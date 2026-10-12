FROM python:3.11.11-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /app
COPY requirements.txt ./requirements.txt
RUN --mount=type=secret,id=build_proxy \
    --mount=type=secret,id=tls_ca \
    set -eu; \
    if [ -s /run/secrets/build_proxy ]; then \
      export HTTPS_PROXY="$(cat /run/secrets/build_proxy)" HTTP_PROXY="$(cat /run/secrets/build_proxy)"; \
    fi; \
    if [ -s /run/secrets/tls_ca ]; then export PIP_CERT=/run/secrets/tls_ca; fi; \
    pip install --no-cache-dir -r requirements.txt
COPY app ./app
COPY agents ./agents
COPY adapters ./adapters
COPY mock_solver ./mock_solver
COPY post ./post

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)" || exit 1
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
