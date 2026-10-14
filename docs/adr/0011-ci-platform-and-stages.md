---
status: accepted
date: 2026-10-14
deciders: product owner (Q1: GitHub Actions), agent
---

# 0011 — CI platform, runner model and pipeline stages

## Context and problem statement
Phase 0 Q1 chose GitHub Actions. TESTING_STRATEGY §9 defines four stages: pre-commit, PR (≤ 15 min), nightly (≤ 2 h) and release. The pilot bank may later require self-hosted CI.

## Decision outcome

**Platform and runners**
- GitHub Actions on GitHub-hosted `ubuntu-24.04` runners.
- Every job calls repository scripts (`ci/*.sh`, `ci/*.mjs`) or Gradle/pnpm tasks, so moving to self-hosted runners or GitLab CI only means rewriting YAML orchestration.

**Workflows**

| Workflow | Trigger | What it runs |
|---|---|---|
| `ci.yml` | PR + push to `master` | Parallel jobs: `backend` (build, tests incl. Testcontainers and RLS suites, ktlint, detekt, SpotBugs, contract and jOOQ drift); `web` (lint, typecheck, unit tests, client and i18n drift, build); `e2e` (core stack + backend jar + Playwright smoke with axe); `security` (gitleaks, Semgrep, Trivy fs + config, SBOMs, licence check); `images` (build both images, Trivy image scan, no push); `docs` (ADR index check, actionlint) |
| `nightly.yml` | 02:00 UTC daily, and on demand | full cross-browser e2e, pseudo-locale, visual, Lighthouse, long property runs; placeholders for the jobs whose content arrives later (mutation, Schemathesis, upgrade tests, ZAP, Gatling) |
| `release.yml` | tag `v*` | build, SBOMs, licence report, images pushed to GHCR, cosign signature, SBOM and provenance attestations |

**Gates and permissions**
- **Pre-commit (local):** lefthook runs Prettier, ESLint and ktlint on staged files, plus gitleaks when installed.
- **Permissions:** `contents: read` by default; jobs widen permissions only where they need to.
- **Pinning:** actions are pinned by commit SHA and container images by digest.

### Consequences
- Good: portable, parallel, every gate is reproducible locally.
- Bad: GitHub-hosted runners download vulnerability databases from the internet. An air-gapped self-hosted runner needs a Trivy DB mirror (documented in `ci/README.md`).

### Confirmation
Green runs of all three workflows. `actionlint` runs in the PR stage.
