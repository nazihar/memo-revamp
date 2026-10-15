---
status: accepted
date: 2026-10-14
deciders: product owner (Q2: own server/VM), agent
---

# 0015 — Environments, IaC and deployment topology

## Context and problem statement
Phase 0 Q2: dev and staging run on the owner's own server or VM. AGENTS §2 asks for the same artefacts for SaaS, dedicated and on-prem installs, a Helm chart for Kubernetes, and a Compose profile for small on-prem installs. AGENTS §4 asks for migrations as a separate job with the migration role. Research: `epic-05-environments-and-deploy/research.md`.

## Considered options
1. Kubernetes (k3s) on the VM, deployed with Helm
2. **Compose on-prem profile on the VM, deployed over SSH; Helm chart verified in CI on kind**
3. Manual installation

## Decision outcome
Chosen option: **2**.

**Provisioning**
- `deploy/ansible/site.yml` (ansible-core, run as a separate tool):
  - Docker Engine + Compose plugin;
  - a `memo` deploy user with an SSH key from CI;
  - firewall (only 22, 80 and 443);
  - unattended security upgrades;
  - `/opt/memo/<env>` layout.
- It is idempotent and safe to re-run.

**Environments**
- `dev` and `staging` are two Compose projects on the VM. Each has its own `.env` (secrets written once by the provisioning playbook from Ansible Vault or generated on the host; never stored in git or CI logs) and its own hostname behind the shared Caddy edge proxy (TLS).

**Deploy**
`deploy/scripts/deploy.sh <env> <backend@digest> <web@digest>` runs on the VM:
1. pull;
2. run the migration job (`memo.mode=migrate`, migrator credentials);
3. `up -d` (the app gets app-role credentials only);
4. wait for readiness;
5. smoke-check `/api/v1/health` through the proxy;
6. record the deployed digests.

On failure it rolls back to the previously recorded digests (the migration stays; expand/contract keeps N-1 compatible).

**Triggers**
- `deploy-dev.yml`: on every green `ci` run on `master`, via SSH with a deploy key held in a GitHub environment secret.
- Staging: manual dispatch with approval.

**Kubernetes**
- `deploy/helm/memo` is checked in the PR stage (`helm lint`, kubeconform) and installed on kind nightly.

**Object storage**
- Compose on-prem uses a single SeaweedFS server (ADR-0005 dev topology) with a persistent volume plus `storage-init`.
- Kubernetes installs use external S3-compatible storage (SeaweedFS cluster or the customer's own) given as values.
- The production SeaweedFS cluster topology (separate master, volume and filer, with the filer store in PostgreSQL) is revisited with the first SaaS environment (Phase 7).

### Consequences
- Good: one artefact path for dev, staging and small on-prem installs, so deploys exercise what customers run. Nothing to operate beyond Docker on the VM.
- Bad: no Kubernetes-specific behaviour (NetworkPolicies, probes as Kubernetes uses them) is exercised in dev; kind covers it in CI.
- Owner input needed: VM host/IP, OS, SSH access for CI, DNS names, TLS choice (ACME or own certificates).
