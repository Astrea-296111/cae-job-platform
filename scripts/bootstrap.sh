#!/usr/bin/env bash
# Run in the existing checkout. Every cloud task already has an isolated workspace.
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
mkdir -p "$DOCKER_CONFIG"
toolchain_root=${CAE_TOOLCHAIN_ROOT:-/workspace/toolchains}
if [[ ! -d /workspace ]]; then
  toolchain_root=${CAE_TOOLCHAIN_ROOT:-$repo_root/.local/toolchains}
fi

java_command=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_command="$JAVA_HOME/bin/java"
elif [[ -x "$toolchain_root/jdk-17/bin/java" ]]; then
  java_command="$toolchain_root/jdk-17/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_command=$(command -v java)
fi
java17=false
if [[ -n "$java_command" ]] && "$java_command" -version 2>&1 | head -1 | grep -Eq 'version "17\.'; then
  java17=true
fi
maven_present=false
if [[ -n "${MVN:-}" ]] || [[ -x "$toolchain_root/maven/bin/mvn" ]] || command -v mvn >/dev/null 2>&1; then
  maven_present=true
fi
if [[ "$java17" != true || "$maven_present" != true ]]; then
  command -v docker >/dev/null 2>&1 || { printf '%s\n' 'Install Java 17 and Maven 3.9+, or install Docker to extract the pinned official toolchain.' >&2; exit 1; }
  docker info >/dev/null
  image=maven:3.9.9-eclipse-temurin-17
  docker pull "$image"
  container=$(docker create "$image")
  cleanup_container() { docker rm "$container" >/dev/null; }
  trap cleanup_container EXIT
  mkdir -p "$toolchain_root/jdk-17" "$toolchain_root/maven"
  docker cp "$container:/opt/java/openjdk/." "$toolchain_root/jdk-17/"
  docker cp "$container:/usr/share/maven/." "$toolchain_root/maven/"
  cleanup_container
  trap - EXIT
fi
if [[ -x "$toolchain_root/jdk-17/bin/java" ]]; then
  export JAVA_HOME="$toolchain_root/jdk-17"
elif [[ -z "${JAVA_HOME:-}" && -n "$java_command" ]]; then
  export JAVA_HOME=$(dirname -- "$(dirname -- "$(readlink -f -- "$java_command")")")
fi
if [[ "${1:-}" == --toolchain-only ]]; then
  exit 0
fi

for public_dir in "$repo_root/deploy" "$repo_root/demo-data"; do
  if [[ -d "$public_dir" ]]; then
    find "$public_dir" \( -name ".env" -o -name ".env.*" -o -name ".local" \) -prune \
      -o -type d -exec chmod a+rx {} + \
      -o -type f -exec chmod a+rX {} +
  fi
done
chmod a+rx "$repo_root"
bash "$repo_root/scripts/configure-cloud-build.sh"
bash "$repo_root/scripts/mvn.sh" -f "$repo_root/backend/pom.xml" dependency:go-offline
worker_venv="$repo_root/worker/.venv"
if [[ ! -x "$worker_venv/bin/python" && -x "$repo_root/.venv/bin/python" ]]; then
  worker_venv="$repo_root/.venv"
fi
if [[ ! -x "$worker_venv/bin/python" ]]; then python3 -m venv "$worker_venv"; fi
if [[ -n "${SSL_CERT_FILE:-}" && -z "${PIP_CERT:-}" ]]; then export PIP_CERT="$SSL_CERT_FILE"; fi
"$worker_venv/bin/python" -m pip install -r "$repo_root/worker/requirements-dev.txt"
if [[ ! -f "$repo_root/frontend/package-lock.json" ]]; then
  printf '%s\n' 'frontend/package-lock.json is required; run npm install once during development and commit the lockfile.' >&2
  exit 1
fi
mkdir -p "$repo_root/.local/npm"
npm --prefix "$repo_root/frontend" ci --cache "$repo_root/.local/npm" --no-audit --no-fund
printf '%s\n' 'Development dependencies ready. Start all services with make up.'
