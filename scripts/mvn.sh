#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
toolchain_root=${CAE_TOOLCHAIN_ROOT:-/workspace/toolchains}
if [[ ! -d "$toolchain_root" && ! -d /workspace ]]; then
  toolchain_root="$repo_root/.local/toolchains"
fi

if [[ -z "${JAVA_HOME:-}" && -x "$toolchain_root/jdk-17/bin/java" ]]; then
  export JAVA_HOME="$toolchain_root/jdk-17"
fi
if [[ -n "${MVN:-}" ]]; then
  maven_bin=$MVN
elif [[ -x "$toolchain_root/maven/bin/mvn" ]]; then
  maven_bin="$toolchain_root/maven/bin/mvn"
elif command -v mvn >/dev/null 2>&1; then
  maven_bin=$(command -v mvn)
else
  # Copy tools from the official image; avoid daemon/host bind-mount permissions.
  bash "$repo_root/scripts/bootstrap.sh" --toolchain-only
  export JAVA_HOME="$toolchain_root/jdk-17"
  maven_bin="$toolchain_root/maven/bin/mvn"
fi

maven_cache=${CAE_MAVEN_CACHE:-/workspace/maven-cache}
if [[ ! -d /workspace ]]; then
  maven_cache=${CAE_MAVEN_CACHE:-$repo_root/.local/maven-cache}
fi
mkdir -p "$maven_cache"
args=(-B "-Dmaven.repo.local=$maven_cache")
settings_file=""
cleanup() { [[ -z "$settings_file" ]] || rm -f -- "$settings_file"; }
trap cleanup EXIT
if [[ -n "${HTTPS_PROXY:-${https_proxy:-}}" ]]; then
  settings_file=$(mktemp /tmp/cae-maven-settings.XXXXXX.xml)
  CAE_MAVEN_SETTINGS_PATH="$settings_file" python3 - <<'PY'
import os
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

url = urlsplit(os.environ.get("HTTPS_PROXY") or os.environ["https_proxy"])
if not url.hostname:
    raise SystemExit("HTTPS_PROXY must contain a valid proxy URL")
root = ET.Element("settings")
proxy = ET.SubElement(ET.SubElement(root, "proxies"), "proxy")
values = {
    "id": "cloud-egress", "active": "true", "protocol": "http",
    "host": url.hostname, "port": str(url.port or 8080),
    "nonProxyHosts": "localhost|127.0.0.1|mysql|redis|rabbitmq|backend|worker-node-*",
}
if url.username:
    values["username"] = unquote(url.username)
if url.password:
    values["password"] = unquote(url.password)
for key, value in values.items():
    ET.SubElement(proxy, key).text = value
ET.ElementTree(root).write(os.environ["CAE_MAVEN_SETTINGS_PATH"], encoding="unicode")
PY
  args+=(-s "$settings_file")
  if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    export MAVEN_OPTS="${MAVEN_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
  fi
fi
"$maven_bin" "${args[@]}" "$@"
