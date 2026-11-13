# Phase 2 — log

History of the phase, newest last. `STATUS.md` keeps only the current position.

## 2026-11-09
- The owner chose Phase 2 as the next phase (Phase 1b not started; it must finish before Phase 3 exits).
- Overview written (`overview.md`): six epics, E01 first slice (D1) → E03 definition model → E04 assignees and DoA → E06 builder on the critical path; E02 ONLYOFFICE and E05 timers beside them. Q1–Q5 sent to the owner.
- Overview approved as recommended. Q1 delegation kept (E01 screen flows to the owner before build), D1 after E01; Q2 the owner upgrades the dev VM to 16 GB; Q3 workflow side of "acting for" now, checked in full with 1b; Q4 follow the master plan; Q5 step-up on approve on by default with a separate 30-minute approval window. The owner asked whether E02–E06 cover E01 (no: they build on it) and whether step-up burdens senior management (the window, batch approval, passkeys and mobile biometrics keep it light).
- The owner asked how far E01 drifted from industry leaders. Answer: E01 is the master plan's first slice by design; the leader features are mapped to E03–E06. A first-pass comparison found four gaps not planned anywhere. The owner added three of them plus two smaller items: self-service out-of-office substitution (Phase 1b screen, E04 workflow side), separation-of-duties and conflict-of-interest rules (E04), "refer for opinion" (E03), phone-friendly approval pages (E01), and a sourced benchmark (R11, E03). Bulk approval from the inbox: not wanted. MASTER_PLAN Phase 1b and Phase 2 scope updated to match.

## 2026-11-13
- **E05 complete** (timers, SLAs, escalation, business calendars; PRs #63–#71).
- **E06 planned and built** under the delegated gate (visual builder; PRs #72–#81).
- **Phase 2 is built.** The exit status is in `retrospective.md`, and the phase demo in `demo.md`.
- **Open for the owner:** merging #41 → #81 in order, the demos, the builder walkthrough, and the manual keyboard and screen-reader pass.
- **Open after merge:** the first weekly load report.
- **Merged (owner's instruction, 2026-11-13):** #41 → #81, one at a time in order, each retargeted to `master` and merged with a merge commit. `ci` on `master` (447ab7d) was green on every job and `deploy-dev` succeeded.
- **Checked on dev:** the eight Phase 2 journeys (`memo`, `workflow`, `authority`, `builder`) ran against dev over https.
  - The first run failed the authority journey. Dev's organisation was seeded in Phase 1a, before E04 T10 gave the demo approvers their seats. The seed only fills empty seats, so named staff still held three of them: Managing Director (Nusrat Jahan), Head of Credit (Imran Hossain) and Chief Financial Officer (Rumana Chowdhury).
  - Fixed through the org API as `admin@`, audited: each staff assignment was cancelled as recorded in error, and `director@`, `head.credit@` and `head.finance@` were assigned from the seed's start date. Dev now matches a fresh seed. A fresh seed (CI, local) was never affected.
  - The rerun passed all eight, the builder exit journey among them.
  - Two "E2E authority" test memos from the failed runs still wait on Nusrat Jahan on dev. They are test data only.
- **Load stage, first runs (2026-11-13/14):** the weekly stage had never run (scheduled workflows run only on `master`), and run by hand it failed four times before measuring anything. Each failure was in the stage, not the inbox:
  1. The volume generator created its tenant with no actor bound, and tenant creation is audited, so the backend failed at start-up. `WorkflowVolumeIT` missed it, because the test extension binds an actor; the test now runs the start-up runner on its own thread.
  2. The CI on-prem stack had no malware scanner, and without one every upload is refused (ADR-0029). The stack now runs the `antivirus` profile for the load stage. A failed start had also left a half-built tenant that the restarted backend skipped silently; it now says so, and the stage stops at once.
  3. Fifty initiators reached the upload limit (30 a minute each); the generator now waits as a client would after a 429.
  4. The simulation addressed the wrong tenant host. Over plain HTTP to 127.0.0.1, Gatling dropped the Secure cookies, so the scenario carries them itself. The CSRF endpoint answers 204. Fifty sign-ins in 30 seconds exceeded the 20 a minute per address, so the ramp now takes 165 seconds.
- **Load measured:** 10,000 memos in review with their timers, generated in about six minutes. 50 approvers, 18,100 inbox reads, each checked for a full page of 25 due decisions. p95 10 ms against the 300 ms budget, no errors (run 43482461508). The fixes are PR #82; the weekly run on `master` starts once it is merged.
