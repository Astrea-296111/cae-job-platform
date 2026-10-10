#!/usr/bin/env bash
# Optional: persist this machine's existing proxy and public trust stores for builds.
# Uses BuildKit secrets, so proxy credentials and certificate bundles never enter images.
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
export CAE_REPO_ROOT="$repo_root"
python3 - <<'PY'
import os
from pathlib import Path
import socket
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

root = Path(os.environ['CAE_REPO_ROOT'])
proxy_url = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy')
if not proxy_url:
    print('No existing HTTPS_PROXY; Compose will use normal direct-network builds.')
    raise SystemExit(0)
proxy = urlsplit(proxy_url)
if not proxy.hostname:
    raise SystemExit('HTTPS_PROXY must be a valid proxy URL')
proxy_ip = socket.gethostbyname(proxy.hostname)
local = root / '.local' / 'cloud-build'
local.mkdir(parents=True, exist_ok=True)
proxy_file = local / 'proxy'
proxy_file.write_text(proxy_url)
proxy_file.chmod(0o600)
settings = ET.Element('settings')
element = ET.SubElement(ET.SubElement(settings, 'proxies'), 'proxy')
values = {'id': 'cloud-egress', 'active': 'true', 'protocol': 'http',
          'host': proxy.hostname, 'port': str(proxy.port or 8080),
          'nonProxyHosts': 'localhost|127.0.0.1'}
if proxy.username:
    values['username'] = unquote(proxy.username)
if proxy.password:
    values['password'] = unquote(proxy.password)
for key, value in values.items():
    ET.SubElement(element, key).text = value
settings_file = local / 'maven-settings.xml'
ET.ElementTree(settings).write(settings_file, encoding='unicode')
settings_file.chmod(0o600)
config = {
    'CAE_BUILD_PROXY': str(proxy_file),
    'CAE_BUILD_PROXY_HOST': proxy.hostname,
    'CAE_BUILD_PROXY_IP': proxy_ip,
    'CAE_MAVEN_SETTINGS': str(settings_file),
}
ca_path = os.environ.get('SSL_CERT_FILE', '/etc/ssl/certs/ca-certificates.crt')
if Path(ca_path).is_file():
    config['CAE_TLS_CA'] = ca_path
java_path = '/etc/ssl/certs/java/cacerts'
if Path(java_path).is_file():
    config['CAE_JAVA_TRUSTSTORE'] = java_path
env = root / '.env'
existing = env.read_text() if env.exists() else ''
begin = '# BEGIN CAE MANAGED CLOUD BUILD'
end = '# END CAE MANAGED CLOUD BUILD'
# Refresh only the block owned by this script; preserve all user configuration.
lines = []
in_managed = False
for line in existing.splitlines():
    if line == begin:
        in_managed = True
    elif line == end:
        in_managed = False
    elif not in_managed:
        lines.append(line)
known = {line.split('=', 1)[0] for line in lines if '=' in line and not line.startswith('#')}
managed = [f'{key}={value}' for key, value in config.items() if key not in known]
if managed:
    if lines and lines[-1]:
        lines.append('')
    lines.extend([begin, *managed, end])
env.write_text('\n'.join(lines) + '\n')
env.chmod(0o600)
print('Local cloud-build configuration ready; TLS verification remains enabled.')
PY
