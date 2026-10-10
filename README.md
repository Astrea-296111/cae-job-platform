# CAE Job Platform

Public reproduction of a CAE job control plane and result/report pipeline.
Synthetic cases and mock solver processes only; no commercial solver, enterprise data or license is required.

Milestones 0–3 are validated: all Compose services start; MySQL Job APIs, state/event CAS, role checks, safe Case validation and immutable input snapshots work. Agents run independent mock Solver processes with durable idempotency, query, cancellation and heartbeats. Backend scheduling and the report pipeline are under implementation. See [progress](docs/progress.md).

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
