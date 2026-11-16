# STATUS

> Read at the start of every session; update at the end (see `AGENTS.md §0`). Keep this page short and current. Move history into the phase folder's `log.md`.

**Last updated:** 2026-11-15 (UTC+06:00) by Claude (agent)

## Current position
- **Phase 3 (memo and e-file management, milestone M1) started on 2026-11-14.** Overview approved the same day (`phase-3-memo-efile/overview.md §10`): no pilot bank (general features; representative configuration in the demo tenant), English only, order E01–E06 → Phase 1b → E07, per-epic gate delegation kept. **E01 (memo types, metadata and numbering) is built** (PRs #86–#93): type versions, fields and metadata, attributes, gapless numbering, closing and links, the admin screens, the create form and memo view, the journeys and the DoA amount bands in the demo. **E02 (documents) is built** (PRs #94–#102): ONLYOFFICE 10.0.0.1 with hardening (T1), PDF and text renditions as background jobs (T2), Apache Tika 4.2.0 (T3), attachments with a stricter-only classification (T4), co-authors and shared editing rooms (T5), version comparison (T6), the web screens (T7) and the close-out (T8: journey, demo seed, performance, runbooks, ASVS). **E03 (templates, note sheet and access) is planned** (#103): research R4 and R9, ADR-0048 to ADR-0051, design, threat model, tasks T1–T9, test plan. The owner approved flows C and D and the six answers as recommended (2026-11-15). **E03 is built** (PRs #104–#112): the template engine and templates on memo types, the access model with shares, copy-to with receipts and distribution lists, the note sheet with authenticated approvals, the web screens, and the close-out (journeys, demo seed, performance, runbook, ASVS). **E04 (final rendition, PDF/A) is planned** (#113): research R1, R3 and R8, ADR-0052 and ADR-0053, design, threat model, tasks T1–T7, test plan; no screen-flow review is needed. **E04 is built** (PRs #113–#120): the PDF/A conversion and veraPDF validator (T1), generated pages on PDFBox with Noto Sans (T2), the assembler (T3), the final record job and API (T4), regeneration, retry, the lock at closure and fixity (T5), the Final record box (T6) and the close-out (T7: journey, demo keeper role, performance, runbook, ASVS). **E05 (inbox, search and dashboards) is planned** (#121): research R7 and R10, ADR-0054, design, threat model, tasks T1–T8, test plan; the owner approved flows H and I and the six answers as recommended (2026-11-15); **E05 is being built:** T1 (search tables and functions, V1 spike: every reader profile within 300 ms at 1,000,000 memos except search-as-you-type for a unit head, 307 ms) is done (#122); T2 (search index, #123), T3 (search API and saved searches, #124), T4 (following, due sort, bulk, #125) T5 (team view and dashboards backend, #126), T6 (search web, #127) and T7 (inbox and dashboards web) are done.
- **Phase 2 (workflow engine) closed on 2026-11-14** (owner instruction; `phase-2-workflow-engine/retrospective.md`, exit recorded in `overview.md §10`). All six epics merged and on dev: E01 first slice (D1), E02 ONLYOFFICE editing, E03 definition model and runtime, E04 assignees, queues and DoA, E05 timers and SLAs, E06 visual builder (PRs #34–#83). The eight Phase 2 journeys pass against dev; the load target is met (inbox p95 10 ms with 10,000 memos in review). Items that need the owner in person are carried (Waiting for human).
- **Phase 1a closed on 2026-11-09** (`phase-1a-minimum-core/retrospective.md`). **Phase 1b** is not started; it runs after Phase 3 E06 and before E07 (Phase 3 Q4).
- **Dev environment:** https://dev.memo-revamp.naziharitsolution.com and the operator console https://admin.dev.memo-revamp.naziharitsolution.com on the VM 123.176.58.175. It deploys automatically after every green `ci` run on `master`. The owner's operator account is enrolled.
- **Phase 0:** done (`docs/plan/phase-0-foundations/`). **Release:** `v0.1.0` (2026-10-15). **Product name:** `memo` everywhere except the repository name (A1).
- **Branches:** `master` holds PRs #1–#83. Open and stacked, to merge in order (#104 E03 T1, #105 T2, #106 T4, #107 T5, #108 T3, #109 T6, #110 T7, #111 T8, #112 T9, #113 E04 plan, #114 T1, #115 T2, #116 T3, #117 T4, #118 T5, #119 T6, #120 T7 and #121 E05 plan, #122 E05 T1, #123 E05 T2, #124 E05 T3, #125 E05 T4, #126 E05 T5, #127 E05 T6 and #128 E05 T7 follow #103): #84 Phase 3 overview → #85 E01 plan → #86 T1 type versions → #87 T2 fields and metadata → #88 T3 attributes → #89 T4 numbering → #90 T5 lifecycle → #91 T8 demo types → #92 T6 admin screens and T7 create form and memo view → #93 T8 close → #94 E02 plan → #95 T1 ONLYOFFICE 10 → #96 T2 renditions → #97 T3 Tika → #98 T4 attachments → #99 T5 co-authors and rooms → #100 T6 comparison → #101 T7 web → #102 T8 close → #103 E03 plan.

## State (Phase 3)
- [x] Overview written (2026-11-14): seven epics (E01 memo types, metadata and numbering; E02 documents; E03 templates, note sheet and access; E04 PDF/A rendition; E05 inbox, search and dashboards; E06 legacy import; E07 go-live readiness and the M1 security gate)
- [x] Overview approved (2026-11-14), Q1–Q4 answered; Q5 (pen tester, production environment, licence review) open until E07
- [x] E01 plan written (2026-11-14): ADR-0043 memo-type versions and metadata, ADR-0044 numbering; tasks T1–T8 (`epic-01-types-metadata-numbering/`)
- [x] E01 backend: T1 type versions with maker-checker (#86), T2 fields, metadata and pinning (#87), T3 fields as workflow attributes (#88), T4 gapless numbering (#89), T5 closing and links (#90); T8 representative demo types (#91). Full backend suite green locally on each; details in the epic log
- [x] Owner reviewed the E01 screen flows (2026-11-14, accepted as drafted; `ux-review.md`)
- [x] E01 web: T6 memo-type admin and numbering screens, T7 type chooser, create and edit form with details, memo view details, links and close (#92); E2E journey `memo-types.spec`
- [x] E01 T8: the authority journey with amount bands, `EXPLAIN` at 100,000 numbers, ASVS rows, runbook, `workflow/V15` for the rows `workflow/V3` missed on dev (#93)
- [x] E02 plan written (2026-11-14): research, ADR-0045 to ADR-0047, design, threat model, tasks T1–T8, test plan; licence brief for the owner's lawyer (`docs/legal/onlyoffice-licence-brief.md`)
- [x] E02 built: T1 ONLYOFFICE 10.0.0.1 and hardening (#95), T2 renditions (#96), T3 Tika (#97), T4 attachments (#98), T5 co-authors and edit rooms (#99), T6 comparison (#100), T7 web (#101), T8 close (#102): journey `attachments.spec`, demo clearance and quotation, performance (attachment list 23–27 ms, comparison 229 ms), runbook `attachments-and-previews.md`, ASVS rows
- [x] E03 plan written (2026-11-15): research R4 and R9 with industry practice, ADR-0048 to ADR-0051 (proposed), design, threat model, tasks T1–T9, test plan, screen flows (#103)
- [x] Owner approved E03 flows C and D and the six answers as recommended (2026-11-15); ADR-0049 to ADR-0051 accepted
- [x] E04 plan written (2026-11-15): research R1, R3 and R8 with records practice, ADR-0052 and ADR-0053 (accepted under the delegated gate), design, threat model, tasks T1–T7, test plan; decisions taken under the delegation listed in `ux-review.md` (#113)
- [x] E05 plan written (2026-11-15): research R7 and R10, ADR-0054, design, threat model, tasks T1–T8, test plan (#121)
- [x] Owner approved E05 flows H (search) and I (team view) and the six answers as recommended (2026-11-15)
- [ ] E05 built: T1 V1 and search spike (#122; ADR-0054 amended: no `pg_trgm`, planned per call, one table per part); T2 search index (#123: indexed in the change's transaction, chunk job, backfill; chunks of 2,000 words); T3 search API and saved searches (#124); T4 watching, due sort, bulk (#125); T5 team view and dashboards (#126); T6 search web (#127); T7 inbox and dashboards web (done); T8 close
- [x] E04 built: T1 spike, PDF/A conversion and validator (#114); T2 generated pages (#115); T3 assembler (#116); T4 job and API (#117); T5 regeneration, retry, retention and fixity (#118); T6 web (#119); T7 close (#120)
- [x] E03 built: T1 template engine (poi-tl 1.12.2 on POI 6.0.0 after the spike; scanner, checks, fill, `memo.template` purpose; #104); T2 templates on memo types, preview, maker-checker rules and create from template (#105); T4 access model and shares (#106); T5 copy-to, receipts and distribution lists (#107); T3 note sheet backend (#108); T6 authenticated approvals and the recusal follow-up (#109); T7 note sheet web (#110); T8 access, copy-to, distribution lists and templates web (#111); T9 close: journeys, demo seed, performance, runbook and ASVS (#112)
- [ ] E02 carried: `OnlyOfficeConversionIT` and `TikaExtractionIT` against the stack, the hostile corpus through both services (nightly), the two conversion timings on dev, editing attachments in the browser (gap against ADR-0047)
- [ ] E01/E02 close-out on dev, after #84–#102 are merged and deployed: re-run Ansible so `MEMO_TIKA_URL` is set; check on dev that no pre-E03 round or step is left at the defaults and that the demo DoA has the credit bands

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
1. Agent: build E05 T1–T8 (stacked PRs). Owner: merge #84–#121 in order when convenient (CI on each); after E03 is on dev, the demo seed adds the unit reader, the Credit facility letterhead and the "Credit line renewal for Meghna Agro" memo.
2. Owner, in person when convenient: the D1 and phase demos on dev, the builder walkthrough and the manual accessibility pass (Waiting for human).
3. Before 2026-11-22: take the rebuilt distroless digest from Renovate and delete the zlib entries in `.trivyignore`.
4. Follow-ups (both retrospectives, "Carried"): `AuditPerformanceIT`, `NotificationPerformanceIT`, the full-signature ClamAV corpus nightly, sample bodies for the remaining mutating matrix cells, upstream clean-ups (`apk upgrade` lines, Spring Session licence exceptions, Guava pin), Spring Boot 4.2 GA; from Phase 2: the step ordinal after a recusal (retrospective), a sweep for timers dropped after their retry limit, the inbox sorted by due time, a warning level for validation, saved simulation scenarios, React Flow 13, the web suite's timeouts under load.

## Waiting for human
- **Phase 3:** Q5 before E07; the ONLYOFFICE licence brief (`docs/legal/onlyoffice-licence-brief.md`) is ready for the lawyer of Q5c (pen tester, production environment, ONLYOFFICE licence review).
- **Phase 2 in person:** the D1 demo (`phase-2-workflow-engine/epic-01-first-slice/demo.md`) and the phase demo (`phase-2-workflow-engine/demo.md`), both on dev; the builder usability walkthrough; the manual keyboard and screen-reader pass (checklist in `epic-06-visual-builder/ux-review.md`).
- **Carried from Phase 1a (in person, no rush):** the demo (`docs/plan/phase-1a-minimum-core/demo.md`), a moderated usability walkthrough (login + MFA, org editing, tenant administration) and a manual keyboard + screen-reader pass (NVDA or VoiceOver), including the E01 patterns gallery (`pnpm dev`, `/patterns`).
- **GitHub setup:** required reviewers on the `release` environment (may need a paid plan on a private repository; meanwhile only the owner can push `v*` tags) and branch protection on `master`.

## Assumptions
- **A1 (resolved):** the product is `memo`. Package `com.memo`, Gradle group `com.memo`, application name `memo`, images `memo-backend` / `memo-web`. Only the repository keeps the name `memo-revamp`. The stored schema marker moved to `memo` with migration `system/V2`.

## Decisions taken
ADR-0001 to ADR-0051 (`docs/adr/README.md`). Phase 3: ADR-0045 renditions and text, ADR-0046 memo attachments, ADR-0047 ONLYOFFICE 10, co-editing and hardening, ADR-0043 memo-type versions, metadata and attributes, ADR-0044 memo numbering. Phase 2: ADR-0042 visual workflow builder, ADR-0041 timers, SLAs and business calendars, ADR-0040 assignees, queues, DoA and acting for, ADR-0038 workflow definitions, ADR-0039 CEL conditions, ADR-0037 ONLYOFFICE editing integration, ADR-0034 own workflow engine, ADR-0035 workflow data model and concurrency, ADR-0036 memo document binding and approval step-up. Phase 1a: ADR-0017 tenant context, ADR-0018 jobs, ADR-0019 audit store, ADR-0020 cache invalidation, ADR-0021 modal dialogs, ADR-0022 tenant resolution and accounts, ADR-0023 local authentication and sessions, ADR-0024 TOTP MFA and step-up, ADR-0025 rate limiting, ADR-0026 org model, ADR-0027 authorisation model, ADR-0028 envelope encryption, ADR-0029 upload pipeline and range downloads, ADR-0030 notifications and email delivery, ADR-0031 platform operator path, ADR-0032 terminology overrides, ADR-0033 audit viewer and export.

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
