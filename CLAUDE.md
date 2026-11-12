# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

OS Service: Spring Boot 4 / Java 21 REST API for a mechanic shop (customers, vehicles, stock, service catalog, service-order opening/status/history) and **orchestrator of the service-order Saga**. It is the Fase 3 monolith reduced for Fase 4 (docs and READMEs are in Portuguese): budgets/approval/payment moved to `../billing-service`, diagnosis/repair moved to `../execution-service`. The three services talk only through RabbitMQ; none reads another's database. To run them together use `docker-compose.full.yaml` in this repo (expects the other two repos as sibling directories; Kong on :8000 is the single entry point); `scripts/saga_e2e.sh <happy|budget_rejected|payment_refused|repair_failed>` drives the flows end to end through Kong.

Historical repositories from previous phases (not part of the Phase 4 deployment):

- `mechanic-shop-pos-tech-challenger-lambda`: previous CPF-login Lambda (Java, Maven). The Phase 4 gateway does not route to it. The legacy `GET /internal/customers/{cpf}/status` controller remains but is not publicly routed.
- `mechanic-shop-pos-tech-challenger-kubernetes`: previous Terraform for VPC/EKS, Lambda infra and observability. Phase 4 shared EKS and OS RDS live in this repository's `terraform/`.
- `mechanic-shop-pos-tech-challenger-managed-database`: previous private RDS Terraform. Phase 4 SQL databases are managed by the OS and Billing repositories independently.

Request path: Kong gateway (`kong/kong.yml`, port 8000) → this API (8080); `POST /api/auth/login` handles email/password login in OS. The Phase 4 gateway has no CPF Lambda route. Customer routes are additionally protected at Kong.

## Commands

```bash
docker compose up --build          # this service alone (Kong :8000, API :8080, Postgres, RabbitMQ :15672, Mailpit :8025, pgAdmin :5050); uses .env
docker compose down -v             # also wipes local DB volume
./mvnw test                        # unit tests
./mvnw verify                      # what CI runs: unit + IT (needs Docker) + BDD + 80% merged coverage gate
./mvnw spring-boot:run             # needs SPRING_DATASOURCE_URL/USERNAME/PASSWORD pointing at a Postgres
./mvnw test -Dtest=ClassName#method   # single test
```

Swagger: `http://localhost:8080/swagger-ui.html`; health: `/actuator/health`. Load tests live in `k6/` (see its README).

CI (`.github/workflows/ci-cd.yml`, branch `master`): `./mvnw verify` + coverage artifact + SonarQube (when `SONAR_TOKEN` is set) on PRs; on push also builds/pushes the Docker image and renders/applies `k8s/` manifests to EKS (secrets/vars come from GitHub, DB creds as `DATABASE_*`, broker creds as `RABBITMQ_*`). Run this pipeline before Billing/Execution on a fresh cluster.

## Architecture

Package root `org.project.mechanic_shop`, hexagonal-style layering:

- `domain/`: entities (one subpackage each, e.g. `service_order`, `saga`), enums, DTOs, domain events, validation. Entities are domain objects, not JPA entities.
- `application/`: services (`services/impl`), `ports/` (repository ports, `EmailService`, `SagaCommandPublisher`, `ProcessedMessagePort`), `saga/` (orchestrator, message types and payloads), `mappers`, `validators`, and `listeners` that react to domain events (`ServiceOrderNotificationListener`, `StockNotificationListener`) to send email asynchronously.
- `infrastructure/`: `jpa/entities` + `jpa/repositories` + `jpa/mappers` (persistence model, mapped to/from domain), `adapters/` implementing the ports, `messaging/` (RabbitMQ topology, reply listener, outbox relay, Saga timeout scheduler), `http/` outbound clients.
- `presentation/`: controllers and middlewares.
- `shared/config/`: security (JWT + role-based authz), swagger, jpa, observability (New Relic), system config; `shared/exception`.

When adding a persistence feature: domain entity → port in `application/ports` → JPA entity/mapper/repository + adapter in `infrastructure`.

Service-order status flow: `RECEIVED` → `DIAGNOSIS` → `PENDING_APPROVAL` → `PENDING_PAYMENT` → `IN_PROGRESS` → `COMPLETED` → `DELIVERED`; `CANCELED` on budget rejection or Saga compensation. Only opening and delivery are REST actions here; every other transition is made by `ServiceOrderSagaOrchestrator` when a reply arrives.

Saga rules to keep when changing it: one reply = one local transaction (lock the saga row, check the step, change the order, write the next command to the outbox); commands go through `SagaCommandPublisher` (outbox), never straight to RabbitMQ; handlers must stay idempotent and ignore replies for a step the Saga is not waiting at; do not catch exceptions thrown by `@Transactional` beans inside a handler (the transaction is already rollback-only) — check through repository ports first. The message contract (`SagaMessage`, type names, payloads) is duplicated in the three services: change all three together.

Tests: `saga/SagaTestHarness` wires the real orchestrator to in-memory ports; it backs both `ServiceOrderSagaOrchestratorTest` and the Cucumber steps (`bdd/`, features in `src/test/resources/features`).

Config: `application.yaml` (Hibernate `ddl-auto: update`, no migration tool; Saga timeouts under `app.saga`), `application-prod.yaml`, `application-test.yaml` (`create-only`). Deployment manifests in `k8s/` also deploy the shared RabbitMQ, Kong (routes `/api/billing/**` and `/api/executions/**` to the other services) and Mailpit; CI adds an optional SonarQube step (`SONAR_TOKEN`). `k6/` load-tests the whole Saga through Kong (`SCENARIO=smoke|baseline|stress|spike`); local kind setup under `docs/infra`; architecture docs in `docs/architecture` (C4 of the distributed system, `saga.md`, AWS deployment); New Relic notes in `docs/observability`.
