# E02 Submissions and the escalation link — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-18 unless stated. Phase research items covered here: R1 (versions), R2 (submission and triage practice), and R8 (the engine's waiting step). Sources marked **[snippet]** were read only from a search result, because the site blocked automated reads.

## 1. Starting point (code, 2026-11-18)
- **Workflow engine:**
  - `Rounds.pass` handles `escalate-to-forum` like `notify`. It records `ESCALATE_TO_FORUM`, adds `Effect.EscalateToForum(nodeId, forum)` and walks on to `next` in the same command (`workflow/engine/Rounds.kt`).
  - `ActionEffects` publishes `WorkflowEscalatedToForum(tenant, instance, subjectType, subjectId, nodeId, forum)`.
  - Steps carry a `StepState` (`PENDING`, `ACTIVE`, `DONE`, `SKIPPED`), a `StepKind` (`APPROVE`, `REVIEW`, `PARALLEL`) and tasks. A step with no task is not a state the engine knows today.
  - Commands are a sealed interface (`Recall`, `Cancel`, `Migrate`, the task commands). The engine is a pure transition over the instance state, and the service writes what changed.
- **Forum module (E01):**
  - Forums, members, `ForumDirectory`, and `EscalationTargets`, so a definition can only name an active forum.
  - Forum types are versioned policy; later epics add their fields to the version.
- **Memo readers (ADR-0050):** one `memo_participant` table with kinds `INITIATOR`, `COAUTHOR`, `APPROVER`, `NOTIFIED`, `COPY`, `SHARED`, under clearance. A forum's officers have no way in yet.
- **Memo outcomes the route already has:** approve (continue), reject (final), return to the initiator (a new round on resubmit), withdraw by the initiator.
- **Demo (E01 T6):** six committees. The credit workflow's "Credit committee" is still a parallel approval step, not an escalation.

## 2. Versions (R1)
E02 adds **no new dependency**: engine changes, forum tables, memo reader kinds, web screens with existing components.

## 3. Industry practice (R2)

### 3.1 Submission and triage in meeting tools
| Product | What it does | Source |
|---|---|---|
| eScribe | Two intake paths: an *agenda request* (meeting type, optional date, category) and a *report* with its own approval workflow before the administrators. Statuses the submitter sees: "Approval Started", approved, "Added". The administrator approves onto a meeting or rejects back to the contributor. Three deadline modes: allow late, block, or a late-exception workflow, which approves lateness, not content **[snippet]** | <https://help.escribemeetings.com/hc/en-us/articles/41414547303053>, <https://help.escribemeetings.com/hc/en-us/articles/41425581733261> |
| Diligent Community | Tabs Draft, Submitted, Pending, Rejected, Added; a reject needs a comment; the submitter sees who approved or rejected and why, and resubmits from Rejected | <https://help.diligentoneplatform.com/helpdocs/d1p/en-us/Content/community/community_admin_guide/review-and-approve-submitted-agenda-items.htm> |
| Boardable | An ordered approval tree with Approve, "Edit and Continue" and Reject. Administrators can skip an approver with a logged reason. A meeting cannot be published until every approval is done | <https://docs.boardable.com/knowledge/use-agenda-approval-workflows> |
| CivicPlus | Status "Waiting For [role]"; a decline needs a reason; resubmitting restarts the whole chain | <https://www.civicplus.help/municipal-websites-central/docs/manage-agenda-items> |
| Granicus Legistar | Approval sequence before the clerk; scheduling closes after a deadline **[snippet]**. City of Madison: files referred to a committee are not reliably pulled onto its agenda, so staff keep spreadsheets | <https://support.granicus.com/articles/How_To/Pre-Agenda-Approval-in-the-Files-Module>, <https://www.cityofmadison.com/node/85186> |

Corporate board portals (Boardvantage, OnBoard, BoardEffect, Convene, iBabs) publish no submission workflow; the secretary builds the book and intake is by email.

**Taken from this:**
- **An escalated memo is a referral object the secretary cannot lose.** It sits in the committee's queue until it is triaged, and the queue flags referrals that wait too long. This is Legistar's documented failure.
- **The submitter sees a status that names who holds the item:** "With the Credit Committee secretary", "Accepted for the next meeting", "Returned: see the reason".
- **Triage outcomes** are accept, defer, return for more information (reason required) and decline (reason required). A return does not throw away the approvals already given in earlier rounds: the memo's own round rules apply.
- **Cover-sheet data** comes with the submission: purpose (for decision, for discussion, for information), the resolution sought, and the presenter. The papers are the memo's documents.

### 3.2 Deadlines and late papers
- **Exim Bank Malaysia, Board Credit Committee (2026):** papers no later than five working days before the meeting; notice seven calendar days before, and shorter notice needs the chair (<https://www.exim.com.my/documents/about-us/corporate-governance/TOR_of_BCC_2026.pdf>).
- **Bank of Cyprus, Risk Committee:** agenda and papers no later than five working days before (<https://www.bankofcyprus.com/contentassets/b4323151bcac4d689c95e4ae28d98440/terms-of-reference-of-the-risk-committee.pdf>).
- **Bank of England, FMI Committee:** papers two days before; "the Chair may decide to defer papers that do not meet this deadline" (<https://www.bankofengland.co.uk/about/people/fmi-committee/tor>).
- **Governance Institute of Australia:** late papers need the chair's approval, and on-time papers can be a preparer KPI (<https://www.governanceinstitute.com.au/?p=15865>).
- **Loughborough University, committee handbook:** late items at the chair's discretion, shown "to follow"; a cover sheet with the action required (approve, consider, note) (<https://lboro.ac.uk/committees/handbook/13-committee-papers/>).
- **Board Intelligence 2025:** 55 % of directors get packs less than five working days before the meeting (<https://www.boardintelligence.com/blog/the-state-of-board-effectiveness-in-2025>).

**Taken from this:**
- A per-type submission deadline in working days on the business calendar (default 5), with a late mode: allow, block, or the chair's approval. Lateness is approved separately from content.
- Deadlines bind to a meeting date, so E02 stores the policy and flags lateness against a target date. E03, which brings meetings, enforces the deadline when an item is placed on an agenda.

### 3.3 Credit decisions above delegated authority
- **EBA Guidelines on loan origination and monitoring (EBA/GL/2020/06):**
  - A documented decision framework with the hierarchy of decision-makers, committee working and escalation (¶63–69).
  - The decision is clear and documented, "including all the conditions and pre-conditions" (¶196).
  - It has a maximum validity (¶197).
  - Conditions are verified before the agreement and disbursement (¶198).
  - Source: <https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2020/Guidelines%20on%20loan%20origination%20and%20monitoring/884283/EBA%20GL%202020%2006%20Final%20Report%20on%20GL%20on%20loan%20origination%20and%20monitoring.pdf>.
- **Exim's Board Credit Committee** can approve, reject or modify credits that the Management Credit Committee approved, which is a chain of committees (<https://www.exim.com.my/documents/about-us/corporate-governance/TOR_of_BCC_2026.pdf>).
- **BPI Europe's credit committee:**
  - It approves within its limits, gives conditions, and refers what is above its limits to the board.
  - Decisions need two concurring members, one of them the chair.
  - Source: <https://www.bpi.com.ph/content/dam/bpi-europe/pdfs/governance/BPI%20Europe%20Terms%20of%20Reference%20-%20Credit%20Committee.pdf>.
- None of the sources describes a memo state "pending committee". The credit file is simply with the committee until it comes back with a decision.

**Taken from this:**
- The memo waits at the forum step and comes back with the decision. The outcome kinds of E01 map to fixed effects on the route (ADR-0058).
- "Referred" opens a new submission to another forum, and the memo keeps waiting.
- Conditions travel with the decision as text in E02 and E06. Structured conditions with owners and due dates are action items (E07); a validity period is noted for E06.

## 4. The waiting step (R8)
Options:
1. **A forum step as an `ACTIVE` step with no task, resumed by a new engine command.** The pure transition stays the single writer of instance state. Its timers (SLA) work as on other steps, and migration of pinned instances treats it like any active step.
2. **A synthetic task assigned to the forum's secretary.** This reuses task machinery, but the secretary would "approve" on behalf of the committee, which blurs the record. Inbox, delegation and separation-of-duties rules would also apply where they make no sense.
3. **Leave the route running and link the outcome afterwards.** This is today's behaviour. The memo could be approved before the committee decides, which breaks the chain the exit criterion needs (Phase 4 Q3).

**Chosen: option 1** (ADR-0058).

- The step's `resolution` holds the forum key and the submission id.
- A new `StepKind.FORUM` lets lists show "With the Credit Committee".
- The definition gains `"wait": true | false` on `escalate-to-forum`.
  - Existing definitions have no `wait` and keep passing through, so pinned instances never change meaning.
  - The builder writes `wait: true` for new steps, and the schema defaults `wait` to false when absent.

## 5. Conclusions for the design
1. **Waiting step:** `escalate-to-forum` with `wait: true` creates an active step with no task. `ForumDecided` (approve and continue, reject, return) and `ForumWithdrawn` (the forum declines, returning the memo) resume it. Deferred and referred keep it waiting.
2. **Submission:** a `forum_submission` row per referral, created by the consumer of `WorkflowEscalatedToForum` in the same transaction.
   - It links the forum, the memo, the instance and the step, and holds the cover-sheet data and a status timeline.
   - Statuses: QUEUED → ACCEPTED → DECIDED, plus DEFERRED, RETURNED, DECLINED, WITHDRAWN and REFERRED.
3. **Direct submissions** are memos of a "committee paper" type per committee, whose workflow escalates to that committee. This is configuration only. The demo gets "Credit committee paper".
4. **Triage:** accept (optionally with a target date), defer (with a reason), return for more information (reason), decline (reason). Each is audited, shown on the memo's note sheet, and told to the submitter.
5. **Readers:** the forum's chair and secretaries become memo readers through a new participant kind `FORUM` while the submission is open. Members get access when the item is on a published agenda (E03).
6. **Deadline policy:** on the forum type (working days, late mode); stored and shown in E02, enforced in E03.
7. **Queue hygiene:** a referral not triaged within the type's triage days (default 5 working days) is flagged on the queue and reminds the secretaries once.
