# E01 Forum types and forums — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Forum types:** `forum/V1`; `forum.types.manage` and `forum.types.approve` (role template, privileged); the version model with typed requests and Kotlin rules (attributes, outcomes, term policy); the quorum rule model and the pure `Quorum` evaluator (ADR-0057) with property tests; presets; the lifecycle on `common.review` (create, save with If-Match, submit, approve with step-up, send back, new draft, changes, activate and deactivate); audit; matrix `p4-e01-forums.yaml` | L | — |
| T2 | **Forums and membership:** `forum/V2`; `forums.manage` and `forum.read`; forums (create, edit, dissolve, clone); members (add a person or position, edit, end, renew, change role, withdraw) with the invariants under the forum lock; `ForumDirectory` with the roster cache and `ForumVisible`; `/me/forums`; the quorum preview endpoint; membership notifications; `OrgChanged` eviction; audit; cross-tenant tests | L | T1 |
| T3 | **Term reminders and the workflow link:** the `forum-term-reminders` daily task with its idempotency table and notification kind; the workflow `EscalationTargets` SPI, `UNKNOWN_FORUM` at submit and publish, `GET /admin/workflow/escalation-targets`; the builder's forum picker (web, small) | M | T2 |
| T4 | **Web, committee types** (after the owner's screen review, flow J): the terminology term `forum`; the catalogue part `forumAdmin`; types list; type page (Policy, Quorum with its sentence, example and tester, Outcomes, Terms, Changes; versions; review with step-up); create from a preset; en/ar | L | T1 |
| T5 | **Web, committees** (after the owner's screen review, flows K and L): committees list; committee page (summary with the type's policy read-only, Members on a date, History); add-member side sheet (person or position, role, voting, attributes, alternate, term, authority); renew, change role, end, withdraw; clone; dissolve; "My committees"; en/ar | L | T2, T4 |
| T6 | **Close:** demo seed (three types, six forums, `secretary@demo.example`, a credit workflow with an escalate-to-forum step for amounts above the MCC limit, still pass-through until E02); E2E journeys `committees.spec`; `EXPLAIN` at 50 forums × 300 rows; ASVS rows; runbook `committees.md`; dependency register unchanged; STATUS and log | M | all |

**Order:**
- T1 → T2 → T3 for the backend.
- T4 and T5 wait for the owner's screen review (`ux-review.md`) and their backend tasks.
- T6 last.
- Each task is its own PR, merged when CI is green (standing go-ahead, 2026-11-17).

## Acceptance criteria (selection; the full list is in `test-plan.md`)
- **T1:**
  - Given `admin@` created "Board committee" from the preset and submitted v1, when `admin@` approves, the answer is 409 `SAME_PERSON`.
  - When `reviewer@` approves with a fresh MFA, v1 is published and audited.
  - A draft whose quorum composition names `independent` while the type has no such attribute is refused with 422 at `quorum.composition[0].attribute`.
- **T1 (quorum):** given the rule "two-thirds of voting members, including the chair" and 7 voting members in office, the needed number is 5. With 5 present but no chair, the meeting is not quorate, and the unmet condition is the chair.
- **T2:**
  - Given the Board Risk Committee with a chair, when a second chair is added from a date that overlaps, the answer is 409 `ONE_CHAIR` and nothing changes.
  - Given `secretary@` is secretary of the BRC, they can add a member but get 403 `SECRETARY_LIMIT` when adding a secretary or changing their own row.
- **T2 (terms):** given a member whose `termEndsOn` was yesterday and who was not renewed, `membersOn(today)` leaves them out, and the roster shows "term ended".
- **T2 (visibility):** given `user@` is not a member, `GET /forums/{brc}` answers 404. `auditor@` with `forum.read` but a clearance below the forum's classification also gets 404.
- **T3:**
  - Given a term ends in 42 days, the daily task notifies the BRC's secretaries once. Running it again the same day sends nothing.
  - A workflow definition naming `board-risk-x` is refused at publish with `UNKNOWN_FORUM` at its step.
- **T5:** with the keyboard only, a secretary adds a member with an alternate and a term, then renews them. axe finds no serious issue in English and Arabic.
