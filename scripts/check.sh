#!/usr/bin/env bash
# Validate each milestone and build changed application images, reusing valid outputs.
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

declare -A requested=()
explicit_targets=false
all_images=false
for argument in "$@"; do
  case "$argument" in
    --all-images) all_images=true ;;
    backend|worker|frontend) requested["$argument"]=1; explicit_targets=true ;;
    -h|--help)
      printf '%s\n' 'Usage: scripts/check.sh [--all-images] [backend|worker|frontend ...]' \
        'All existing tests run. Docker builds changed or missing application images by default.'
      exit 0
      ;;
    *) printf 'Unknown check target: %s\n' "$argument" >&2; exit 2 ;;
  esac
done

bash scripts/test-backend.sh spotless:check verify
worker_python="$repo_root/worker/.venv/bin/python"
if [[ ! -x "$worker_python" ]]; then worker_python="$repo_root/.venv/bin/python"; fi
(cd worker && "$worker_python" -m ruff check . && "$worker_python" -m ruff format --check . && "$worker_python" -m pytest -q)
npm --prefix frontend run lint
npm --prefix frontend run build

export DOCKER_CONFIG=${DOCKER_CONFIG:-$repo_root/.local/docker}
mkdir -p "$DOCKER_CONFIG"
compose=(docker compose -f deploy/docker-compose.yml)
"${compose[@]}" config --quiet

if [[ "$all_images" == true ]]; then
  requested[backend]=1
  requested[worker]=1
  requested[frontend]=1
elif [[ "$explicit_targets" == false ]]; then
  # Include staged/unstaged changes and new source files without listing secret values.
  while IFS= read -r -d '' changed_path; do
    case "$changed_path" in
      backend/*) requested[backend]=1 ;;
      worker/*) requested[worker]=1 ;;
      frontend/*) requested[frontend]=1 ;;
    esac
  done < <(git diff --name-only -z HEAD --; git ls-files --others --exclude-standard -z)
fi

# A clean checkout still builds any application image that has not been produced yet.
for application in backend worker frontend; do
  if ! docker image inspect "cae-$application:local" >/dev/null 2>&1; then
    requested["$application"]=1
  fi
done

storage_driver=$(docker info --format '{{.Driver}}')
guard_build_space() {
  local threshold_mb=${1:-6144}
  local available_mb
  available_mb=$(df -Pk "$repo_root" | awk 'NR == 2 { print int($4 / 1024) }')
  if [[ "$storage_driver" == vfs && "$available_mb" -lt "$threshold_mb" ]]; then
    printf 'Docker vfs storage has %s MiB free; releasing unused build cache and dangling images.\n' "$available_mb"
    # Keep every container, named image and volume, including running Solver state.
    docker builder prune -af
    docker image prune -f
    available_mb=$(df -Pk "$repo_root" | awk 'NR == 2 { print int($4 / 1024) }')
    if [[ "$available_mb" -lt "$threshold_mb" ]]; then
      printf 'Docker vfs still has %s MiB free; %s MiB is required before continuing. Existing containers, named images and volumes were retained.\n' "$available_mb" "$threshold_mb" >&2
      return 1
    fi
  fi
}

built=false
for application in backend worker frontend; do
  if [[ "${requested[$application]:-0}" == 1 ]]; then
    # The Java build copies a full JDK/Maven filesystem under vfs; the smaller
    # Python/Node builds retain a 4 GiB reserve without discarding useful caches.
    build_reserve=4096
    if [[ "$application" == backend ]]; then build_reserve=6144; fi
    guard_build_space "$build_reserve"
    service=$application
    if [[ "$application" == worker ]]; then service=worker-node-a; fi
    printf 'Building affected application image: %s\n' "$application"
    "${compose[@]}" build "$service"
    # Free unused snapshots before container recreation if the build used the reserve.
    guard_build_space 4096
    built=true
  fi
done
if [[ "$built" == false ]]; then
  printf '%s\n' 'Application sources unchanged; reusing existing Docker images.'
fi
