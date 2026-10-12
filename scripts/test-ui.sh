#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
browser_venv="$repo_root/.local/browser-venv"
if [[ ! -x "$browser_venv/bin/python" ]]; then
  python3 -m venv "$browser_venv"
fi
"$browser_venv/bin/python" -m pip install --cache-dir "$repo_root/.local/pip-cache" -r tests/e2e/requirements-browser.txt >&2
# Use the host's Chromium when present; otherwise install the pinned Playwright browser.
if ! command -v chromium >/dev/null 2>&1; then
  if [[ -n "${SSL_CERT_FILE:-}" ]]; then export NODE_EXTRA_CA_CERTS="$SSL_CERT_FILE"; fi
  export PLAYWRIGHT_BROWSERS_PATH="$repo_root/.local/browsers"
  "$browser_venv/bin/python" -m playwright install chromium >&2
fi
"$browser_venv/bin/python" tests/e2e/ui_smoke.py
