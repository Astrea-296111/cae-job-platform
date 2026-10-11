SHELL := /bin/bash
export DOCKER_CONFIG ?= $(CURDIR)/.local/docker
COMPOSE := docker compose -f deploy/docker-compose.yml
WORKER_PYTHON ?= $(if $(wildcard worker/.venv/bin/python),$(CURDIR)/worker/.venv/bin/python,$(if $(wildcard .venv/bin/python),$(CURDIR)/.venv/bin/python,python3))

.PHONY: help bootstrap build up down logs ps health test test-backend test-worker test-frontend lint lint-backend lint-worker lint-frontend demo failure-demo benchmark

help:
	@printf '%s\n' 'make bootstrap    Install development prerequisites with scripts/bootstrap.sh' 'make up           Build and start the complete Compose stack' 'make down         Stop containers; retain volumes and results' 'make health       Inspect health of all services' 'make test         Run backend, worker and frontend checks' 'make demo         Run the successful end-to-end demo' 'make failure-demo Run failure acceptance scenarios' 'make benchmark    Run the local benchmark'

bootstrap:
	bash scripts/bootstrap.sh

build:
	$(COMPOSE) build

up:
	$(COMPOSE) up -d --build --wait --wait-timeout 240

down:
	$(COMPOSE) down

logs:
	$(COMPOSE) logs --tail=150 -f

ps:
	$(COMPOSE) ps

health:
	$(COMPOSE) ps
	curl --noproxy "*" --fail --silent http://localhost:8080/actuator/health
	curl --noproxy "*" --fail --silent http://localhost:8001/health
	curl --noproxy "*" --fail --silent http://localhost:8002/health
	curl --noproxy "*" --fail --silent http://localhost:8003/health
	curl --noproxy "*" --fail --silent http://localhost:8088/health

test:
	$(MAKE) test-backend test-worker test-frontend

test-backend:
	bash scripts/test-backend.sh test

test-worker:
	cd worker && "$(WORKER_PYTHON)" -m pytest

test-frontend:
	cd frontend && npm run build

lint:
	$(MAKE) lint-backend lint-worker lint-frontend

lint-backend:
	cd backend && ../scripts/mvn.sh spotless:check

lint-worker:
	cd worker && "$(WORKER_PYTHON)" -m ruff check .
	cd worker && "$(WORKER_PYTHON)" -m ruff format --check .

lint-frontend:
	cd frontend && npm run lint && npm run typecheck

demo:
	bash scripts/demo.sh

failure-demo:
	bash scripts/failure-demo.sh

benchmark:
	bash scripts/benchmark.sh
