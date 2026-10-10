#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
bash scripts/mvn.sh -f backend/pom.xml spotless:check verify
worker_python="$repo_root/.venv/bin/python"
if [[ ! -x "$worker_python" ]]; then worker_python="$repo_root/worker/.venv/bin/python"; fi
(cd worker && "$worker_python" -m ruff check . && "$worker_python" -m ruff format --check . && "$worker_python" -m pytest -q)
npm --prefix frontend run lint
npm --prefix frontend run build
export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
mkdir -p "$DOCKER_CONFIG"
docker compose -f deploy/docker-compose.yml config --quiet
docker compose -f deploy/docker-compose.yml build
