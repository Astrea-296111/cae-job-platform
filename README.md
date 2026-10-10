# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestone 0 is validated: Java/Python/Vue bootstrap and all Compose services start. Business functionality is under implementation. See [progress](docs/progress.md).

## Development

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

Demo credentials (local demonstration only): `admin/admin123`, `engineer/engineer123`, `lead/lead123`.

Cloud builds use the existing proxy and public CA without disabling TLS verification:

```bash
bash scripts/configure-cloud-build.sh
DOCKER_CONFIG=.local/docker docker compose -f deploy/docker-compose.yml up -d --build
```

Health endpoints: backend `/actuator/health`, agents `/health`, frontend `/health`.
