#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
worker_python="$repo_root/worker/.venv/bin/python"
if [[ ! -x "$worker_python" ]]; then worker_python="$repo_root/.venv/bin/python"; fi
if [[ ! -x "$worker_python" ]]; then printf '%s\n' 'Run scripts/bootstrap.sh to install the pinned Python dependencies.' >&2; exit 1; fi
export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
exec "$worker_python" tests/e2e/failure_smoke.py
