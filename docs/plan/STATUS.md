# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-11-14 (UTC+06:00) by Claude (agent)

## Current position
- **Phase 2 (workflow engine) closed on 2026-11-14** (owner instruction; `phase-2-workflow-engine/retrospective.md`, exit recorded in `overview.md §10`). All six epics merged and on dev: E01 first slice (D1), E02 ONLYOFFICE editing, E03 definition model and runtime, E04 assignees, queues and DoA, E05 timers and SLAs, E06 visual builder (PRs #34–#83). The eight Phase 2 journeys pass against dev; the load target is met (inbox p95 10 ms with 10,000 memos in review). Items that need the owner in person are carried (Waiting for human).
- **Phase 1a closed on 2026-11-09** (`phase-1a-minimum-core/retrospective.md`). **Phase 1b** is not started; it must finish before Phase 3 exits.
- **Dev environment:** https://dev.memo-revamp.naziharitsolution.com and the operator console https://admin.dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175. It deploys automatically after every green `ci` run on `master`. The owner's operator account is enrolled.
- **Phase 0:** done (`docs/plan/phase-0-foundations/`). **Release:** `v0.1.0` (2026-10-15). **Product name:** `memo` everywhere except the repository name (A1).
- **Branches:** `master` holds PRs #1–#83 (2026-11-13/14: #41–#81 Phase 2 E03–E06 merged in order, #82 load stage, #83 phase close).

## State (Phase 2, closed)
- [x] Overview approved (2026-11-09), Q1–Q5 answered, per-epic gate delegated (E01 screen flows reviewed by the owner before build)
- [x] E01–E06 planned, built, merged, deployed to dev and documented (each epic folder: plan, threat model, test plan, log); ADR-0034 to ADR-0042 accepted
- [x] Exit criteria met with evidence (`retrospective.md`): first slice, builder exit journey, version pinning, acting-for audit, load (`load` run 43482461508)
- [x] `ci`, `deploy-dev` and the nightly green on `master`; the eight Phase 2 journeys pass against dev
- [ ] Owner: the D1 and phase demos, the builder walkthrough, the manual keyboard and screen-reader pass (carried)

## State (Phase 1a, closed)
- [x] Overview approved (2026-10-18), Q1–Q8 answered, per-epic gate delegated
- [x] E01–E07 merged, deployed to dev and documented (each epic folder: plan, threat model, test plan, log)
- [x] Exit criteria met with evidence; phase-close review built the missing tests (`AuditActionCoverageTest`, matrix audit check, cross-tenant link test) and corrected stale ASVS rows
- [x] ADR-0017 to ADR-0033 accepted
- [x] Full backend suite and the E2E exit journey green in CI; nightly fixes (PR #30) on master
- [ ] Owner demo, moderated usability walkthrough, manual keyboard and screen-reader pass (carried)

## Next step
1. Owner: choose the next phase (Phase 1b must finish before Phase 3 exits).
2. Owner, in person when convenient: the D1 and phase demos on dev, the builder walkthrough and the manual accessibility pass (Waiting for human).
3. Before 2026-11-22: take the rebuilt distroless digest from Renovate and delete the zlib entries in `.trivyignore`.
4. Follow-ups (both retrospectives, "Carried"): `AuditPerformanceIT`, `NotificationPerformanceIT`, the full-signature ClamAV corpus nightly, sample bodies for the remaining mutating matrix cells, upstream clean-ups (`apk upgrade` lines, Spring Session licence exceptions, Guava pin), Spring Boot 4.2 GA; from Phase 2: the step ordinal after a recusal (retrospective), a sweep for timers dropped after their retry limit, the inbox sorted by due time, a warning level for validation, saved simulation scenarios, React Flow 13, the web suite's timeouts under load.

## Waiting for human
- **Phase 2 in person:** the D1 demo (`phase-2-workflow-engine/epic-01-first-slice/demo.md`) and the phase demo (`phase-2-workflow-engine/demo.md`), both on dev; the builder usability walkthrough; the manual keyboard and screen-reader pass (checklist in `epic-06-visual-builder/ux-review.md`). Then choose the next phase.
- **Carried from Phase 1a (in person, no rush):** the demo (`docs/plan/phase-1a-minimum-core/demo.md`), a moderated usability walkthrough (login + MFA, org editing, tenant administration) and a manual keyboard + screen-reader pass (NVDA or VoiceOver), including the E01 patterns gallery (`pnpm dev`, `/patterns`).
- **GitHub setup:** required reviewers on the `release` environment (may need a paid plan on a private repository; meanwhile only the owner can push `v*` tags) and branch protection on `master`.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0042 (`docs/adr/README.md`). Phase 2: ADR-0042 visual workflow builder, ADR-0041 timers, SLAs and business calendars, ADR-0040 assignees, queues, DoA and acting for, ADR-0038 workflow definitions, ADR-0039 CEL conditions, ADR-0037 ONLYOFFICE editing integration, ADR-0034 own workflow engine, ADR-0035 workflow data model and concurrency, ADR-0036 memo document binding and approval step-up. Phase 1a: ADR-0017 tenant context, ADR-0018 jobs, ADR-0019 audit store, ADR-0020 cache invalidation, ADR-0021 modal dialogs, ADR-0022 tenant resolution and accounts, ADR-0023 local authentication and sessions, ADR-0024 TOTP MFA and step-up, ADR-0025 rate limiting, ADR-0026 org model, ADR-0027 authorisation model, ADR-0028 envelope encryption, ADR-0029 upload pipeline and range downloads, ADR-0030 notifications and email delivery, ADR-0031 platform operator path, ADR-0032 terminology overrides, ADR-0033 audit viewer and export.

## Environment notes (dev machine)
- VS Code's Java extension builds the tree with its own Gradle and can hold the shared `build-logic/.gradle` lock, stalling CLI builds at "Calculating task graph". CLI builds use `MEMO_BUILD_ROOT=<repo>/.build-cli` (README); the git-ignored `.vscode/settings.json` sets the Java extension to LightWeight mode (owner agreed 2026-10-26; the owner does not code on this project).
- Demo users: `admin@`, `reviewer@` (second administrator, for maker-checker), `orgadmin@`, `branchadmin@` (North Region), `auditor@`, `user@`, and the approvers `head.credit@`, `director@` and `head.finance@demo.example`, plus 44 named staff without sign-in; password and TOTP secret (Base32) in the generated `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`). Locally the bare `localhost` is the demo organisation.
- The local Compose stack is now project `memo-local`; the old `memo-dev` local volumes were removed. The first `pnpm dev` re-creates them, and ClamAV re-downloads its signatures.

## Milestone tracker
| Milestone | Target | Status |
|---|---|---|
| D1 — Pilot demo: simple sequential memo approval | end of Phase 2 slice 1 | built and on dev; owner demo pending |
| M1 — Pilot memo go-live (incl. pen test) | end of Phase 3 | not started |
| M2 — Committee meetings live | end of Phase 4 | not started |
| M3 — Board-grade web | end of Phase 5 | not started |
| M4a — Mobile approver app | end of Phase 6a | not started |
| M4b — Mobile director app | end of Phase 6b | not started |
| M5 — Enterprise-ready | end of Phase 7 | not started |
