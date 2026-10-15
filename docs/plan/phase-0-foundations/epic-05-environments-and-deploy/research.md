# E05 Environments, deployment and operability — research

Sources accessed **2026-10-14**: GitHub Releases API (Helm 4.3.0, kind 0.33.0, kubeconform 0.8.0, Caddy 2.11.7, Ansible-core 2.21.5, ZAP 2.17.0, Gatling 3.16.0), Spring Boot 4.1 reference (structured logging, Actuator probes, OpenTelemetry starter), Docker Hub.

## R17 — IaC tool and hosting target (Q2 answered: the owner's own server/VM)

| Option | Fits "own server" | Notes |
|---|---|---|
| OpenTofu/Terraform | partly | Strong for cloud APIs; for a single existing VM it adds state management without benefit |
| **Ansible (ansible-core 2.21, GPL-3.0, run as a separate tool, never linked)** | yes | Idempotent provisioning over SSH: Docker Engine, deploy user, firewall, unattended upgrades, directory layout, TLS proxy. The playbook is the IaC. |
| cloud-init only | first boot only | Not re-runnable for drift |

**Deployment mechanism on the VM:** the **Compose on-prem profile** (the artefact on-prem customers get), driven over SSH by `deploy/scripts/deploy.sh`:
1. pull images by digest;
2. run the migration job;
3. `up -d`;
4. smoke-check health.

Kubernetes would add a control plane to operate for two environments on one VM. The **Helm chart** is still built and verified in CI on a throwaway kind cluster, because SaaS and customer Kubernetes need it.

**TLS on the VM:** Caddy 2.11 (Apache-2.0) as the edge proxy, with ACME certificates for the dev/staging hostnames, or owner-provided certificates for internal hostnames. On-prem customers usually bring their own TLS terminator, so Caddy is an optional profile.

## R18 — Helm conventions (multi-component, customer Kubernetes)
- One chart `deploy/helm/memo` with components backend, web and migrations (a Job that runs as a `pre-install,pre-upgrade` hook).
- Secrets are referenced by name (`existingSecret`), never placed in values.
- `values.schema.json` validates values.
- Probes use the Actuator liveness/readiness groups. Resource requests and limits are set.
- `securityContext`: non-root, `readOnlyRootFilesystem`, `drop: [ALL]`, `seccompProfile: RuntimeDefault`, plus `emptyDir` for `/tmp`.
- NetworkPolicies: default deny; ingress → web → backend; backend → DB/S3/ONLYOFFICE/ClamAV/SMTP; the **ONLYOFFICE egress policy allows the backend only** (no internet). Third-party services such as SeaweedFS, ONLYOFFICE and ClamAV are external or optional subcharts, decided per installation; the Phase 0 chart takes their endpoints as values.
- Verification: `helm lint`, `helm template | kubeconform -strict`, and an install on kind with the backend reaching PostgreSQL (CI job).

## Operability (Q7 accepted)

| Need | Spring Boot 4.1 capability | Choice |
|---|---|---|
| Structured JSON logs | built-in structured logging (`logging.structured.format.console=ecs\|logstash\|gelf`) | **ECS JSON** in deployed profiles; human-readable text in `dev` |
| Correlation id | MDC + filter | `X-Request-Id`: accepted if it is a well-formed UUID or token, otherwise generated. Put in MDC as `correlation.id`, echoed in the response. nginx forwards `$request_id`. |
| Tenant id in logs | MDC | Set by `TenantTransactions` when bound. The full request tenant context comes in Phase 1a. |
| Traces and metrics | `spring-boot-starter-opentelemetry` (Micrometer → OTLP) | Wired. **Export disabled by default** (`management.otlp.*.export.enabled=false`), switched on per environment when a collector exists. |
| Probes | Actuator `health` groups `liveness` / `readiness` on a separate management port (8081, not exposed through the web proxy) | readiness includes the DB |

## Migrations as a separate job (AGENTS §4)
- The same backend image starts with `--memo.mode=migrate`: no web server, Modulith Flyway runs as `memo_migrator`, then the process exits 0.
- Application pods run with `spring.flyway.enabled=false` and never receive migrator credentials (least privilege).
- Local dev keeps migrate-on-start for convenience.

## DAST and performance
- **OWASP ZAP 2.17 baseline** (`zaproxy/zap-stable`, Apache-2.0): passive scan nightly against dev, with a rules file for accepted findings.
- **Gatling 3.16** (Gatling Gradle plugin, Apache-2.0) with Kotlin simulations in a `perf` Gradle project. Phase 0 ships the health smoke simulation and the structure of the synthetic-volume generator.

## Industry practice
- **GitLab Omnibus and Mattermost:** a single-VM Docker deployment for small installs plus a Helm chart for Kubernetes, with the same images. Adopted.
- **Twelve-factor:** config from the environment; logs as event streams (JSON to stdout); admin processes (migrations) run as one-off processes from the same release. Adopted.
