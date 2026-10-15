# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-10-15 (UTC+06:00) by Claude (agent)

## Current position
- **Phase:** 0 **exited 2026-10-15** (owner approval; `phase-0-foundations/retrospective.md`). **Next: Phase 1a planning**, starting with the overview.
- **Carried into Phase 1a:** first dev deploy (waiting for the VM); app layout and visual design epic (from the UX walkthrough).
- **Epics:** E01–E05 implemented. Plans, logs and test plans are in `docs/plan/phase-0-foundations/epic-0*/`.
- **Branches:** everything is merged into `master` (PRs #1–#6); GitHub CI green; feature branches deleted.
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
- [ ] First nightly run (scheduled 02:00 UTC) and first release run (**needs the cosign key**)
- [ ] Dev environment deployed: **needs the VM** (carried into Phase 1a)
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
1. **Phase 1a planning:** write `docs/plan/phase-1a-minimum-core/overview.md` (AGENTS.md planning protocol). Its first epic is the app layout and visual design.
2. Watch the first nightly run; cut the first release once the cosign key exists.
3. When the VM exists: provision it with Ansible, enable `deploy-dev`, run ZAP and Gatling against dev.

## Waiting for human
1. **VM for dev and staging:**
   - host/IP and OS (Ubuntu LTS expected);
   - an admin SSH account for Ansible;
   - DNS names for dev and staging;
   - TLS choice: ACME/Let's Encrypt or your own certificates.
2. **GitHub setup:**
   - `dev` environment secrets: `DEPLOY_SSH_KEY`, `DEPLOY_HOST`, `DEPLOY_KNOWN_HOSTS`;
   - variables `DEPLOY_DEV_ENABLED=true` and `DEV_URL`;
   - `release` environment with reviewers, plus the cosign key pair (`COSIGN_PRIVATE_KEY`, `COSIGN_PASSWORD`; commit `deploy/cosign.pub`);
   - branch protection on `master`.
3. **Renovate:** install the GitHub App (ADR-0014).
4. **For Phase 1a design (no rush):** brand assets (logo, colours, fonts) and 2–3 reference apps you like.

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
