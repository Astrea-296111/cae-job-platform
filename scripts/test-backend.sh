#!/usr/bin/env bash
# Tests use owned MySQL schemas, Redis namespaces and RabbitMQ vhosts.
set +x
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
mkdir -p "$DOCKER_CONFIG"
compose=(docker compose -f deploy/docker-compose.yml)

verify_only=false
if [[ "${1:-}" == --verify-isolation ]]; then
  verify_only=true
  shift
elif [[ "${1:-}" == --help || "${1:-}" == -h ]]; then
  printf '%s\n' 'Usage: scripts/test-backend.sh [Maven arguments ...]' \
    '       scripts/test-backend.sh --verify-isolation' \
    'Default Maven arguments: spotless:check verify. Owned test resources are removed on exit.'
  exit 0
fi
if [[ "$#" == 0 ]]; then set -- spotless:check verify; fi

suffix=$(python3 -c 'import uuid; print(uuid.uuid4().hex)')
schema="cae_test_$suffix"
[[ "$schema" =~ ^cae_test_[0-9a-f]{32}$ ]] || { printf '%s\n' 'Invalid generated test schema name.' >&2; exit 1; }
test_root=$(mktemp -d /tmp/cae-backend-tests.XXXXXXXX)
schema_owned=false
grant_owned=false
rabbit_vhost_owned=false

mysql_root() {
  # Authentication stays in the MySQL container's injected environment; values are not logged.
  "${compose[@]}" exec -T mysql sh -c \
    'test -n "$MYSQL_ROOT_PASSWORD" || exit 1; MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --protocol=socket --batch --skip-column-names' \
    <<< "$1"
}
mysql_app() {
  "${compose[@]}" exec -T mysql sh -c \
    'test "$MYSQL_USER" = cae && test -n "$MYSQL_PASSWORD" || exit 1; MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --protocol=socket --batch --skip-column-names' \
    <<< "$1"
}
redis_cli() {
  "${compose[@]}" exec -T redis redis-cli -e --raw "$@"
}
redis_cleanup() {
  local key
  local -a batch=()
  # Capture SCAN completion before deleting; command failures must propagate to cleanup.
  redis_cli --scan --pattern "$schema:*" > "$test_root/redis-keys" || return 1
  while IFS= read -r key; do
    [[ -n "$key" ]] || continue
    [[ "$key" == "$schema:"* ]] || return 1
    batch+=("$key")
    if [[ "${#batch[@]}" == 100 ]]; then
      redis_cli UNLINK "${batch[@]}" > /dev/null || return 1
      batch=()
    fi
  done < "$test_root/redis-keys"
  if [[ "${#batch[@]}" != 0 ]]; then
    redis_cli UNLINK "${batch[@]}" > /dev/null || return 1
  fi
}
rabbit_cli() {
  "${compose[@]}" exec -T rabbitmq rabbitmqctl -q "$@"
}
rabbit_vhost_exists() {
  rabbit_cli list_vhosts name --formatter=json |
    python3 -c 'import json,sys; print(sum(v["name"] == sys.argv[1] for v in json.load(sys.stdin)))' "$schema"
}
rabbit_demo_counts() {
  rabbit_cli list_queues -p / name messages --formatter=json |
    python3 -c 'import json,sys; print(json.dumps(sorted(json.load(sys.stdin), key=lambda q: q["name"]), sort_keys=True))'
}
rabbit_probe() {
  CAE_ISOLATION_MQ_USER="$mq_user" CAE_ISOLATION_MQ_PASSWORD="$mq_password" \
  CAE_ISOLATION_MQ_VHOST="$schema" \
  CAE_ISOLATION_MQ_HOST="${CAE_TEST_MQ_HOST:-127.0.0.1}" \
  CAE_ISOLATION_MQ_PORT="${CAE_TEST_MQ_MANAGEMENT_PORT:-15672}" python3 - <<'PY'
import base64
import json
import os
import urllib.parse
import urllib.request

credentials = f'{os.environ["CAE_ISOLATION_MQ_USER"]}:{os.environ["CAE_ISOLATION_MQ_PASSWORD"]}'
authorization = "Basic " + base64.b64encode(credentials.encode()).decode()
base = f'http://{os.environ["CAE_ISOLATION_MQ_HOST"]}:{os.environ["CAE_ISOLATION_MQ_PORT"]}/api'
vhost = urllib.parse.quote(os.environ["CAE_ISOLATION_MQ_VHOST"], safe="")
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def request(method, path, payload):
    req = urllib.request.Request(
        base + path,
        data=json.dumps(payload).encode(),
        method=method,
        headers={"Authorization": authorization, "Content-Type": "application/json"},
    )
    with opener.open(req, timeout=10) as response:
        body = response.read()
        return json.loads(body) if body else None

request("PUT", f"/queues/{vhost}/isolation-probe", {"durable": False, "auto_delete": False, "arguments": {}})
published = request("POST", f"/exchanges/{vhost}/amq.default/publish", {
    "properties": {}, "routing_key": "isolation-probe", "payload": "owned-test-message", "payload_encoding": "string"
})
assert published["routed"], "Owned RabbitMQ probe was not routed"
messages = request("POST", f"/queues/{vhost}/isolation-probe/get", {
    "count": 1, "ackmode": "ack_requeue_true", "encoding": "auto"
})
assert len(messages) == 1 and messages[0]["payload"] == "owned-test-message", "Owned RabbitMQ message probe failed"
PY
}
cleanup() {
  local result=$?
  trap - EXIT
  if [[ "$grant_owned" == true ]]; then
    if ! mysql_root "REVOKE ALL PRIVILEGES ON $schema.* FROM 'cae'@'%';"; then
      printf 'Could not remove privileges for owned test schema %s.\n' "$schema" >&2
      if [[ "$result" == 0 ]]; then result=1; fi
    fi
  fi
  if [[ "$schema_owned" == true ]]; then
    if ! mysql_root "DROP DATABASE IF EXISTS $schema;"; then
      printf 'Could not remove owned test schema %s.\n' "$schema" >&2
      if [[ "$result" == 0 ]]; then result=1; fi
    fi
  fi
  if ! redis_cleanup; then
    printf 'Could not remove Redis keys in owned test namespace %s.\n' "$schema" >&2
    if [[ "$result" == 0 ]]; then result=1; fi
  fi
  if [[ "$rabbit_vhost_owned" == true ]]; then
    if ! rabbit_cli delete_vhost "$schema" > /dev/null; then
      printf 'Could not remove owned RabbitMQ vhost %s.\n' "$schema" >&2
      if [[ "$result" == 0 ]]; then result=1; fi
    fi
  fi
  rm -rf -- "$test_root"
  if [[ "$verify_only" == true && "$result" == 0 ]]; then
    local after remaining redis_remaining redis_demo_after rabbit_remaining rabbit_after
    after=$(mysql_root "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='cae'; SELECT COUNT(*) FROM cae.cae_job;")
    remaining=$(mysql_root "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$schema'; SELECT COUNT(*) FROM mysql.db WHERE Db='$schema' AND User='cae';")
    redis_remaining=$(redis_cli --scan --pattern "$schema:*")
    redis_demo_after=$(redis_cli EXISTS cae:node:node-a cae:node:node-b cae:node:node-c)
    rabbit_remaining=$(rabbit_vhost_exists)
    rabbit_after=$(rabbit_demo_counts)
    if [[ "$before" != "$after" || "$remaining" != $'0\n0' || -n "$redis_remaining" || "$rabbit_remaining" != 0 || "$rabbit_before" != "$rabbit_after" || -e "$test_root" ]]; then
      printf '%s\n' 'Isolation verification failed: demo counts changed or owned resources remain.' >&2
      result=1
    elif [[ "$redis_demo_before" == 0 && "$redis_demo_after" != 0 ]]; then
      printf '%s\n' 'Demo Redis node keys appeared during verification; rerun with stable demo services.' >&2
      result=1
    else
      printf '%s\n' 'MySQL schema/grant, Redis namespace and RabbitMQ vhost cleanup verified; live demo data and queue counts unchanged.'
    fi
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ "$verify_only" == true ]]; then
  before=$(mysql_root "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='cae'; SELECT COUNT(*) FROM cae.cae_job;")
  redis_demo_before=$(redis_cli EXISTS cae:node:node-a cae:node:node-b cae:node:node-c)
  rabbit_before=$(rabbit_demo_counts)
fi
# Create and grant separately: only a successfully created schema is ours to remove.
mysql_root "CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
schema_owned=true
mysql_root "GRANT ALL PRIVILEGES ON $schema.* TO 'cae'@'%';"
grant_owned=true

mq_user=$("${compose[@]}" exec -T rabbitmq sh -c 'printf "%s" "$RABBITMQ_DEFAULT_USER"')
mq_password=$("${compose[@]}" exec -T rabbitmq sh -c 'printf "%s" "$RABBITMQ_DEFAULT_PASS"')
[[ "$mq_user" == cae && -n "$mq_password" ]] || { printf '%s\n' 'Expected the existing cae RabbitMQ user binding.' >&2; exit 1; }
[[ "$(rabbit_vhost_exists)" == 0 ]] || { printf '%s\n' 'Generated test RabbitMQ vhost already exists; refusing to adopt it.' >&2; exit 1; }
rabbit_cli add_vhost "$schema" > /dev/null
rabbit_vhost_owned=true
rabbit_cli set_permissions -p "$schema" "$mq_user" '.*' '.*' '.*' > /dev/null

if [[ "$verify_only" == true ]]; then
  mysql_app "CREATE TABLE $schema.isolation_probe (id INT PRIMARY KEY); INSERT INTO $schema.isolation_probe VALUES (1);"
  [[ "$(mysql_app "SELECT COUNT(*) FROM $schema.isolation_probe;")" == 1 ]]
  [[ "$(redis_cli SET "$schema:node:isolation-probe" owned EX 60)" == OK ]]
  [[ "$(redis_cli EXISTS "$schema:node:isolation-probe")" == 1 ]]
  rabbit_probe
  # The EXIT trap performs removal and checks both schema and grant disappearance.
  exit 0
fi

# Reuse the running MySQL user's injected binding in subprocess memory only.
# Command substitution captures these values; they never reach the terminal or a file.
db_user=$("${compose[@]}" exec -T mysql sh -c 'printf "%s" "$MYSQL_USER"')
db_password=$("${compose[@]}" exec -T mysql sh -c 'printf "%s" "$MYSQL_PASSWORD"')
[[ "$db_user" == cae && -n "$db_password" ]] || { printf '%s\n' 'Expected the existing cae MySQL user binding.' >&2; exit 1; }
printf 'Using isolated MySQL schema: %s\n' "$schema"
DB_URL="jdbc:mysql://${CAE_TEST_DB_HOST:-127.0.0.1}:${CAE_TEST_DB_PORT:-3306}/$schema?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC" \
DB_USER="$db_user" DB_PASSWORD="$db_password" \
CAE_CACHE_NAMESPACE="$schema" \
SPRING_RABBITMQ_HOST="${CAE_TEST_MQ_HOST:-127.0.0.1}" SPRING_RABBITMQ_PORT="${CAE_TEST_MQ_PORT:-5672}" \
SPRING_RABBITMQ_USERNAME="$mq_user" SPRING_RABBITMQ_PASSWORD="$mq_password" \
SPRING_RABBITMQ_VIRTUAL_HOST="$schema" RABBITMQ_VHOST="$schema" \
CAE_MQ_LISTENERS_ENABLED=false \
DATA_ROOT="$test_root/data" CASE_ROOT="$repo_root/demo-data" \
  bash "$repo_root/scripts/mvn.sh" -f "$repo_root/backend/pom.xml" "$@"
