# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-11-11 (UTC+06:00) by Claude (agent)

## Current position
- **Phase 1a closed on 2026-11-09** (owner instruction; `phase-1a-minimum-core/retrospective.md`, exit recorded in `overview.md §10`). All seven epics merged and on dev: E01 app frame, E02 platform kernel, E03 identity, sessions and step-up, E04 org directory and authorisation, E05 files, E06 notifications, E07 tenant lifecycle and administration (PRs #18–#30). Three exit items need the owner in person and are carried (Waiting for human).
- **Phase 2 (workflow engine) started on 2026-11-09** at the owner's choice. The overview is approved (2026-11-09, Q1–Q5 answered, per-epic gate delegation kept): `phase-2-workflow-engine/overview.md` (six epics: E01 first slice / D1, E02 ONLYOFFICE editing, E03 definition model and runtime, E04 assignees and DoA, E05 timers and SLAs, E06 visual builder). Phase 1b is not started; it must finish before Phase 3 exits.
- **Dev environment:** https://dev.memo-revamp.naziharitsolution.com and the operator console https://admin.dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175. It deploys automatically after every green `ci` run on `master`. The owner's operator account is enrolled.
- **Phase 0:** done (`docs/plan/phase-0-foundations/`). **Release:** `v0.1.0` (2026-10-15). **Product name:** `memo` everywhere except the repository name (A1).
- **Branches:** `master` holds PRs #1–#39 (2026-11-11: #34 Phase 2 E01, #35 memo visual baselines, #36 web image CVE fix, #37 E02 plan, #38 E02 implementation, #39 forwarded scheme for the editor).

## State (Phase 2)
- [x] Overview written (2026-11-09)
- [x] Overview approved (2026-11-09), Q1–Q5 answered, per-epic gate delegated (E01 screen flows reviewed by the owner before build)
- [x] E01 plan written (2026-11-09): research, ADR-0034 to ADR-0036, design, threat model, tasks, test plan, screen flows (`epic-01-first-slice/`)
- [x] E01 screen flows approved by the owner (2026-11-09, as planned)
- [x] E01 T1 engine core (pure transition function, property tests, PIT nightly at 90 %)
- [x] E01 T2 workflow tables and service
- [x] E01 T3 memo types and drafts
- [x] E01 T4 route and submit
- [x] E01 T5 approver actions, rounds and approval step-up
- [x] E01 T6 reassignment and blocked tasks
- [x] E01 T7 inbox, boxes and history
- [x] E01 T8 workflow notifications
- [x] E01 T9 memo screens (desktop and phone layouts)
- [x] E01 T10 demo memos, matrix, first-slice E2E journey, D1 script (`epic-01-first-slice/demo.md`)
- [x] E01 T11 close (PR #34 merged 2026-11-11; visual baselines in the follow-up PR; the owner's D1 demo is in Waiting for human)
- [x] E02 plan written and merged (2026-11-11): research, ADR-0037, design, threat model, tasks, test plan (`epic-02-onlyoffice-editing/`, PR #37)
- [x] E02 T1–T3 service, network, `docedit` module, download and callbacks (forced RLS, HS256, internal connector, saves through the files pipeline)
- [x] E02 T4 web editor page and buttons (read-only on phones), en/ar
- [x] E02 T5 `DocEditIT`, matrix `p2-e02-docedit.yaml`, E2E edit journey green against a real ONLYOFFICE locally and on dev in Chromium, Firefox and WebKit; nightly on the E2E stack with ONLYOFFICE
- [x] E02 T6 runbook, ASVS rows, licence register, nfr memory figures, epic log
- [x] E02 merged and on dev (#38, #39; Ansible 2026-11-11 turned on the `documents` profile; editing verified on dev)
- [x] E03 plan written (2026-11-11): research, ADR-0038 (definitions), ADR-0039 (CEL conditions), design, threat model, tasks T1–T11, test plan (`epic-03-definition-runtime/`)
- [x] E03 T1 definition model · T2 conditions (PR stacked on #41)
- [x] E03 T3 runtime (stacked PR)
- [x] E03 T4 parallel (stacked PR)
- [x] E03 T5 task actions (stacked PR)
- [x] E03 T6 round control (stacked PR)
- [x] E03 T7 definition lifecycle (stacked PR)
- [x] E03 T8 in-flight migration (stacked PR)
- [x] E03 T9 events and history (stacked PR)
- [x] E03 T10 web (stacked PR)
- [x] E03 T11 close: demo "Credit facility" workflow and FIXED memo type, E2E journey (opinion, recommend, send back, approve) green locally, ASVS rows, runbook `workflow-definitions.md`, epic log
- [ ] E03 merged (stacked PRs #41–#51, in order) and on dev
- [x] E04 plan written (2026-11-12): research (R1, R5, R6, R12), ADR-0040, design, threat model, tasks T1–T10, test plan (`epic-04-assignees-doa/`)
- [x] E04 T1 read facades (stacked PR)
- [x] E04 T2 resolvers (stacked PR)
- [x] E04 T3 queues, claim and release (stacked PR)
- [x] E04 T4 separation of duties and interests (stacked PR)
- [x] E04 T5 substitution and staff changes (stacked PR)
- [x] E04 T6 DoA versions, rules, `doa` step and simulator (stacked PR)
- [ ] E04 T7 routes · T8–T9 web · T10 close

## State (Phase 1a, closed)
- [x] Overview approved (2026-10-18), Q1–Q8 answered, per-epic gate delegated
- [x] E01–E07 merged, deployed to dev and documented (each epic folder: plan, threat model, test plan, log)
- [x] Exit criteria met with evidence; phase-close review built the missing tests (`AuditActionCoverageTest`, matrix audit check, cross-tenant link test) and corrected stale ASVS rows
- [x] ADR-0017 to ADR-0033 accepted
- [x] Full backend suite and the E2E exit journey green in CI; nightly fixes (PR #30) on master
- [ ] Owner demo, moderated usability walkthrough, manual keyboard and screen-reader pass (carried)

## Next step
1. Owner: merge E03 in order (#41 → #51); agent then deploys and checks it on dev.
1. Agent: E04 implementation, slice by slice (T1 read facades first), stacked on the E03 PRs.
2. Owner: the D1 demo on dev (`phase-2-workflow-engine/epic-01-first-slice/demo.md`).
3. Before 2026-11-22: take the rebuilt distroless digest from Renovate and delete the zlib entries in `.trivyignore`.
4. Follow-ups (retrospective "Carried"): `AuditPerformanceIT`, `NotificationPerformanceIT`, the full-signature ClamAV corpus nightly, sample bodies for the remaining mutating matrix cells, upstream clean-ups (`apk upgrade` lines, Spring Session licence exceptions, Guava pin), Spring Boot 4.2 GA.

## Waiting for human
- **D1 pilot demo** (Phase 2 E01): on dev once the release with PR #34 is deployed, following `phase-2-workflow-engine/epic-01-first-slice/demo.md`.
- **Carried from Phase 1a (in person, no rush):** the demo (`docs/plan/phase-1a-minimum-core/demo.md`), a moderated usability walkthrough (login + MFA, org editing, tenant administration) and a manual keyboard + screen-reader pass (NVDA or VoiceOver), including the E01 patterns gallery (`pnpm dev`, `/patterns`).
- **GitHub setup:** required reviewers on the `release` environment (may need a paid plan on a private repository; meanwhile only the owner can push `v*` tags) and branch protection on `master`.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0039 (`docs/adr/README.md`). Phase 2: ADR-0038 workflow definitions, ADR-0039 CEL conditions, ADR-0037 ONLYOFFICE editing integration, ADR-0034 own workflow engine, ADR-0035 workflow data model and concurrency, ADR-0036 memo document binding and approval step-up. Phase 1a: ADR-0017 tenant context, ADR-0018 jobs, ADR-0019 audit store, ADR-0020 cache invalidation, ADR-0021 modal dialogs, ADR-0022 tenant resolution and accounts, ADR-0023 local authentication and sessions, ADR-0024 TOTP MFA and step-up, ADR-0025 rate limiting, ADR-0026 org model, ADR-0027 authorisation model, ADR-0028 envelope encryption, ADR-0029 upload pipeline and range downloads, ADR-0030 notifications and email delivery, ADR-0031 platform operator path, ADR-0032 terminology overrides, ADR-0033 audit viewer and export.

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
