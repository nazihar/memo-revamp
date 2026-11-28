# Committees: types, members and papers sent to them

**When:** setting up the Board and its committees, changing who sits on them, or answering "where is my paper?" and "why can't I see this memo?" for a memo sent to a committee. Background: ADR-0056 (committee types, committees and members), ADR-0057 (quorum), ADR-0058 (the memo waits for the committee) and ADR-0059 (submissions and triage). The screens arrive with Phase 4 E01 T4 and T5 and E02 T4. Until then these steps go through the API (`/api/v1/admin/forum-types`, `/api/v1/forums`). Meetings, agendas and papers for members are in [meetings-and-agendas.md](meetings-and-agendas.md).

## Committee types (administrators with `forum.types.manage`; a second person approves)
- A type holds the policy every committee of that type follows:
  - default classification and member attributes;
  - the quorum rule, read back as a sentence;
  - the outcomes and their labels;
  - terms of office;
  - the **submission policy**.
- The submission policy has three settings:
  - **Papers due**: 0–30 working days before a meeting. The default is 5. Used for agendas from E03.
  - **Late papers**: allowed, held for the next meeting, or allowed only if the chair agrees (the default). Used from E03.
  - **Remind the secretaries**: when a paper has waited for triage for 1–30 working days (the default is 5). Working days come from the business calendar ([workflow-deadlines.md](workflow-deadlines.md)).
- Every change is a new version: a draft, then review, then publication by someone else (`SAME_PERSON` otherwise). A published version applies from the committee's next meeting.

## Committees and members (`forums.manage`; secretaries for ordinary members)
- A committee has a type, a code, a workflow key and, optionally, a parent ("reports to").
- The **workflow key** is what a workflow's "Escalate to committee" step names. Dissolving a committee makes those steps refuse to run (`FORUM_INACTIVE`), so change the workflow first.
- Members are people or positions ("whoever is the Chief Risk Officer"). Each has a role, a voting flag, attributes, an alternate and a term.
  - Renewing a term or changing a role adds a row. Ending one keeps the record.
  - A secretary may add and end ordinary members. Only an administrator appoints a chair or a secretary.
- The secretaries are reminded before a term ends: 42 days and 1 day before, by default.
- **Background work:** the `forum-officer-readers-sweep` (every 15 minutes) schedules one `forum-officer-readers` per tenant. Once a day, at the first run after midnight in the tenant's zone, it resyncs the queue readers of every committee whose chair or secretaries changed by date since its last run (the day is kept in `forum_reader_sync`; the first run, or one after more than 31 days, resyncs every committee). A position's holder change applies at once.

## A memo sent to a committee
1. A workflow step `escalate-to-forum` with `"wait": true` holds the memo. The step's `forum` is the committee's workflow key.
   - The memo shows **With the Management Credit Committee** in its status and in the author's Sent box.
   - Nobody's inbox has a task for it.
2. The committee's queue gets a **submission** in the same transaction, and the secretaries are told.
   - While the paper is open, the chair and secretaries in office can read the memo, within their clearance.
   - Access follows the chair and secretaries in office on the organisation's date, with a position seat resolved to whoever holds the position:
     - appointed or ended through the Members tab: at once;
     - a new holder of a position seat (Organisation → positions or assignments): at once, in the same change; the previous holder stops reading;
     - by date (a term that lapses on its planned end, an appointment or a holder change recorded for a later day): at the start of that day in the tenant's zone (`default.timezone`), within 15 minutes of midnight, by the `forum-officer-readers` task.
3. The secretary triages the paper:
   - **Accept**, with an optional target meeting date.
   - **Defer**, with a reason.
   - **Return for more information**, with a reason. The memo goes back to its author, and the note sheet says "Returned by the Management Credit Committee: reason".
   - **Decline**, with a reason. The memo goes back to its author.
   - **Refer** to another committee, with a reason. The paper moves, and the memo keeps waiting.
4. The committee's decision resumes the route:
   - approved, approved with conditions or noted: the memo continues;
   - rejected: the memo is rejected;
   - returned: the memo goes back to its author.

   Until minutes arrive (E06), decisions are recorded by the service the tests and the demo use.
5. When the paper leaves the queue, the officers' access to the memo is retired, unless they read it another way (approver, copy, share).

The demo's "Credit under authority" workflow sends credit proposals of BDT 10 million or more to the Management Credit Committee after the credit desk.

## "Where is my paper?"
- The memo's committee panel (`GET /api/v1/memos/{id}/forum-submissions`) lists every submission, newest first, with its status, reason and target date. It shows only committees within the reader's clearance.
- The secretaries triage on the committee page, tab **Submissions** (`/committees/{id}?tab=submissions`); a paper's own page is `/committees/{id}/submissions/{submissionId}`, where the notifications lead.
- A paper still queued after the type's triage days sends **one** reminder to the secretaries (`FORUM_SUBMISSION_WAITING`, audit `forum.submission.reminded`). If a committee has no secretary, holders of `forums.manage` get it instead.

## "Why can't I see this memo?"
- Committee members who are not chair or secretary do not read memos from the queue. They get them with the meeting's papers (E03).
- An officer below the memo's classification cannot read it. The queue shows the paper as "restricted" (no number or subject) until they are cleared.
- An officer whose term started today reads the queue from shortly after midnight in the tenant's zone; one whose term ended yesterday no longer does. If that has not happened, check that `forum-officer-readers` ran for the tenant today (`forum_reader_sync.synced_on`) and that the tenant's zone is the one expected.
- Check the audit log: `memo.reader.forum_added` and `memo.reader.forum_retired` on the memo (the actor is `forum-officer-readers` for a change by date, and whoever changed the assignment for a new holder), `forum.member.*` on the committee, and `org.assignment.*` for a position seat.

## Audit actions
- Submissions: `forum.submission.created`, `.updated`, `.accepted`, `.deferred`, `.returned`, `.declined`, `.referred`, `.decided`, `.withdrawn` and `.reminded`.
- The memo's workflow: `workflow.forum.decided` and `workflow.forum.declined`.
- Committee readers: `memo.reader.forum_added` and `memo.reader.forum_retired`.
