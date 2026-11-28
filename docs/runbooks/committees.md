# Committees: types, members and papers sent to them

**When:** setting up the Board and its committees, changing who sits on them, or answering "where is my paper?" and "why can't I see this memo?" for a memo sent to a committee. Background: ADR-0056 (committee types, committees and members), ADR-0057 (quorum), ADR-0058 (the memo waits for the committee) and ADR-0059 (submissions and triage). The screens are Administration → Committee types (`/admin/committee-types`; tabs Policy, Quorum, Outcomes, Terms, Meetings, Minutes, Actions and Changes) and Committees (`/committees`; a committee's tabs Members, Meetings, Submissions, Resolutions, Actions, History and Policy); the API is `/api/v1/admin/forum-types` and `/api/v1/forums`. Meetings, agendas, declarations and attendance are in [meetings-and-agendas.md](meetings-and-agendas.md); invitations in [meeting-invitations.md](meeting-invitations.md); decisions, resolutions and minutes in [minutes-and-resolutions.md](minutes-and-resolutions.md); actions in [action-items.md](action-items.md); the register and analytics in [decision-register-and-analytics.md](decision-register-and-analytics.md).

## Committee types (`forum.types.manage` drafts; `forum.types.approve` publishes)
- A type holds the policy every committee of that type follows:
  - default classification and member attributes;
  - the quorum rule, read back as a sentence;
  - the outcomes and their labels;
  - terms of office;
  - the **submission policy**.
- The submission policy has three settings:
  - **Papers due**: 0–30 working days before a meeting. The default is 5. Used by the agenda builder.
  - **Late papers**: allowed, held for the next meeting, or allowed only if the chair agrees (the default).
  - **Remind the secretaries after**: a paper has waited for triage for 1–30 working days (the default is 5). Working days come from the organisation's business calendar, in the tenant's time zone ([workflow-deadlines.md](workflow-deadlines.md)).
- Later epics added policies to the same version, each explained in its runbook:
  - **Meetings** (E05): the invitation's title or a neutral one, "Ask for replies by" (0–10 working days, default 2), the reply and meeting reminders, decisions without a quorum (`quorumOverride` NEVER or WITH_REASON) ([meeting-invitations.md](meeting-invitations.md));
  - **Minutes** (E06): approval at the next meeting or by circulation, circulation and draft days, when the memo resumes (`memoResumes`), the resolution number format and the minutes template ([minutes-and-resolutions.md](minutes-and-resolutions.md));
  - **Actions** (E07): the workflow (`definitionKey`, default `action-item`), who agrees new dates (`dueChange`), who verifies, evidence required, and the reminder days ([action-items.md](action-items.md)).
- Every change is a new version: a draft (`forum.types.manage`), submitted, then published or sent back by someone else holding `forum.types.approve`, with a step-up (`SAME_PERSON` for the drafter).
- **When a version applies:** a meeting pins the version published when the meeting is created, so meetings already created keep theirs (agenda template, paper deadline, late rule, minutes and actions policy). The triage reminder reads the version published now, so a new number of days applies at once.

## Committees and members (`forums.manage`; secretaries for ordinary members)
- A committee has a type, a code, a workflow key and, optionally, a parent ("reports to").
- The **workflow key** is what a workflow's "Escalate to committee" step names. Dissolving a committee makes those steps refuse to run (`FORUM_INACTIVE`), so change the workflow first.
- Members are people or positions ("whoever is the Chief Risk Officer"). Each has a role, a voting flag, attributes, an alternate and a term.
  - Renewing a term or changing a role adds a row. Ending one keeps the record.
  - A secretary may add and end ordinary members. Only an administrator appoints a chair or a secretary.
- The secretaries are reminded before a term ends: 42 days and 1 day before, by default (1–365 days, distinct; `FORUM_TERM_ENDING`, audit `forum.term.reminded`). Holders of `forums.manage` are reminded when a committee has no secretary.
- **Dates are the organisation's:** terms of office, "in office today" and the reminders count on the tenant's date (`default.timezone`, Organisation → calendar; set at provisioning, and UTC for a tenant without one). The organisation pages (positions, units, assignments) take their default dates and "Current" from the server's date (#253), as do the committee screens' dates, the actions', analytics', the dashboard's and the register's (from the session's `today`, Phase 4 close), so a browser in another zone cannot start an assignment on the wrong day. A position seat follows whoever holds the position on the meeting's date.
- **Background work:** the hourly `forum-term-reminders-sweep` schedules one `forum-term-reminders` per tenant, which sends both the term reminders and the triage reminders (at most 1,000 queued papers a run). Days are counted on the tenant's date (`default.timezone`).
- **Background work, queue readers:** the `forum-officer-readers-sweep` (every 15 minutes) schedules one `forum-officer-readers` per tenant. Once a day, at the first run after midnight in the tenant's zone, it resyncs the queue readers of every committee whose chair or secretaries changed by date since its last run (the day is kept in `forum_reader_sync`; the first run, or one after more than 31 days, resyncs every committee). A position's holder change applies at once.
- Refusals worth knowing: `ONE_CHAIR`, `ALREADY_MEMBER`, `SECRETARY_LIMIT` (a secretary changing a chair, a secretary or their own row), `MEMBER_ENDED`, `FORUM_DISSOLVED`; a meeting of a type with no published version is `TYPE_NOT_PUBLISHED`.

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
4. The paper is placed on a meeting's agenda, and the committee's decision there resumes the route, released by the `minutes-release` job at the meeting's end or when the minutes are approved (the type's `memoResumes`; [minutes-and-resolutions.md](minutes-and-resolutions.md), "Release"):
   - approved, approved with conditions or noted: the memo continues, with the resolution number on its note sheet;
   - rejected: the memo is rejected;
   - returned, or withdrawn at the meeting: the memo goes back to its author;
   - deferred: the paper goes back to the queue with its target, and the memo keeps waiting;
   - referred: a linked submission opens at the other committee, and the memo waits for that one.
5. When the paper leaves the queue, the officers' access to the memo is retired, unless they read it another way (approver, copy, share).

The demo's "Credit under authority" workflow sends credit proposals of BDT 10 million or more to the Management Credit Committee after the credit desk.

## "Where is my paper?"
- The memo's committee panel (`GET /api/v1/memos/{id}/forum-submissions`) lists every submission, newest first, with its status, reason and target date. It shows only committees within the reader's clearance.
- The secretaries triage on the committee page, tab **Submissions** (`/committees/{id}?tab=submissions`); a paper's own page is `/committees/{id}/submissions/{submissionId}`, where the notifications lead.
- A paper still queued after the type's triage days sends **one** reminder to the secretaries (`FORUM_SUBMISSION_WAITING`, audit `forum.submission.reminded`). If a committee has no secretary, holders of `forums.manage` get it instead.

## "Why can't I see this memo?"
- Committee members who are not chair or secretary do not read memos from the queue. They get them with the meeting's papers ([meetings-and-agendas.md](meetings-and-agendas.md)).
- An officer below the memo's classification cannot read it. The queue shows the paper as "restricted" (no number or subject) until they are cleared.
- An officer whose term started today reads the queue from shortly after midnight in the tenant's zone; one whose term ended yesterday no longer does. If that has not happened, check that `forum-officer-readers` ran for the tenant today (`forum_reader_sync.synced_on`) and that the tenant's zone is the one expected.
- Check the audit log: `memo.reader.forum_added` and `memo.reader.forum_retired` on the memo (the actor is `forum-officer-readers` for a change by date, and whoever changed the assignment for a new holder), `forum.member.*` on the committee, and `org.assignment.*` for a position seat.

## Audit actions
- Submissions: `forum.submission.created`, `.updated`, `.accepted`, `.deferred`, `.returned`, `.declined`, `.referred`, `.decided`, `.withdrawn`, `.reminded`, and `.placed` and `.unplaced` (on and off an agenda).
- Members: `forum.member.*`; term reminders `forum.term.reminded`.
- The memo's workflow: `workflow.forum.decided` and `workflow.forum.declined`.
- Committee readers: `memo.reader.forum_added` and `memo.reader.forum_retired`.
