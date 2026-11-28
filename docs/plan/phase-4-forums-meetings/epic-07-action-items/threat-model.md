# E07 Action items — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows. ASVS 5.0 IDs are as in `docs/security/asvs-checklist.md`, chapter-level where the checklist groups them. Builds on the Phase 2 engine threat model (timers, escalation, separation of duties), the E03 and E04 threat models (the item rule, recusal) and E06's (decisions, minutes).

## Data flows
1. A secretary raises draft actions from a decision, a condition, an item, the meeting, or directly. They issue them: a reference is taken, a workflow instance starts, and the owner is told.
2. The owner, contributors or the secretary on the owner's behalf post updates, with evidence files through the upload pipeline. The owner asks for a new date, and the secretary or chair agrees; the engine's timers are re-planned.
3. The engine's timers remind the holder, mark the action overdue and escalate to the holder's supervisor. Emails are combined in a daily digest.
4. The owner marks the action done with a note and evidence. A verifier from the pool closes it or sends it back, singly or in a batch at the meeting.
5. The owner raises a follow-up memo from the action, or links one. The memo's decision is recorded on the action.
6. When the next meeting's agenda is published, the action taken report is frozen for that revision. Members read their rows on the "Matters arising" item, on screen or as a PDF.
7. Officers export the tracker as XLSX. Administrators edit the committee type's actions policy (maker-checker) and the `action-item` definition in the builder.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Someone closes their own action, or a friendly colleague closes it without checking | 4 | The verifier slot leaves out the owner, the holder and contributors. SOD-2 excludes whoever recommended in the round. The definition check refuses an action definition with no verification step. Closure records the verifier, and the report lists closures for the next meeting to note | V8 |
| S2 | A secretary records an update, or a "risk accepted" cancellation, that the owner or chair never gave | 2, 4 | "On behalf" needs `how` and is recorded with both names. The owner (for updates) or the chair (for risk accepted) is told at once. Both names show on the timeline and in the report | V8, V16 |
| S3 | A substitute or the new holder of a position acts on an action they should not see | 1, 3 | The engine's substitutes and holder changes are the only ways in (ADR-0040), and each is audited. A holder must be cleared for the action's level (`OWNER_NOT_CLEARED`, checked again on re-resolution through `ineligible`). A blocked task goes to the secretaries | V8 |
| T1 | A missed date is hidden by moving it or overriding the colour | 2 | The original date is immutable (trigger). Every change keeps the old and new dates and a reason, and the revision count is shown everywhere. Overrides carry a reason and the person's name and are shown with the colour. The report snapshots what the meeting saw. Everything is audited | V16 |
| T2 | An update or the timeline is edited after the fact | 2 | `action_entry` is insert-only for the app role. Corrections are new entries pointing at the old one | V16 |
| T3 | The action taken report is altered after publication, or rows are quietly left out | 6 | Report and row tables are insert-only. The content hash is audited at build. Held-back rows need a reason and are counted for officers | V16 |
| T4 | Two people verify, re-date or cancel the same action at once, or a double click issues twice | 1, 2, 4 | If-Match on `version`. `WorkflowSubject.lock` locks the action before the instance (lock order). Guarded status updates. Idempotency keys. The engine's own guards on tasks. Bulk endpoints run one transaction per action | V2 |
| T5 | A tenant administrator edits the `action-item` definition to drop verification, or routes closure to the owner | 7 | `checkDefinition` refuses definitions without a later human step, with REJECT or RETURN, or with FORWARD or DELEGATE on the owner's step. SOD-2 and the slot's exclusions still apply. Definition publishing is the builder's maker-checker (ADR-0042) | V8 |
| T6 | Injection through action text, updates or names into the XLSX export (formula injection) or the PDF report | 6, 7 | Cells are written as strings, and values starting with `=`, `+`, `-`, `@`, tab or CR are prefixed with `'` (OWASP CSV and formula injection). PDF text is drawn as text by PDFBox (ADR-0052). Lengths are capped and control characters stripped | V1, V5 |
| R1 | An owner denies having been told of an action or its overdue state | 1, 3 | Notifications are stored per recipient (ADR-0030). The engine records timers fired and escalations as actions. The digest's `action_digest` row is the record that an email was sent | V16 |
| R2 | A verifier denies closing an action | 4 | The engine's APPROVE action carries the actor, time and remark. `actions.closed` is in the hash-chained audit | V16 |
| I1 | A director recused from an item learns of it through its actions: the tracker, a count, the report, "My actions", search, a notification | 1, 3, 6 | `ActionAccess` leaves out actions of items the viewer is recused from unless they are involved. Counts come from the same predicate in the same query. Report rows are filtered by the same rule. Actions are not in memo search (ADR-0054). Notifications carry no action text. The leak suite's E07 surfaces probe each with canaries | V8 |
| I2 | An owner outside the committee reads the item's papers, the decision's vote or other actions | 1, 6 | The owner sees the action and the context the secretary wrote. The decision is shown only by E06's rule; papers never. The item title only by the title rule. Other actions of the item only if they are involved in them too | V8 |
| I3 | Emails or their subjects leak an action's text, an item title or a decision | 3 | Subjects take no parameters. Bodies name the committee, the reference and the due date, and link to the app. The digest lists references only. The leak suite's mail channel checks canaries in every email | V14 |
| I4 | Evidence files are read through the generic files endpoints by anyone with the id | 2, 4 | The `action.evidence` purpose's `mayRead` is `ActionAccess`. Files are stored under random keys and served with `Content-Disposition` and strict headers (ADR-0029) | V8, V12 |
| I5 | A follow-up memo is created below the action's level, so its readers learn the action's subject | 5 | The memo's level defaults to the action's, and choosing a lower one needs a confirmation. The memo's note-sheet entry carries the reference only. Memo access is the memo's own rule (ADR-0050) | V8 |
| I6 | Former members, guests or item attendees of the source meeting keep reading actions | 1, 6 | `viewerToday` uses today's role. Guests and attendees see only actions they are involved in. Former members lose access unless involved (E03's rule) | V8 |
| I7 | The audit log or application logs carry action text | all | Only SHA-256 of free text in audit details. No content in logs (AGENTS §4) | V14, V16 |
| D1 | A burst of reminders or escalations floods mail when a calendar change or a bulk re-date makes many actions due at once | 3 | Emails other than escalations wait for the daily digest (one per person per day). Escalations are capped by the timers' own per-run limits (ADR-0041). The tracker's "Ask owners for an update" is limited to once a day per committee | V2 |
| D2 | Large exports or reports exhaust the node | 6, 7 | Exports are rate-limited (`EXPORT`) and streamed (SXSSF). The report has at most 500 rows. PDFs are built on demand through the bounded `ReportPages` | V2 |
| E1 | A member, a contributor or the owner performs a secretary's change (re-date, owner change, override, cancel) | 2, 4 | Deny-by-default. Matrix tests over every endpoint and column, including the recused officer, the owner, contributors and the verifier pool | V8 |
| E2 | A recused secretary issues, edits or verifies actions of the item they are recused from | 1, 4 | 403 `RECUSED` on every officer change of such an action. The verifier slot leaves recused people out | V8 |

## Residual risks
- The secretary writes an action's context. If they paste discussion into it, the owner outside the committee reads it. The form says "The owner will see this text" and shows who the owner is, and the runbook covers it.
- Action titles are free text and may name sensitive matters. They never leave the app by email. Inside the app they follow the item's rule.
- A verifier can close an action without reading the evidence. The record shows who closed it and when, and the next meeting notes closures.

## ASVS rows to add (closed in E08)
- V8 (authorisation): every action endpoint with the recused, owner-outside-committee, contributor and verifier columns, the evidence purpose, and the report rows.
- V16 (logging): every event in §5 of the design, with free text hashed.
- V1 and V5 (input, files): export formula injection, evidence uploads, lengths.
- V2 (business logic): status guards, the lock order with the engine, idempotent bulk actions, the digest's idempotency.
- V14 (data protection): notifications and the digest without content.

## Reviewed at phase close
Reviewed at phase close on 2026-11-28 (Phase 4 E08 T11): **every mitigation holds as built**. No leak was found in E07's surfaces. The suites probed each surface as it was built (ACTIONS, TRACKER, ACTION_REPORT and the evidence files, with recusal columns and officer probes).
- **Found and fixed:**
  - T1, D1 (#238, in the test, not the product): `ActionReportIT` dated actions with the database's `current_date`, which follows the JVM's zone. The report uses only the tenant's day (`OrgDirectory.today()`). `ReportSectionsDayBoundaryTest` pins the clock either side of the tenant's midnight, and since #251 the backend tests run in UTC.
- **Changed by the build:**
  - I1 (E07 follow-up): the action taken report joins its follow-up memos per reader by the memo's own read rule. `PaperLeakIT` plants a follow-up memo that none of the probed viewers may read.
  - T1: the seed-only paths added by E07 T12 cannot be reached from a request, and only `com.memo.demo` may call them. They are an action issued as on a past day (`ActionSeeding.issue` with `asOf`, through the explicit `ActionIssuing.issue` overload) and overdue timers fired at once (`WorkflowTimerSeeding.catchUp`, `TimerCatchUp`). `ArchitectureRulesTest` enforces this (added at phase close: only the demo seeders use a `*Seeding` facade; only `WorkflowTimerSeeding` uses `TimerCatchUp`; only `ActionSeeding` calls the `asOf` overload). The controllers call the overload that uses today. The demo seeders bind only the demo tenant and start only with `memo.demo.seed=true`. The demo tenant's seeder refuses the `prod` profile and stops the start-up (`DemoTenantSeedIT`).
- **Evidence:** the seven E07 rows of `docs/security/asvs-checklist.md` (V8 twice, V2, V1, V5, V14, V16), with their tests. Also:
  - S3: `OWNER_NOT_CLEARED` and re-resolution (`OwnerChangeIT`, `SubjectSlotIT`). T5: `ActionDefinitionSeedIT`.
  - I5: the level defaults to the action's, and a lower one needs a confirmation (`FollowUpMemoIT`).
  - R1: the per-recipient notifications and the digest's row (`ActionsDigestIT`). D1: the digest and `UpdateRequestsIT` (once a day per committee).
- **Residual risks:** all three confirmed. The secretary's context is shown to an owner outside the committee, with the form's warning. Titles are free text inside the app. A verifier can close without reading the evidence.
- **Pen test:** area 6 (the action taken report and its follow-up memos) and area 4 (the tracker's export) of `docs/security/pen-test-scope.md`.
