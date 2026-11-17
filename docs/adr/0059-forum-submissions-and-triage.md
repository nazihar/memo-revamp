---
status: accepted
date: 2026-11-18
deciders: Claude (agent) under the delegated gate (Phase 4 Q1, Q4); product owner aradnazihar
---

# 0059 — Forum submissions and secretary triage

## Context and problem statement
A memo that reaches a waiting forum step (ADR-0058) must arrive in the committee's hands and must not be lost: Legistar users keep spreadsheets because referred files do not reliably reach agendas. Phase 4 Q4 decided that direct submissions are memos. The secretary needs a queue to accept, defer, return or decline them; the submitter needs to see where their memo is; and the committee's officers need to read it. Research: `epic-02-submissions/research.md §3`.

## Decision drivers
- Nothing escalated is lost: every referral is a row with a status until it is decided, declined or withdrawn.
- One model for papers: memos (Q4). No second document store, numbering or access model.
- The submitter always sees who holds the item.
- Fixed meanings for statuses. Reasons are required where the submitter must act.

## Considered options
1. **A `forum_submission` row per referral, created from the escalation event; direct submissions as memos of a committee-paper type** (chosen).
2. A separate submission form and record with its own documents (rejected by Q4).
3. No submission object; the queue is a query over waiting workflow steps. This is simpler, but there would be no place for triage data, cover-sheet fields, deferrals or the history of referrals.

## Decision outcome
Chosen option 1.

- **The record:** `forum_submission` (forum module) holds:
  - the tenant, the forum, the memo, the workflow instance and the step;
  - `previous_id`, the earlier submission of the same memo (after a return) or the referring one;
  - the cover sheet:
    - purpose: DECISION, DISCUSSION or INFORMATION, defaulting to DECISION for a waiting step and INFORMATION for a pass-through one;
    - the resolution sought, optional, up to 2,000 characters;
    - a presenter, optional, defaulting to the initiator;
  - status, target date (optional), late flag, submitted time, and triage actor, time and reason;
  - `version`.
- **Statuses:**
  - QUEUED → ACCEPTED → DECIDED.
  - QUEUED or ACCEPTED → DEFERRED → QUEUED again (with a new target).
  - QUEUED or ACCEPTED → RETURNED or DECLINED (closing).
  - Any open status → WITHDRAWN (the memo was withdrawn or cancelled), or REFERRED (a new submission to another forum, linked).
  - Pass-through escalations ("for information", `wait: false`) create a submission too. It goes to the queue and never resumes anything; deciding it means "noted".
- **Creation:** a synchronous listener on `WorkflowEscalatedToForum`, inside the escalating transaction, so the step and the submission commit together. An escalation to a forum that is no longer active fails the action (409 `FORUM_INACTIVE`), and the submitter must choose another route.
- **Direct submissions** are memos of a tenant-configured type per committee (for example "Credit committee paper") whose workflow is review → `escalate-to-forum` with `wait: true` → end. Configuration only; the demo seeds one.
- **Triage** by the forum's secretaries (and holders of `forums.manage`):
  - **Accept**, optionally with a target date: the item is ready for an agenda (E03 places it on a meeting).
  - **Defer**, with a reason and an optional new target date.
  - **Return for more information**, with a reason: `ForumDecided(RETURNED)`.
  - **Decline**, with a reason: `ForumDeclined`.

  Each action is audited, added to the memo's note sheet as a system entry, and told to the initiator and the presenter.
- **Late flag:** the forum type's new submission policy `{deadlineWorkingDays: 5, lateMode: ALLOW | BLOCK | CHAIR_APPROVAL, triageWorkingDays: 5}` is stored on the version. A submission whose target date is closer than the deadline is flagged late. E03 enforces the late mode when an item goes onto an agenda.
- **Queue hygiene:** a QUEUED submission older than `triageWorkingDays` on the business calendar is flagged "waiting too long" and reminds the secretaries once (`FORUM_SUBMISSION_WAITING`).
- **Readers:** while a submission is open, the forum's chair and secretaries read the memo through a new `memo_participant` kind `FORUM`. The forum module asks the memo module's published `MemoReaders` API to add and retire these rows. Members gain access when the item is on a published agenda (E03).
- **The decision service:** `ForumDecisions.decide(submissionId, outcome, remark)` issues `ForumDecided` and closes the submission as DECIDED. In E02 it is used by tests and the demo; E06 calls it from recorded decisions.

### Consequences
- Good: a referral can never vanish. Submitters and secretaries see the same status; papers, numbering and access come from the memo.
- Bad: one committee-paper memo type per committee in tenants with many committees. A "committee" memo field type could replace this later.
- Follow-up: structured conditions and validity periods (EBA ¶196–198) come with decisions (E06) and actions (E07).

### Confirmation
- `ForumSubmissionsIT` covers creation in the escalating transaction, each triage action and its effect on the memo, readers added and retired, lateness, the waiting reminder, and withdrawal.
- Matrix `p4-e02-submissions.yaml`, the cross-tenant tests and the audit coverage test.

## Pros and cons of the options
- **Option 1:** + nothing lost, one paper model, reasons recorded; − one type per committee for direct papers.
- **Option 2:** + a tailored form; − a second document and access model (Q4).
- **Option 3:** + no table; − nowhere to keep triage, cover sheets or history.
