---
status: accepted
date: 2026-10-14
deciders: product owner (Q7 accepted), agent
---

# 0016 — Operability baseline: structured logs, correlation id, OpenTelemetry, probes, migration job

## Context and problem statement
Deploying to dev requires minimal operability (Q7). AGENTS §4 (Observability) requires OpenTelemetry traces, metrics and logs, Micrometer, structured JSON logs with tenant and correlation id, and never logging content, tokens or personal data beyond ids.

## Decision outcome

**Logs**
- Spring Boot structured logging writes **ECS JSON** to stdout when the deployment sets `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (Compose on-prem profile, Helm chart). Local dev and tests keep readable text.

**Correlation**
- `CorrelationIdFilter`, ordered first, accepts an inbound `X-Request-Id` only if it matches `^[A-Za-z0-9-]{8,64}$`, otherwise generates one.
- The id goes into MDC (`correlation.id`) and the response header, and is propagated by nginx (`$request_id`).
- The tenant id goes into MDC (`tenant.id`) while a tenant transaction is bound.

**Telemetry**
- `spring-boot-starter-opentelemetry` is wired.
- OTLP export is **off by default** for traces, metrics and logs; it is enabled per environment with `MEMO_OTEL_EXPORT=true` and the endpoint.
- No external collector is required to run the product.

**Probes**
- Actuator runs on management port **8081**, which the web proxy does not expose.
- Exposed endpoints: `health` with `liveness` and `readiness` groups (readiness includes the DB), and `prometheus`/`metrics` only when enabled.
- The public `/api/v1/health` stays as designed in E01.

**Migration job**
- `memo.mode=migrate` starts without a web server, runs Modulith Flyway as `memo_migrator`, and exits 0.
- Application instances set `spring.flyway.enabled=false` and receive only app-role credentials.

### Consequences
- Good: deployable and diagnosable from day one without any external SaaS; least privilege for app pods.
- Bad: logs carry ids only. Investigating a problem means correlating by `correlation.id` and the audit log (Phase 1a), by design.

### Confirmation
- `CorrelationIdFilterTest`, `StructuredLoggingIT` (JSON with `correlation.id`, no secrets), `ProbesIT` (liveness/readiness on 8081, not on 8080), `MigrateModeIT` (migrate-only exits 0 and leaves the schema migrated).
- The image smoke test runs the migration job before the app.
