#!/usr/bin/env bash
# Tests use owned MySQL schemas and Redis namespaces, separate from the demo.
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
  rm -rf -- "$test_root"
  if [[ "$verify_only" == true && "$result" == 0 ]]; then
    local after remaining redis_remaining redis_demo_after
    after=$(mysql_root "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='cae'; SELECT COUNT(*) FROM cae.cae_job;")
    remaining=$(mysql_root "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='$schema'; SELECT COUNT(*) FROM mysql.db WHERE Db='$schema' AND User='cae';")
    redis_remaining=$(redis_cli --scan --pattern "$schema:*")
    redis_demo_after=$(redis_cli EXISTS cae:node:node-a cae:node:node-b cae:node:node-c)
    if [[ "$before" != "$after" || "$remaining" != $'0\n0' || -n "$redis_remaining" || -e "$test_root" ]]; then
      printf '%s\n' 'Isolation verification failed: demo counts changed or owned resources remain.' >&2
      result=1
    elif [[ "$redis_demo_before" == 0 && "$redis_demo_after" != 0 ]]; then
      printf '%s\n' 'Demo Redis node keys appeared during verification; rerun with stable demo services.' >&2
      result=1
    else
      printf '%s\n' 'MySQL schema/grant and Redis namespace cleanup verified; live cae table and job counts unchanged.'
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
fi
# Create and grant separately: only a successfully created schema is ours to remove.
mysql_root "CREATE DATABASE $schema CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
schema_owned=true
mysql_root "GRANT ALL PRIVILEGES ON $schema.* TO 'cae'@'%';"
grant_owned=true

if [[ "$verify_only" == true ]]; then
  mysql_app "CREATE TABLE $schema.isolation_probe (id INT PRIMARY KEY); INSERT INTO $schema.isolation_probe VALUES (1);"
  [[ "$(mysql_app "SELECT COUNT(*) FROM $schema.isolation_probe;")" == 1 ]]
  [[ "$(redis_cli SET "$schema:node:isolation-probe" owned EX 60)" == OK ]]
  [[ "$(redis_cli EXISTS "$schema:node:isolation-probe")" == 1 ]]
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
DATA_ROOT="$test_root/data" CASE_ROOT="$repo_root/demo-data" \
  bash "$repo_root/scripts/mvn.sh" -f "$repo_root/backend/pom.xml" "$@"
