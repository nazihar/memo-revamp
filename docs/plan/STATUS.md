# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-10-29 (UTC+06:00) by Claude (agent)

## Current position
- **Phase:** 1a (overview approved 2026-10-18, gate delegation kept). Merged: **E02 platform kernel** (PR #18), **E01 app frame** (PR #19). **E03 identity, sessions and step-up** complete on `feat/1a-e03-identity` (PR #20): T1–T10 done; Caddy on-demand TLS for organisation subdomains and the authenticated ZAP scan moved to E07 (see the E03 log).
- **Dev environment live (2026-10-18):** https://dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175 (Ubuntu 24.04, 4 vCPU, 7.8 GB RAM, 100 GB disk). It deploys automatically after every green `ci` run on `master`.
- **Phase 0 epics:** E01–E05 done (`docs/plan/phase-0-foundations/`).
- **Branches:** `master` holds PRs #1–#19; E03 on `feat/1a-e03-identity`.
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
- [x] Wildcard DNS `*.dev.memo-revamp.naziharitsolution.com` → dev VM added by the owner (2026-10-18, Phase 1a Q2)
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
1. **Phase 1a:** merge E03 (PR #20) when CI is green, after re-running Ansible on the dev VM (new `.env` settings: tenancy base domain, Mailpit, `MEMO_DATA_KEY`, demo password and TOTP secret). Then plan **E04 organisation and authorisation**.
2. Check the ZAP and Gatling results against dev from the next nightly.
3. Remove the `apk upgrade` lines from `web/Dockerfile` once `nginx-unprivileged` ships the fixed Alpine packages.
4. Remove the Spring Session licence exceptions (`ci/license-exceptions.json`) once Boot manages Spring Session 4.1.2 or later.

## Waiting for human
- **E01 manual accessibility pass (no rush):** a short keyboard + screen-reader (NVDA or VoiceOver) walk through the patterns gallery, run locally with `pnpm dev` at `/patterns` (the gallery is left out of production images, so it is not on the dev server). Automated axe, keyboard and CSP checks pass.
1. **VM memory:** 7.8 GB is enough for dev. Running staging, ONLYOFFICE and ClamAV as well needs 16 GB.
2. **GitHub setup:**
   - required reviewers on the `release` environment (not set; may need a paid GitHub plan on a private repository. Meanwhile only the owner can push `v*` tags);
   - branch protection on `master`.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0025 (`docs/adr/README.md`). Phase 1a so far: ADR-0017 tenant context, ADR-0018 jobs, ADR-0019 audit store, ADR-0020 cache invalidation, ADR-0021 modal dialogs, ADR-0022 tenant resolution and accounts, ADR-0023 local authentication and sessions, ADR-0024 TOTP MFA and step-up, ADR-0025 rate limiting.

## Environment notes (dev machine)
- VS Code's Java extension builds the tree with its own Gradle and can hold the shared `build-logic/.gradle` lock, stalling CLI builds at "Calculating task graph". CLI builds use `MEMO_BUILD_ROOT=<repo>/.build-cli` (README); the git-ignored `.vscode/settings.json` sets the Java extension to LightWeight mode (owner agreed 2026-10-26; the owner does not code on this project).
- Demo users: `admin@demo.example` and `user@demo.example`; password and TOTP secret (Base32) in the generated `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`). Locally the bare `localhost` is the demo organisation.
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
