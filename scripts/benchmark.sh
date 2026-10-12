#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
mkdir -p "$repo_root/.local/benchmark"
export CAE_BENCH_JAVA_RESULT="$repo_root/.local/benchmark/java.json"
bash scripts/test-backend.sh test-compile exec:java -Dexec.mainClass=com.example.cae.LocalBenchRunner -Dexec.classpathScope=test > "$repo_root/.local/benchmark/java.log" 2>&1
worker_python="$repo_root/worker/.venv/bin/python"
if [[ ! -x "$worker_python" ]]; then worker_python="$repo_root/.venv/bin/python"; fi
exec "$worker_python" tests/e2e/benchmark.py
