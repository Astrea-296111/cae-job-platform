# Architecture

Public CAE control plane. Independent Python agents execute synthetic solvers; commercial CAE algorithms and licenses are outside this repository.

```mermaid
flowchart LR
    UI[Vue 3] --> N[Nginx]
    N --> B[Spring Boot / Java 17]
    B --> DB[(MySQL business state)]
    B --> R[(Redis snapshots)]
    B --> MQ[RabbitMQ commands]
    MQ --> B
    B --> A[Python node agents]
    A --> S[Independent mock solver processes]
    B --> FS[(Shared filesystem)]
    S --> FS
    B --> P[Parser / post / draft report]
    P --> FS
    B --> M[Prometheus / Grafana]
```

This describes the target design. Implemented and validated milestones are recorded in `progress.md`.
