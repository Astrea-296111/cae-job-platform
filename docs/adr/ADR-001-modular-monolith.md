# ADR-001: Modular monolith

Status: accepted.

Use one Java 17 / Spring Boot 3.3.6 Maven module with domain, application, repository and infrastructure package boundaries. Python agents run independently because solver processes must survive backend restart. Vue is a separate client. This avoids distributed transactions between artificial business microservices while preserving execution-plane isolation.

MySQL is the business source of truth; a shared filesystem stores input snapshots and solver files. Redis is disposable state and RabbitMQ transports at-least-once commands. Commercial tool integration will replace adapters, not the job lifecycle.
