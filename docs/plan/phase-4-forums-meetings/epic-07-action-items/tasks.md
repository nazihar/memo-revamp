# E07 Action items — tasks

Backend first, then web. **The screens are delegated** (Phase 4 Q1): the web tasks wait only for their backend tasks, not for an owner review. Each task is its own PR, merged when CI is green (standing go-ahead, 2026-11-17).

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **The engine additions (ADR-0068):**<br>• assignee kind `subject` with slots, through `WorkflowSubject.slot`: persons, positions (with re-resolution on holder change), pools with claims, slots as escalation targets;<br>• `sla.from: "subject"` with `WorkflowSubject.dueAt`, and the published `Workflows.redue`;<br>• a definition's `subject` key, `WorkflowSubject.checkDefinition`, `initiatorTakesPart`, `WorkflowTaskCreated.nodeId`;<br>• schema v2 additions, validator problems (`UNKNOWN_SLOT`, `SUBJECT_REQUIRED`, `WRONG_SUBJECT`), and the builder catalogue listing subjects with their slots and attributes (backend);<br>• a test subject in `testsupport` exercising every addition;<br>• the engine's property tests and every Phase 2 suite unchanged;<br>• confirm that no workflow CHECK constraint needs a migration (else `workflow/V17`) | L | — (merged code only) |
| T2 | **The committee type's actions policy and the built-in definition:**<br>• `forum/V8`; `ForumTypePolicy.actions` (§1.13) with checks, presets, the changes view and maker-checker; the version in force today read by `ForumPolicies.current(forumId)`;<br>• the built-in `action-item` definition seeded per tenant like `sequential-approval` (`WorkflowSeeding`);<br>• matrix rows for the type endpoints | M | T1 |
| T3 | **Actions: raise, issue, see:**<br>• `actions/V1`; drafts from ITEM, MEETING and DIRECT sources (DECISION when E06 T3 is merged, through the `DecisionSource` adapter); edit and discard; issue (one and all of a meeting) with the counter and `Workflows.start`; owner (person or position) and contributors; item columns copied and refreshed on `AgendaPublished`;<br>• the `action-item` subject: slots `owner`, `verifier`, `chair`, `secretaries`; `dueAt`; attributes; `interested`; `summaries`; `lock`; `ineligible` (clearance, inactive); `checkDefinition`; status, holder and step name from engine events;<br>• `ActionAccess` (§1.14) and its architecture test; `GET /actions/{id}`, `GET /meetings/{id}/actions`, `GET /me/actions` (MINE);<br>• the `MinutesItemActions` port implementation (if E06 T5 has added it);<br>• the leak suite: `PaperSurfaceRegistry`'s E07 placeholder replaced by the surfaces and probes (test plan §5);<br>• audit; matrix `p4-e07-actions.yaml` (these endpoints); cross-tenant tests | L | T1, T2; E04 merged (`Recusals`) |
| T4 | **Progress, dates, RAG, owner, cancel and reopen:**<br>• `actions/V2`; updates with confidence and `action.evidence` files, on behalf, corrections; "I have started";<br>• date requests and decisions per `dueChange`, direct changes, `redue`;<br>• RAG in SQL and in Kotlin (one definition, tested to agree), `amberFrom` and `staleFrom`, overrides;<br>• owner and contributor changes (reassign or re-resolve); edits after issue with reasons;<br>• cancel with kinds (risk accepted by the chair or on their behalf), reopen within 90 days with a new instance;<br>• audit; matrix rows; state-machine tests | L | T3 |
| T5 | **Reminders, escalation, closure and the digest:**<br>• reactions to `WorkflowTimerFired` and `WorkflowEscalated` (in-app items, secretaries on DUE, escalation email);<br>• mark as done with note and evidence (`evidenceRequired`); verify: close or send back; the verifier pool's rules; "Close the actions the committee agreed";<br>• the notification kinds (en/ar), the `actions-daily` task: (a) the digest with `action_digest`, (c) the safety net;<br>• `GET /me/actions` TO_VERIFY and CONTRIBUTING tabs with counts;<br>• `ActionWorkflowIT` on a test clock; audit; matrix rows | L | T4 |
| T6 | **Follow-up memos:**<br>• memo additions: `MemoDrafts.create` (the `POST /memos` path with the template fill after commit), the `MemoOrigins` port, `MemoBrief.status`, the `RAISED_FROM_ACTION` system note;<br>• `actions/V3`; raise and link; the memo's state on the action by the memo's rule; memo decided → entry and notice;<br>• `FollowUpMemoIT`; the memo's own suites unchanged; audit; matrix rows | M | T3 |
| T7 | **Matters arising: the action taken report:**<br>• `actions/V4`; the report built in `AgendaPublished` for a revision with a MATTERS_ARISING item (sections 1–6, at most 500 rows); held-back rows;<br>• the item link through the meeting's item-links port (added here if E06 T6 has not); the report API with rows by the rule; the PDF through `ReportPages`;<br>• the `actions-daily` task's (b): update requests before the agenda deadline (`AgendaDeadlines.of` published by meeting); "Ask owners for an update";<br>• audit; matrix rows; the leak-suite report surface | L | T5; E06 T8 for section 6 (empty until then) |
| T8 | **The tracker and its export:**<br>• `GET /forums/{fid}/actions` with every filter, totals per RAG from the same predicate, text filter, keyset; DIRECT drafts; XLSX export (SXSSF, formula-safe, rate-limited, audited);<br>• matrix rows; leak-suite tracker surface | M | T4 |
| T9 | **Volumes and performance:**<br>• the generator extended (design §8); `EXPLAIN` for "My actions", the tracker (with text), the report and the daily task;<br>• `ActionsVolumeIT` against every budget; weekly `load` | M | T7, T8 |
| T10 | **Web, the secretary's side** (flows AJ, AM, AN, AQ; delegated):<br>• "Actions" on E06's decision panel (each decision and condition) and on the meeting page; the action form with owner picker (person or position), contributors, due date quick picks and the classification warning; drafts and "Send the drafts"; the held-meeting dialog's line;<br>• verification with evidence and memos, closing at the meeting;<br>• the committee's tracker with filters, RAG key and totals, the officer actions (date, owner, override, cancel, reopen, hold back, ask for updates), export;<br>• the committee type's Actions tab;<br>• en/ar; 44 px targets | L | T5, T8; E06 T10 merged (the decision panel) |
| T11 | **Web, the owner's and members' side** (flows AK, AL, AO, AP, AR; delegated):<br>• "My actions" in the inbox (tabs, counts, RAG in words, empty states); the action page (source and context, updates with "How is it going?", evidence, date request, mark as done, timeline);<br>• follow-up memo: raise with the type chooser, link existing; "Raised from action" on the memo view;<br>• the action taken report on the "Matters arising" item and its print;<br>• en/ar | L | T5, T6, T7 |
| T12 | **Close:**<br>• demo seed: on the held BRC and MCC meetings (E06's seed), actions from a condition of MCC/2026/001 (owner: Head of Credit Risk, a position), from a discussion item, and a direct one; one overdue and escalated, one done awaiting verification, one closed with evidence, one with a revised date, one cancelled with risk accepted; a follow-up memo raised from the condition's action and approved; the next BRC meeting's published agenda with its action taken report;<br>• journeys `action-items.spec` and the E07 leg of the exit journey (`committee-cycle.spec`: resolution → action → follow-up memo → verified), with `CommitteeCycleIT` walking the links both ways;<br>• manual keyboard and screen-reader pass over the tracker and the action page (overview §5); visual baselines;<br>• runbook `action-items.md` (the definition and its checks, reminders and the digest, escalation, re-dating, risk accepted, the report and held-back rows, evidence, what operations check); ASVS rows; log and STATUS | M | all |

## Order and parallel work
- **Backend:** T1 → T2 → T3 → (T4 → T5 → T7) and T6 after T3, T8 after T4 → T9.
  - T1 and T2 can start now: they need only merged code.
  - T3 needs E04's `Recusals` (merged). It raises DECISION actions only once E06 T3 is merged.
  - T7's section 6 waits for E06 T8.
- **Web:** T10 waits for E06 T10 (the decision panel it extends). T11 can run beside T10.
- T12 is last.
- **In parallel:**
  - T6 beside T4 and T5.
  - T8 beside T5.
  - T10 and T11 by two sessions.
  - E07 T1 and T2 beside E06 T3 and later tasks: they touch workflow and forum, not minutes.

## What to ask of E06's remaining tasks (design §11)
- **E06 T3:** publish `DecisionRecorded` for corrections too, with the entry `seq`. Publish `Decisions.find` and `Decisions.entry`.
- **E06 T5:** add the `item.actions` and meeting-level `actions` row loops to the minutes vocabulary and the built-in template, filled through a `MinutesItemActions` port with an empty default.
- **E06 T6:** make the meeting's item-links port a list of implementations keyed by standing kind.

## Acceptance criteria (selection; the full list is in `test-plan.md`)
- **T1:** given a test definition with `{"kind":"subject","slot":"owner"}` and `sla.from: subject`, when the subject's owner is a position, the task goes to its holder and is due at the subject's time. When the holder changes, it moves to the new holder with the same due time. When `redue` moves the date two weeks later, the pending reminder is cancelled and a new one planned. A memo definition stored before T1 validates and runs unchanged.
- **T2:** given a draft committee type with `amberWorkingDays` 0, when submitted, then 422 `RANGE` at `actions/amberWorkingDays`. The Board Audit preset has `evidenceRequired` true.
- **T3:**
  - Given the held BRC meeting and `secretary@` not recused from item 5, when they raise an action from item 5 with the owner "Head of Treasury" and due by the next meeting, then it is a draft seen only by officers.
  - When they issue it, it is **BRC A1/2026**, the holder of Head of Treasury has a task due at the end of the next meeting's date, and they are told by email without the action's title.
  - `director@` (recused from item 5) gets 404 on the action and does not find it in `/me/actions`, the meeting's actions or any count. If `director@` were its owner, they would see it without the item's papers.
  - Raising as a recused secretary answers 403 `RECUSED`.
- **T4:**
  - Given SECRETARY_AGREES, when the owner asks for a new date with a reason, the action keeps its date until `secretary@` agrees. Then it shows "Due 20 January 2027 (originally 12 December 2026, revised once)", and the owner's reminder moves with it.
  - A RAG override without a date is cleared by the next update.
  - Cancelling with RISK_ACCEPTED as a secretary without `how` answers 422.
- **T5:**
  - Given an action due on a Thursday with the default definition, the holder gets an in-app reminder 5 working days before. The email comes in the next morning's digest, listing the reference only.
  - On the Friday, the action is red, "Overdue", and the secretaries are told in the app.
  - 10 working days later, the holder's supervisor gets an escalation email.
  - When the owner marks it done with a note, the verify task goes to the secretaries' pool, not to the owner (also a secretary). `secretary2@` closes it, and the owner is told.
  - Sending back without a remark answers 422.
- **T6:** given an open action, when its owner raises a memo of type "General memo", a draft memo exists with the type's template filled, its note sheet says "Raised from action BRC A1/2026 (Board Risk Committee)", and the action lists it. When the memo is approved, the action's timeline records it and the owner is told, but the action stays IN_PROGRESS.
- **T7:**
  - Given 2 done, 3 overdue and 9 open actions of BRC and 4 closed since the last meeting, when the next meeting's agenda is published, its "Matters arising" item links to a report with those sections in that order.
  - A later revision gets its own report, and the first is unchanged.
  - `director@` sees no row from item 5.
  - A held-back action is absent and counted for officers.
- **T8:** the tracker filtered "Overdue" for BRC returns the 3 overdue actions with totals "Red 3, Amber 2, Green 7, To verify 2". The export's cell for a title beginning with `=` starts with `'`.
