# E07 Action items — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`.
> - **Phase-4 special tests (§12):** E07's surfaces in "recusal enforced across API, search, notifications and exports".
> - **State-machine tests** for action items: every allowed and every forbidden transition (overview §5).
> - **The exit criterion's chain**, memo → item → resolution → action → follow-up memo, walked both ways.
> - **The manual keyboard and screen-reader pass** over the action tracker (overview §5).
>
> CI stages: PR (unit, property, integration, matrix, leak suite, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale), weekly (load).

## 1. Scope and risks
**Risks covered:**
- an action closed by its own owner or without verification;
- a missed date hidden by re-dating or a colour override, with no trace;
- reminders that never fire, fire twice, or keep firing after closure;
- an escalation to the wrong person;
- RAG that differs between the list (SQL) and the page (Kotlin);
- a recused director learning of an item through its actions, a count, the report, an email or "My actions" (overview risk R1);
- an owner outside the committee reading the item's papers or the vote;
- a report that is not what the meeting was shown;
- the engine changes breaking a memo route (Phase 2's invariants).

**Seed:**
- E06's held BRC meeting (7 voting members, `secretary@` and `secretary2@`, `director@` recused from item 5, item 6 restricted to two members with a hidden title) and the MCC meeting with the credit memo decided as MCC/2026/001 with conditions;
- the positions Head of Treasury and Head of Credit Risk, each with a holder and a supervisor by the reporting line, and an executive outside every committee (`exec@`);
- the next BRC meeting with "Matters arising and actions";
- a substitute for Head of Treasury's holder;
- **canaries:** `CANARY-ACT-…` in action titles, descriptions, context and updates of item 5's actions, and `CANARY-EVID-…` in an evidence file's text. They are checked in every API response, list, count, report row, PDF, export, notification, email and audit detail for the people who may not see them.

## 2. Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `DefinitionValidatorTest` rows: `UNKNOWN_SLOT`, `SUBJECT_REQUIRED` (a `subject` assignee or `sla.from` without `subject`), `WRONG_SUBJECT`, a subject's `checkDefinition` problems; JSON round trip of the new keys; golden JSON of every stored built-in definition unchanged |
| T1 | Integration | `SubjectSlotIT` (test subject): person; position resolved and re-resolved on holder change keeping the due time; a pool of three with claim and release; slot as escalation `to`; SOD-2 removes the recommender from the pool; SOD-4 interested; `initiatorTakesPart` true and false; `fallback` when the slot is empty |
| T1 | Integration | `SubjectDueIT` on `MutableClock`: due from the subject at the end of a date; remind before and after in working time; `redue` later (pending reminder cancelled, new planned), earlier (past reminder skipped, DUE fires at once), cleared (null: no timers); send-back reads the due time again; a closed task ignores `redue` |
| T1 | Property + regression | The engine's property suite (TESTING_STRATEGY §4) unchanged; all Phase 2 workflow ITs and the memo route suites green; `WorkflowTaskCreated.nodeId` present |
| T2 | Unit + integration | `ActionsPolicyChecksTest` (ranges, enums, defaults when null, presets); `ForumTypeActionsPolicyIT` (maker-checker, changes view, current version read for a committee, `UNKNOWN_DEFINITION`); `ActionDefinitionSeedIT` (built-in `action-item` per tenant, passes the subject's check, editable as a new version) |
| T3 | Unit | `ActionRulesTest` (each invariant of design §1.1 with its problem code; the reference format; classification never lowered) |
| T3 | Integration | `ActionsRaiseIT`: drafts from ITEM, MEETING, DIRECT (and DECISION with a condition once E06 T3 is merged); raise windows per meeting state; discard; issue one and all with per-action results; the counter per committee and year (concurrent issues → 1..n, no duplicates); item columns copied and refreshed on a later revision; owner cleared check; a recused owner allowed with the warning; contributors refused when recused |
| T3 | Integration | `ActionSubjectIT`: slots, `dueAt`, attributes for CEL (`action.priority`, `action.fromResolution`), `ineligible`, `checkDefinition` on the built-in and on bad definitions (no verification, REJECT offered, FORWARD on the owner's step), status, holder and step name kept in step with engine events in the same transaction, lock order with a racing update (no deadlock in 3 rounds) |
| T3 | Authorisation | Matrix `p4-e07-actions.yaml`: every endpoint × Sec, Chair, Mgr, Member, Read, Guest, Att, Former, Owner, Contrib, Verifier, **recused secretary**, **recused member**, other-committee secretary; 404 for invisible, 403 for forbidden |
| T3 | Persistence | `ActionsRlsIT`, `ActionsCrossTenantIT` (every table, endpoint, the copied item columns, the workflow instance of another tenant's action, the evidence file); triggers refuse changing source, reference, original date; no UPDATE on entries |
| T3 | Architecture | `ActionAccess` is the only reader of the action tables; actions does not call meeting or minutes internals; memo, meeting and minutes do not depend on actions |
| T4 | Unit + property | `RagRuleTest` (the table of design §1.5, every row); **`RagRuleProperties`**: for random due dates, calendars (holidays, Friday–Saturday weekends), confidences, overrides and today, the Kotlin rule and the SQL expression give the same colour and words |
| T4 | Integration | `ActionProgressIT` (updates and confidence, on behalf with `how`, corrections as new entries, files through the upload pipeline with ClamAV and the 10-file cap, OPEN → IN_PROGRESS, stale and override clearing); `DueChangeIT` (each `dueChange` mode, one pending request, withdrawn on done, direct change, revision count, original date kept, `redue` called); `OwnerChangeIT` (person to position and back, reassign, holder told); `CancelReopenIT` (each kind, risk accepted by the chair or on behalf, supersededBy in the same committee, reopen within 90 days with a new instance, `TOO_LATE` after) |
| T4 | State machine | **`ActionStateMachineTest`**: every allowed transition of design §1.4 and every forbidden one (updates on DRAFT, CLOSED, CANCELLED; verify not DONE_PENDING; issue twice; reopen DISCARDED; any officer change by a recused officer) |
| T5 | Integration | **`ActionWorkflowIT`** on `MutableClock` with the built-in definition: reminder before, DUE (holder and secretaries told), reminder after, escalation to the supervisor by reporting line (and none when there is no supervisor, the secretaries still told), verification reminder and chair escalation; substitute receives and acts; nothing fires after closure or cancel |
| T5 | Integration | `ActionClosureIT`: done with note and evidence (`EVIDENCE_REQUIRED`), the pool excludes owner, holder, contributors and recused; owner who is also a secretary is not in the pool; CHAIR mode with vice-chair fallback; close and send back (remark required; owner's step again with the same date, overdue at once if past); close-agreed at the meeting with per-action results |
| T5 | Integration | `ActionsDigestIT`: in-app items at once, one digest email per person per working day at 09:00 in the tenant's zone, none on a holiday, idempotent on rerun, escalations emailed at once; subjects without parameters |
| T6 | Integration | **`FollowUpMemoIT`**: raise a memo (type offered, template filled, initiator, level default and lower with confirmation), link existing (readable only), 10-memo cap; the memo shows "Raised from action" with a link only for action viewers; note sheet entry with the reference only; memo approved, rejected, withdrawn → entry and notice, action not closed; verifier sees the memo's status without memo access; memo suites unchanged |
| T7 | Integration | **`ActionReportIT`**: built at publication of a revision with a MATTERS_ARISING item (not without one); sections in order with the right rows; frozen (later changes do not alter it; a new revision makes a new report); "since the last meeting" boundaries; E06 corrections (once T8 of E06 is merged); held back counted for officers; 500-row cap with the "and n more" line; content hash audited |
| T7 | Integration | `UpdateRequestsIT`: owners asked N working days before the agenda deadline, once per meeting, only those without an update since the previous meeting; "Ask owners for an update" once a day |
| T8 | Integration | `ActionTrackerIT`: every filter, keyset pages, totals from the same predicate, text filter, sort; `TrackerExportIT` (XLSX opens, formula-safe cells, rate limit, audit) |
| T9 | Performance | `ActionsVolumeIT` (design §8 volumes): every budget; `EXPLAIN` plans recorded for "My actions", the tracker with and without text, the report build and read, the daily task; weekly `load` |
| T10, T11 | Component | Vitest + Testing Library: the action form (owner picker with positions, due quick picks, warnings in words), RAG badge (word, icon and colour; axe colour contrast), the update form ("How is it going?" as a radio group), date request, mark as done, verify with remark, the tracker's filters and key, the report sections, "My actions" tabs and empty states; axe on each |
| T10, T11 | E2E | Playwright `action-items.spec`: raise from a condition on the decision panel, issue at "Mark as held", the owner updates and asks for a date, the secretary agrees, the owner raises a follow-up memo and marks done, `secretary2@` verifies; the next meeting's report shows it closed; visual baselines; pseudo-locale and Arabic RTL screenshots |
| T12 | E2E + IT | The E07 leg of **`committee-cycle.spec`** (UI only, on CI and against dev) and **`CommitteeCycleIT`**: from the credit memo through submission, item, decision, resolution, action, follow-up memo and back from the follow-up memo to the source memo; every step's audit entry present and the tenant chain verifying |
| T12 | Manual | Keyboard and screen-reader (NVDA and VoiceOver) pass over the tracker (filters, rows, officer actions) and the action page, recorded in the log |

## 3. State machine coverage
From design §1.4, each with a test:
- **Allowed:**
  - DRAFT → DRAFT (edit), DRAFT → CANCELLED (discard), DRAFT → OPEN;
  - OPEN → IN_PROGRESS (update, started), OPEN → DONE_PENDING, IN_PROGRESS → DONE_PENDING;
  - DONE_PENDING → IN_PROGRESS (send back), DONE_PENDING → CLOSED;
  - OPEN, IN_PROGRESS and DONE_PENDING → CANCELLED;
  - CLOSED → IN_PROGRESS and CANCELLED → IN_PROGRESS (reopen ≤ 90 days).
- **Forbidden** (409 or 403):
  - issue a non-draft;
  - update, re-date or mark done DRAFT, CLOSED or CANCELLED;
  - verify OPEN or IN_PROGRESS;
  - reopen DISCARDED or after 90 days;
  - mark done by a contributor;
  - verify by the owner;
  - any officer change by a recused officer.

## 4. Phase-4 special test covered here
| Special test | Where |
|---|---|
| Recusal enforced across API, search, notifications and exports: E07's surfaces | §5 probes in `RecusalLeakIT` (T3, T5, T7, T8); the registry's meta-test fails an `actions` controller method without a probe |

## 5. The leak-suite surfaces added by E07
In T3, `PaperSurfaceRegistry`'s E07 placeholder (`/api/v1/actions`, `/api/v1/me/actions`, `/api/v1/meetings/{meetingId}/actions`) is replaced by these surfaces. The forum tracker's path `/api/v1/forums/{forumId}/actions` is added too. Every `actions` controller method goes into `endpoints` or `officersOnly`.

| Surface | A person recused from item 5, not involved, sees | An owner outside the committee sees | Probe |
|---|---|---|---|
| One action (`/actions/{id}` and every sub-resource) | 404 | Their action: title, context, timeline, evidence; source label without a hidden title; no decision vote, no papers | `ActionProbe` |
| The meeting's actions | No row of item 5; counts without them | Only their own | `MeetingActionsProbe` |
| "My actions" and its counts | No row of item 5 (unless involved) | Their own | `MyActionsProbe` |
| The committee's tracker, totals and export | No row, no count, no export line of item 5 | Only their own rows; no export | `TrackerProbe` |
| The action taken report (screen and PDF) | No row of item 5 | — (not in the item's audience) | `ActionReportProbe` (text layer canaries) |
| Evidence files through the generic files endpoints | 404 | Their action's | `EvidenceProbe` |
| In-app notifications, emails, the digest | Nothing about item 5's actions; no canary in any email | Reference, committee, date only | `ActionMailProbe` (Mailpit) |
| Memo search | Actions are not indexed (ADR-0054 unchanged) | — | `SEARCH` channel probe |
| Audit viewer and export | Hashes of free text only | — | `AUDIT` channel probe |
| The follow-up memo's "Raised from action" | The reference as text, no link | Link | `MemoOriginProbe` |

## 6. CI stages
- **PR:** unit, property (`RagRuleProperties`), integration (Testcontainers PostgreSQL, SeaweedFS, ClamAV for evidence), the matrix file, the leak suite, `ActionStateMachineTest`, smoke E2E.
- **Nightly:** PIT on `RagRule`, the verifier pool and the report builder (score ≥ 80 %); full browsers, visual, pseudo-locale; `committee-cycle.spec` against the stack.
- **Weekly (`load`):** `ActionsVolumeIT`.
