---
status: accepted
date: 2026-11-21
deciders: Claude (agent) under the delegated gate (Phase 4 Q1: E07's screens are not reviewed by the owner before build); product owner aradnazihar (two refinements of the overview reported: the RAG rule per committee type, and the whole action, not only its closure, on the workflow engine)
---

# 0067 — Action items: owned, dated actions on the workflow engine, RAG per committee type, closure verified by someone else, follow-up memos, an action taken report at the next meeting

## Context and problem statement
Phase 4 E07 closes the loop the exit criterion names: "a resolution with a number, then action items, then a follow-up memo, all linked and auditable". Committees raise actions from their decisions (often from a decision's conditions) and from discussions. Each action needs an owner, a due date, progress, reminders and escalation, and closure that someone checks. Each committee must see its open actions again at its next meeting.

Practice differs on RAG meanings and on who closes an action. Supervisors expect timely follow-up with an audit trail (Basel 2015 ¶46, ¶60, ¶69, ¶167; EBA/GL/2021/05 ¶151; RBI's 2025 model agenda item "Minutes and Action Taken Report"). Research: `phase-4-forums-meetings/epic-07-action-items/research.md §3–§7`.

## Decision drivers
- One accountable owner and a verifier who is someone else (CGI 2024 guidance; the IIA's evidence-based follow-up).
- The record is append-only (AGENTS §3.3): updates, date changes and verification are entries, and nothing is deleted.
- Configuration over code (AGENTS §3.5): RAG thresholds, date-change agreement, verifier and reminder times are tenant data.
- Reuse the engine's timers, substitutes, position holders and escalation (Phase 2) rather than build a second scheduler.
- Recusal: a person recused from an item does not see its actions unless they are involved (the E04 placeholder).
- Simple for directors and executives: words with every colour, few emails.

## Considered options
**A. Where the action's life runs**
1. **The whole action on the engine:** a new subject `action-item` with a tenant-editable definition. The owner's step is due on the action's date, and a verification step follows (chosen).
2. Only closure on the engine (the overview's sentence), with the owner's period and its reminders on the actions module's own timers.
3. Everything in the actions module: its own state machine and its own timers, with no engine.

**B. The RAG rule**
1. **Derived from stored dates and the owner's word, with thresholds per committee type and an override with a reason** (chosen).
2. One tenant-wide rule (the overview).
3. RAG set by hand by the secretary.

**C. Carry-forward**
1. **A frozen action taken report per published revision of the next meeting, under the "Matters arising" standing item** (chosen).
2. A live view of the tracker linked from the item.
3. Copying each open action into the next agenda as a sub-item.

**D. Follow-up memos**
1. **A draft memo created from the action through a published memo API, linked both ways; the decided memo tells the owner, and does not close the action** (chosen).
2. The same, closing the action automatically when the memo is approved.
3. A memo-to-memo `FOLLOWS_UP` link from the source memo (ADR-0043).

## Decision outcome
Chosen: **A1, B1, C1, D1.**

### The action
- **The record:**
  - Belongs to one committee.
  - Its source is a decision (or one of its condition lines), an item without a decision, the meeting, or "direct" (between meetings).
  - It has a title, description, the context the owner is told, a level, an owner (a person, or a position that follows its holder), up to five contributors, a priority, a due date with the original date and a revision count, a status, the owner's confidence, and a timeline of append-only entries.
- **Reference and drafts:**
  - The reference `BRC A14/2026` is taken from a counter per committee and year when the action is issued.
  - Drafts are seen only by the committee's secretaries and managers. They are sent to owners singly, together, or from the "Mark as held" dialog.
- **Statuses:** DRAFT → OPEN → IN_PROGRESS → DONE_PENDING → CLOSED, with CANCELLED (kinds NO_LONGER_NEEDED, SUPERSEDED, DUPLICATE, DISCARDED, and RISK_ACCEPTED, which is the chair's alone) and reopening within 90 days.
- **Overdue is derived**, not a status.

### A1: on the engine
- The committee type names a definition for subject `action-item`. Every tenant gets the built-in `action-item`:
  - the owner's review step, due at the end of the action's date, with a reminder 5 working days before and 5 after, and a notice to the holder's supervisor after 10;
  - then a verification step (APPROVE closes, SEND_BACK returns to the owner with a remark), with a reminder after 3 working days and the chair told after 10.
- **The engine already owns what an action needs:**
  - working-time timers and escalation (ADR-0041);
  - substitutes and out-of-office (ADR-0040);
  - a position re-resolved when its holder changes;
  - claims for a pool of secretaries;
  - SOD-2, which keeps whoever marked the action done from verifying it;
  - CEL branches on the action's attributes (priority, from a resolution, committee type);
  - the visual builder.
- **The engine needs four general additions** for a subject with its own dates and people (ADR-0068).
- **Who verifies:**
  - The verifier slot is the committee's secretaries not recused and not involved (or the chair, per type), with fallbacks.
  - The verifier is never the owner, the holder or a contributor.
  - "Close the actions the committee agreed" verifies a batch at the meeting.
- **Option A2 was rejected:**
  - It needs a second timer system, which duplicates ADR-0041 with the same calendar arithmetic.
  - It loses substitutes, holder changes and escalation by reporting line for the months an action is open, which is where reminders matter.
- **Option A3** is more code for less: the same rejection, plus no tenant-configurable closure path.

### B1: RAG
- **The colours:**
  - Red when past the date, or when the owner says "off track".
  - Amber when within `amberWorkingDays` of the date (default 10), when the owner says "at risk", or when there has been no update for `staleWorkingDays` (default 20).
  - Green otherwise.
  - Blue for "done, to be verified". Closed and cancelled actions have no colour.
  - An override by a secretary or the chair carries a reason, and lasts until a date or the next update.
- **Shown in words:** every colour carries words and an icon, and the key is printed.
- **Per committee type:** the thresholds sit on the committee type (`forum_type_version.actions`), reviewed by a second administrator like the rest of the type. Published logs define amber differently ("not due" in one, "due this month" in another), and a board and a credit committee chase at different speeds. The overview said "a tenant rule". The committee type is still tenant configuration, and this refinement is reported to the owner.
- **Computed from columns:** RAG comes from stored dates (`amberFrom`, `staleFrom`, computed with the business calendar when written) and today. Lists therefore filter and sort on it in SQL with no sweep.
- **Date changes:** per type, SECRETARY_AGREES (default), CHAIR_AGREES or OWNER_WITH_REASON. Every change keeps the original date and a reason, and re-plans the owner's timers.
- **Evidence** at closure is required per type (on in the Board Audit preset).

### C1: the action taken report
- **When it is built:** when a revision of a meeting's agenda that has a MATTERS_ARISING item is published, in the publishing transaction. It is frozen as the status at publication.
- **The sections, in order:**
  1. proposed to close;
  2. overdue;
  3. open;
  4. closed since the last meeting, for noting;
  5. cancelled since the last meeting, for noting ("risk accepted" in words);
  6. corrections to earlier minutes (E06).
  - The sections that need the meeting come first (RBI's "compliance crowding" point).
- **Rows:** each row snapshots what the meeting is shown and keeps the item columns, so every reader gets their rows by the one rule.
- **Curating:** a secretary may hold an action back from the next report with a reason, and officers see that rows were held back.
- **Rejected options:**
  - C2 cannot show what the meeting was shown.
  - C3 would put action text into the agenda's access model and multiply items.

### D1: follow-up memos
- **Raising one:**
  - The owner or a contributor raises a draft memo from the action, with a type chooser and the template fill as for any memo.
  - An existing memo the person can read may be linked instead.
  - The memo shows "Raised from action BRC A14/2026" through a memo port (memo never depends on actions), and its note sheet gets a system entry carrying the reference only.
- **When the memo is decided:**
  - The action records it, and the owner is told.
  - **The action is not closed automatically:** the overview's loop closes when the owner marks it done and the verifier, seeing the memo's outcome, verifies.
  - D2 would close actions whose memo was approved with changes the committee did not ask for.
- **D3 cannot express this:** the exit chain is memo → item → resolution → action → follow-up memo, and the action sits between the decision and the new memo.

### Visibility
- **One rule (`ActionAccess`):** people involved in an action (owner, holder, contributors, verifier pool) see it.
- **Everyone else:**
  - Officers and `forum.read` holders cleared for the action's level see it, and members in office see it when they see its item in full (ADR-0061's item rule, run on item columns copied onto the action).
  - Nobody recused from the item sees it or counts it unless involved.
  - Guests, item attendees and former members see nothing unless involved.
- **An owner outside the committee** sees the action and the context the secretary wrote, never the item's papers, and the decision only if E06's rule allows it.
- **Emails** carry the committee, the reference and the date, never the action's text.
- **Reminder and overdue emails** are combined into one daily digest per person. New actions, send-backs and escalations are emailed at once.

### Consequences
- Good:
  - Supervisors can be shown what was decided, who was to act, by when, every revised date with its reason, the evidence and who verified it.
  - The engine's proven machinery (timers, substitutes, holder changes, claims, separation of duties, the builder) serves actions without a second scheduler.
  - Tenants adapt the closure path by configuration.
  - Members and the chair see progress themselves, and the next meeting sees it in a fixed, printable report.
- Bad:
  - Four engine additions (ADR-0068) touch Phase 2's core. They are additive and optional, and the engine's suites and property tests run unchanged, but they need a careful first task.
  - Owner tasks stay open for weeks or months. The memo inbox ignores them because they are another subject, and "My actions" lists them.
  - Item columns are copied onto the action and must be refreshed when the source meeting publishes a later revision.
  - A large committee's report is capped at 500 rows.
- Follow-ups:
  - E08 adds actions to the cross-forum register and analytics (`ActionChanged`).
  - Moving an action to another committee is not in E07. It is a cancel (SUPERSEDED) plus a new action by someone who is an officer of both, or `forums.manage`.
  - External owners without accounts are not supported (AGENTS §3.4). A staff owner carries the action for them.

### Confirmation
- `ActionStateMachineTest`: every allowed and forbidden transition.
- `RagRuleProperties`: for random dates, calendars, confidences and overrides, the derived RAG matches the table, and SQL and Kotlin agree.
- `ActionWorkflowIT`: the built-in definition, reminders and escalation on a test clock, holder change, substitute, send-back, SOD-2.
- `ActionVisibilityIT` and the leak suite's E07 surfaces (the recused member, the owner outside the committee).
- `ActionReportIT`: frozen per revision, sections, held-back rows, per-reader rows.
- `FollowUpMemoIT`.
- The matrix `p4-e07-actions.yaml`.
- The exit-journey IT (`CommitteeCycleIT`) walking memo → item → resolution → action → follow-up memo and back.

## Pros and cons of the options
### A1 The whole action on the engine
- Good, because reminders, escalation by reporting line, substitutes, holder changes, pools and separation of duties are already built and tested.
- Good, because tenants change the path in the builder.
- Bad, because it needs engine additions (ADR-0068), and owner tasks are long-lived.

### A2 Closure on the engine, own timers
- Good, because there is no engine change for the owner's period.
- Bad, because it duplicates ADR-0041.
- Bad, because there is no substitute or holder handling while the action is open, and two places hold the action's due behaviour.
- Bad, because it still needs a subject-provided verifier (secretaries are a committee role, not an org role), so the engine changes anyway.

### A3 No engine
- Good, because it is fully self-contained.
- Bad, because it is the most code, and has no configurable path, no substitutes, and no escalation by reporting line.

### B2 One tenant rule
- Good, because it is simpler.
- Bad, because committees chase at different speeds, and practice differs.

### B3 RAG by hand
- Good, because it is what a spreadsheet log does.
- Bad, because it goes stale, it is subjective, and it cannot be filtered reliably.

### C2 A live view
- Good, because it is always current.
- Bad, because the meeting's record of what it was shown is lost.

### C3 Sub-items
- Good, because the agenda already handles items.
- Bad, because it is heavy, mixes action text into the agenda's access model, and the agenda freezes.

### D2 Automatic closure
- Good, because it means fewer clicks.
- Bad, because an approved memo is not proof that the committee's action was met.

## More information
- Design: `phase-4-forums-meetings/epic-07-action-items/design.md`. Threat model, tasks and test plan in the same folder.
- Sources (accessed 2026-11-21): CGI UK & Ireland, *The practice of minuting meetings* (2024); Basel Committee, *Corporate governance principles for banks* (2015); EBA/GL/2021/05; RBI/DOR/2025-26/249; ACCA's summary of IIA GIAS Standard 15.2; NHS and public-body action logs; BoardPro, Diligent, OnBoard and Board Intelligence documentation. URLs are in `research.md`.
