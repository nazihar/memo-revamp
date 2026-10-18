# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-10-18 (UTC+06:00) by Claude (agent)

## Current position
- **Phase:** 1a. Overview **approved 2026-10-18** with Q1–Q8 answered (`phase-1a-minimum-core/overview.md §10`); per-epic gate delegation kept. Next: plan E01 and E02. Phase 0 exited 2026-10-15.
- **Carried into Phase 1a:** app layout and visual design, now epic E01 of the overview.
- **Dev environment live (2026-10-18):** https://dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175 (Ubuntu 24.04, 4 vCPU, 7.8 GB RAM, 100 GB disk). It deploys automatically after every green `ci` run on `master`.
- **Epics:** E01–E05 implemented. Plans, logs and test plans are in `docs/plan/phase-0-foundations/epic-0*/`.
- **Branches:** everything is merged into `master` (PRs #1–#15); GitHub CI green; feature branches deleted.
- **Release:** `v0.1.0` published with signed images (2026-10-15).
- **Product name:** `memo` everywhere except the repository name `memo-revamp` (A1, PR #6).

## State
- [x] Phase overview approved; Q1, Q2, Q4, Q5 and Q7 answered; per-epic gate delegated
- [x] E01 Walking skeleton
- [x] E02 Data and tenancy (RLS meta-test, cross-tenant suite, negative test)
- [x] E03 Web foundation (shell, i18n/RTL, Playwright + axe, CSP)
- [x] E04 CI and supply chain (all gates run locally; workflows actionlint-clean)
- [x] E05 Environments and deploy (on-prem profile, Helm, Ansible, deploy with rollback, ZAP, Gatling)
- [x] GitHub CI green on PRs and `master`
- [x] Product renamed to `memo` (package `com.memo`, images `memo-backend` / `memo-web`)
- [x] Release signing key (2026-10-15): `release` environment with `COSIGN_PRIVATE_KEY` / `COSIGN_PASSWORD`; `deploy/cosign.pub` committed
- [x] First release run: `v0.1.0` signed and published (2026-10-15, after the cosign 3 fixes in PRs #9 and #10)
- [x] First nightly run (2026-10-16) failed only on the lockfile re-scan: HIGH Jackson CVEs in `build-logic/gradle.lockfile` (build tooling, not shipped). Fixed in PR #11; the manual re-run passed on 2026-10-17
- [x] Renovate GitHub App installed by the owner (2026-10-17, ADR-0014)
- [x] Dev environment deployed (2026-10-18):
  - VM provisioned with Ansible (playbook fixes in PR #13);
  - private images pulled with the deploy job's short-lived token, no credential stored on the VM (PR #14);
  - first deploy succeeded after the libexpat image fixes (PR #15).
- [ ] ZAP baseline and Gatling against dev: they run in the next nightly now that `DEV_URL` is set
- [x] Owner usability walkthrough (2026-10-15): UI OK; "layout very simple" carried into Phase 1a as a layout/visual-design epic
- [x] NFRs confirmed as proposed by the owner (2026-10-15); Q3 answered: agent only, no deadline
- [x] `retrospective.md` finalised and phase exit recorded in `overview.md §10` (2026-10-15)

## Verified locally (2026-10-14/15)
- **Backend:** 79 tests green (unit, property, Modulith, ArchUnit, RLS meta, cross-tenant, negative, API, contract and jOOQ drift, probes, migrate mode, structured logs); ktlint, detekt, SpotBugs + FindSecBugs clean.
- **Web:** lint, typecheck and format clean; Vitest 12/12; Playwright smoke 6/6 (dev stack and production images); pseudo 1/1; visual 4/4 (Linux baselines).
- **Supply chain:** SBOMs and licence gate pass; Trivy shows 0 fixable HIGH/CRITICAL in the SBOMs and images; gitleaks clean; 6 Semgrep rules tested.
- **Deployment:** image and on-prem smoke tests pass; `deploy.sh` rollback verified; Helm lint + kubeconform clean; ansible-lint clean (production profile).
- **DAST/performance:** ZAP baseline 0 FAIL / 0 WARN; Gatling p95 48 ms.

## Next step
1. **Phase 1a:** plan E01 (app frame and visual design) and E02 (platform kernel) in parallel. E02 is backend-only and fills the waits for the owner review of the E01 wireframes.
2. Check the ZAP and Gatling results against dev from the next nightly.
3. Remove the `apk upgrade libexpat` step from `web/Dockerfile` once `nginx-unprivileged` ships libexpat 2.9.0 or later.

## Waiting for human
1. **VM memory:** 7.8 GB is enough for dev. Running staging, ONLYOFFICE and ClamAV as well needs 16 GB.
2. **GitHub setup:**
   - required reviewers on the `release` environment (not set; may need a paid GitHub plan on a private repository. Meanwhile only the owner can push `v*` tags);
   - branch protection on `master`.
3. **Wildcard DNS for dev (Q2):** a `*.dev.memo-revamp.naziharitsolution.com` record pointing at the dev VM, needed before E03 runs per-tenant hosts on dev.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0016 (`docs/adr/README.md`).

## Environment notes (dev machine)
- VS Code's Java extension builds the tree with its own Gradle 9.7.1. CLI builds use `MEMO_BUILD_ROOT=<repo>/.build-cli` (README).
- The local Compose stack is now project `memo-local`; the old `memo-dev` local volumes were removed. The first `pnpm dev` re-creates them, and ClamAV re-downloads its signatures.

## Milestone tracker
| Milestone | Target | Status |
|---|---|---|
| D1 — Pilot demo: simple sequential memo approval | end of Phase 2 slice 1 | not started |
| M1 — Pilot memo go-live (incl. pen test) | end of Phase 3 | not started |
| M2 — Committee meetings live | end of Phase 4 | not started |
| M3 — Board-grade web | end of Phase 5 | not started |
| M4a — Mobile approver app | end of Phase 6a | not started |
| M4b — Mobile director app | end of Phase 6b | not started |
| M5 — Enterprise-ready | end of Phase 7 | not started |
