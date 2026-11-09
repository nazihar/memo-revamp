# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-11-09 (UTC+06:00) by Claude (agent)

## Current position
- **Phase 1a closed on 2026-11-09** (owner instruction; `phase-1a-minimum-core/retrospective.md`, exit recorded in `overview.md §10`). All seven epics merged and on dev: E01 app frame, E02 platform kernel, E03 identity, sessions and step-up, E04 org directory and authorisation, E05 files, E06 notifications, E07 tenant lifecycle and administration (PRs #18–#30). Three exit items need the owner in person and are carried (Waiting for human).
- **Next phase not started:** Phase 1b (enterprise identity and organisation) and Phase 2 (workflow engine) may run in parallel (MASTER_PLAN); the owner chooses where to start, then the agent writes that phase's overview for approval.
- **Dev environment:** https://dev.memo-revamp.naziharitsolution.com and the operator console https://admin.dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175. It deploys automatically after every green `ci` run on `master`. The owner's operator account is enrolled.
- **Phase 0:** done (`docs/plan/phase-0-foundations/`). **Release:** `v0.1.0` (2026-10-15). **Product name:** `memo` everywhere except the repository name (A1).
- **Branches:** `master` holds PRs #1–#31; `fix/fuzz-blank-file-name` is PR #32.

## State (Phase 1a)
- [x] Overview approved (2026-10-18), Q1–Q8 answered, per-epic gate delegated
- [x] E01–E07 merged, deployed to dev and documented (each epic folder: plan, threat model, test plan, log)
- [x] Exit criteria met with evidence; phase-close review built the missing tests (`AuditActionCoverageTest`, matrix audit check, cross-tenant link test) and corrected stale ASVS rows
- [x] ADR-0017 to ADR-0033 accepted
- [x] Full backend suite and the E2E exit journey green in CI; nightly fixes (PR #30) on master
- [ ] Owner demo, moderated usability walkthrough, manual keyboard and screen-reader pass (carried)

## Next step
1. Owner: choose the next phase (1b enterprise identity, 2 workflow engine, or both in parallel). The agent then writes its overview for approval.
2. PR #32 (owner go-ahead to merge, CI green): the hand-started nightly confirmed the Helm fix, and Jazzer found a blank upload name from dots and spaces; fixed with a stored regression input. The next nightly should be fully green.
3. Before 2026-11-22: take the rebuilt distroless digest from Renovate and delete the zlib entries in `.trivyignore`.
4. Follow-ups (retrospective "Carried"): `AuditPerformanceIT`, `NotificationPerformanceIT`, the full-signature ClamAV corpus nightly, sample bodies for the remaining mutating matrix cells, upstream clean-ups (`apk upgrade` lines, Spring Session licence exceptions, Guava pin), Spring Boot 4.2 GA.

## Waiting for human
- **PR #32 merge:** fuzz finding (blank upload name), CI green.
- **Next phase:** 1b, 2, or both in parallel.
- **Carried from Phase 1a (in person, no rush):** the demo (`docs/plan/phase-1a-minimum-core/demo.md`), a moderated usability walkthrough (login + MFA, org editing, tenant administration) and a manual keyboard + screen-reader pass (NVDA or VoiceOver), including the E01 patterns gallery (`pnpm dev`, `/patterns`).
- **VM memory:** 7.8 GB is enough for dev with ClamAV (965 MiB after three days; 5.2 GB available). Staging and ONLYOFFICE as well need 16 GB.
- **GitHub setup:** required reviewers on the `release` environment (may need a paid plan on a private repository; meanwhile only the owner can push `v*` tags) and branch protection on `master`.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0033 (`docs/adr/README.md`). Phase 1a: ADR-0017 tenant context, ADR-0018 jobs, ADR-0019 audit store, ADR-0020 cache invalidation, ADR-0021 modal dialogs, ADR-0022 tenant resolution and accounts, ADR-0023 local authentication and sessions, ADR-0024 TOTP MFA and step-up, ADR-0025 rate limiting, ADR-0026 org model, ADR-0027 authorisation model, ADR-0028 envelope encryption, ADR-0029 upload pipeline and range downloads, ADR-0030 notifications and email delivery, ADR-0031 platform operator path, ADR-0032 terminology overrides, ADR-0033 audit viewer and export.

## Environment notes (dev machine)
- VS Code's Java extension builds the tree with its own Gradle and can hold the shared `build-logic/.gradle` lock, stalling CLI builds at "Calculating task graph". CLI builds use `MEMO_BUILD_ROOT=<repo>/.build-cli` (README); the git-ignored `.vscode/settings.json` sets the Java extension to LightWeight mode (owner agreed 2026-10-26; the owner does not code on this project).
- Demo users: `admin@`, `orgadmin@`, `branchadmin@` (North Region), `auditor@` and `user@demo.example`, plus 44 named staff without sign-in; password and TOTP secret (Base32) in the generated `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`). Locally the bare `localhost` is the demo organisation.
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
