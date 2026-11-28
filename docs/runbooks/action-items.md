# Action items

**When:** a committee's secretary raises, sends, re-dates, cancels or closes actions, or someone asks "why didn't the owner get a reminder?", "why did the CRO get an email about an action?", "why can't I change this date?", "why is this action missing from the action taken report?", "why can't I close it?" or "why is there no evidence?". Background: ADR-0067 (action items), ADR-0068 (the engine's subject slots and due times), ADR-0041 (timers and SLAs), ADR-0062 (recusal), Phase 4 E07 `design.md`. Decisions and resolutions are in [minutes-and-resolutions.md](minutes-and-resolutions.md); meetings and agendas in [meetings-and-agendas.md](meetings-and-agendas.md); committee types in [committees.md](committees.md); the engine's deadlines in [workflow-deadlines.md](workflow-deadlines.md).

## Who does what

| Who | Can |
| --- | --- |
| The committee's secretaries in office, and holders of `forums.manage` | Raise actions (from a decision or one of its conditions, an item or the meeting while it runs, is held or minuted; directly on the tracker), edit and send drafts, change the owner or the date with a reason, set the colour by hand, keep an action out of the next report, record an update for the owner, cancel, reopen within 90 days, ask owners for an update, export the tracker, verify (unless they own the action or are involved in it) |
| A secretary who declared an interest in the source item | Nothing on that item's actions (403 `RECUSED`); another secretary acts |
| The chair in office (the vice-chair when the chair is recused or absent) | Cancel with the risk accepted; set the colour; verify when the committee type says `verifiedBy` CHAIR; agree new dates under `CHAIR_AGREES`; told when verification is late |
| The owner (a person, or whoever holds the owner position today, or their substitute) | Post updates with how it is going and evidence, "I have started", ask for a new date (or change it under `OWNER_WITH_REASON`), raise or link follow-up memos, mark it as done |
| People also involved (up to five) | Post updates and evidence, raise or link follow-up memos; never mark it as done or verify it |
| Members, `forum.read` holders | The committee's tracker rows and the action taken report, without the officers' buttons and without the actions of items they declared an interest in |

Everyone else gets 404. An owner outside the committee sees their own action and what the secretary wrote for them ("What the owner is told"), never the item's papers, the vote or the discussion. A recused member sees no row, count or report line of the item's actions; if they own one, they see it, with the item's number only.

## The definition and its checks

- Each committee type names the workflow its actions run on (Actions tab, `definitionKey`, default **`action-item`**). Every tenant has the built-in `action-item` definition, seeded at tenant creation and at start-up (published version 1), editable in the builder as new versions with maker-checker.
- **The built-in definition:** the owner's step ("Owner"): reminded 5 working days before the due date and 5 after, escalated 10 working days after (notify); then the verification step ("Verification", the `verifier` slot): due 5 working days after the action was marked done, reminded 3 working days after that, escalated to the chair 10 after its due time.
- **The definition check** refuses an `action-item` definition that does not start with a human step on the `owner` slot due from the subject (`ACTION_START_WITH_OWNER`), offers REJECT or RETURN (`ACTION_NOT_REJECTED`: an action is sent back, never rejected), has no later human step (`ACTION_VERIFIED`: closing is always verified by someone else), or offers FORWARD or DELEGATE on the owner's step (`ACTION_OWNER_KEEPS`). The builder shows the problems at the steps; the committee type cannot name an unbound or unpublished definition (422 `UNKNOWN_DEFINITION`), and a definition a type names cannot be retired (`IN_USE`).
- **A change** to the definition applies to actions issued or reopened afterwards; running actions keep the version they started on. A change to the committee type's actions policy applies to open actions from their next write (amber and "no recent update" days) and, for `verifiedBy`, from the next verification.
- **Tenant variations** are configuration: a branch on `action.priority == "HIGH"`, a chair's step for actions from resolutions (`action.fromResolution`), other reminder times. Never code.

## Reminders and the digest

| When (working days, the tenant's business calendar) | Who hears | How |
| --- | --- | --- |
| 5 before the due date | The holder | In the app at once (`ACTION_DUE_SOON`); by email in the next digest |
| At the end of the due date | The holder and the secretaries not recused | In the app at once (`ACTION_OVERDUE`); email in the digest |
| 5 after | The holder | In the app; digest |
| 10 after | The holder's supervisor; the secretaries | Escalation, by email **at once** (below) |
| When it is marked done | The verifiers | In the app (`ACTION_TO_VERIFY`) |
| 5 after it was marked done (the verification's due time), and 3 after that | The verifiers | In the app (`ACTION_TO_VERIFY`); digest |
| 10 after the verification's due time (about 15 after done) | The chair | Escalation by email at once |

- **The digest** (`actions-daily-tenants`, hourly, schedules one `actions-daily` per active tenant; each acts from 09:00 in the tenant's zone on its working days): one `ACTIONS_DIGEST` email per person per day, one line per kind of news with its references, the most pressing first: "Overdue: BRC A14/2026, MCC A3/2026", "Due soon: …", "To verify: …", "Due dates asked for, decided or changed: …", "Closed", "Cancelled", "Reopened", "Other news"; at most 10 references a line, then "and n more". Never a title. Nothing on weekends and holidays; a person with nothing new gets nothing. It reads the person's in-app action notices since their last digest (at most 14 days back), so a person who turned off in-app workflow notices gets no digest. Running twice sends nothing twice (`actions_digest` keys).
- **How the daily task batches** (since E08 T5): one query reads the day's notices (at most 200,000); at most **5,000 people a run**, the rest on the next hourly run; the digest keys are written 1,000 a statement; recipients' preferences are read 1,000 a query; outbox rows are written 500 a statement and their deliveries scheduled a chunk at a time (`Notifications.notifyAll`, `Mail.enqueueAll`). Measured: 5,000 digests in 7.7 s and 7,857 statements (27.6–31.9 s and 16,900 before), against a 30 s budget (`ActionsVolumeIT`, weekly `load`, job `actions-volume`). The day's first run also runs the safety net (below) and the update requests.
- New actions, send-backs, escalations, a change of owner, a risk accepted and a request for an update are emailed at once. No email subject carries a parameter, and no email carries an action's text.
- **"Why didn't they get a reminder?"** Check: the action is issued (a draft has no workflow); the due date and the business calendar (a holiday added after the action was written moves nothing until its date is changed or a new round starts, a send-back or a reopen; a progress update does not plan the reminders again); the workflow instance is running (the safety net below); the person's notification preferences; the mail outbox for `ACTIONS_DIGEST` the next working morning.

## Escalation

- 10 working days after the due date the engine escalates (`notify`): the **holder's supervisor by the reporting line** (the holder of the position the owner's position reports to) gets `ACTION_ESCALATED` in the app and by email at once, with the reference, the owner, the holder and how many working days it is overdue; the secretaries are told in the app. The holder keeps the action.
- **The supervisor is told, not given access.** The link opens the action for someone with a seat on the committee and the inbox for others.
- No supervisor (a top position, or a vacant one): nobody is escalated to, and the secretaries are still told. Fix the reporting line in the organisation if that is wrong.
- At the next meeting the action is in "Overdue" on the action taken report: that is the escalation to the committee.
- The audit has `workflow.task.escalated` for the owner's task; the action's timeline does not change.

## Re-dating

- The committee type's `dueChange` decides:
  - `SECRETARY_AGREES` (default; the board and board committee presets): the owner asks with a reason; until a secretary agrees, the action keeps its date and shows "New date requested: …; until it is agreed the action stays due …". One request at a time (409 `PENDING_REQUEST`); marking done or cancelling withdraws it.
  - `CHAIR_AGREES`: the same, answered by the chair (or the vice-chair).
  - `OWNER_WITH_REASON` (the management committee presets): the owner changes the date with a reason; the secretaries are told.
- A secretary not recused may always change the date directly, with a reason.
- Every change keeps the original date and counts the revisions: "Due 20 January 2027 (originally 15 December 2026, revised once)" on the action, "(rev. 1)" on the tracker, and both on the report. The owner's reminders and the escalation are planned again from the new date in the same transaction (`Workflows.redue`).
- A date in the past is refused (422 `DUE_IN_PAST`), for a new action, a new date and an update's "until". **"Today" is the organisation's**, in the tenant's time zone (`default.timezone`; the demo tenant is on Asia/Dhaka), as are "overdue" and "Ask owners for an update" once a day. The date picker's earliest day and its quick choices use the browser's date (known limit): near midnight, someone in another zone can pick a day the server refuses as past, or be kept from today. To record that an action was due earlier, leave the date and let it show as overdue.

## Risk accepted

- "Cancel the action" with the kind **Risk accepted by the chair** is the chair's alone. A secretary may record it for the chair, saying how the chair told them (422 `REQUIRED` at `how` without it); the timeline says it was recorded for the chair, and the chair is told at once.
- The other kinds: no longer needed, replaced by another action of the committee (named), a duplicate. Every cancel needs a reason; the owner and the people involved are told; the workflow is cancelled and a pending date request withdrawn.
- The report lists it under "Cancelled since the last meeting, for noting" with "risk accepted". It can be reopened within 90 days (a new workflow run with a new date); after that, raise a new action (409 `TOO_LATE`).

## The action taken report and held-back rows

- **Built** when an agenda revision with a "Matters arising" item is published, in the same transaction, and **frozen**: a later revision gets a report of its own, and the earlier one never changes. The item shows "Action taken report (as published on …)" with each reader's counts and "Open the report"; "Print" gives the reader's rows as a PDF.
- **Sections:** proposed to close (done, to be verified), overdue, open (by due date), closed since the last meeting, cancelled since the last meeting, corrections to earlier minutes. "Since the last meeting" is from the start of the committee's latest meeting that took place before this one. At most 500 rows; then "and n more open actions; see the committee's tracker".
- **Each reader sees their own rows:** a recused member none of the item's; follow-up memos only those the reader may open, as they stand now (said so on the screen).
- **Held back:** a secretary may keep an action out of the next meeting's reports ("Keep out of the next report", a reason). It stays open on the tracker; officers see "1 action held back by the secretary" on the report. It applies to reports built afterwards: to change a published report, publish a new revision.
- **"Why is this action missing?"** It was held back; it is another committee's; it was closed or cancelled before the last meeting started; the reader declared an interest in its item; or the report was built before the action changed (open the latest revision's report, or publish a new revision).
- **Asking for updates:** owners of open actions with no update since the last meeting are asked `updateRequestWorkingDays` (default 5) before the next meeting's paper deadline, once per meeting; "Ask owners for an update" on the tracker asks at once, once a day per committee (409 `ALREADY_ASKED`).
- **Closing at the meeting:** on the report of a meeting that runs or is held, "Close the actions the committee agreed" closes the ticked ones, each with "Agreed at {meeting}, item {n}" unless a remark is given; the ones the officer may not close (they own one, or are involved) are listed with why.

## Evidence

- Files on an update or on "Mark as done", up to 10 per entry, purpose `action.evidence`: scanned by ClamAV and typed by content before they are stored (25 MiB each), encrypted per tenant, stored under random keys and served only to those who see the action, with `Content-Disposition` and strict headers. See [files-and-malware-scanning.md](files-and-malware-scanning.md).
- A committee type with `evidenceRequired` (the Board Audit preset) refuses "Mark as done" without a file on that call (422 `EVIDENCE_REQUIRED`); files posted with earlier updates do not count. "Mark as done" says so before sending.
- The verifier sees what was done, the evidence and the follow-up memos with their status before closing or sending back (a remark is required to send back).
- Entries are never edited or deleted: a wrong update is corrected by a new one pointing at it; a wrong file stays on the timeline with the correction saying why.

## Follow-up memos

- The owner or a person involved (with `memo.create`) raises a draft memo from an open action; anyone involved or a secretary links an existing memo they may read (at most 10 per action). The memo's note sheet says "Raised from action BRC A1/2026 (Board Risk Committee)" (the reference only), and the memo page links back for those who see the action.
- When the memo is decided, the action's timeline records it and the owner is told they can mark the action as done. **The action is never closed by its memo**: the verifier closes it with the memo's outcome in view.

## What operations check

- **Daily:** the `actions-daily` task ran for each tenant on its working day (audit `actions.digest.sent` with the people and whether the day is complete, written only when some were sent; a day with more than 5,000 people finishes on the next hourly runs; db-scheduler's `actions-daily` executions without failures). A tenant with no actions sends nothing.
- **The safety net** (the day's first run): `actions.action.not_running` (an issued action without a running workflow, reported once to the secretaries: look at the workflow logs for a failed start, then reopen or raise again), `actions.action.reconciled` (the action took the engine's state), `actions.timers.replanned` (an owner's step due at another time than the action's date was planned again). More than a handful a day means something is changing actions or workflows outside the services: investigate.
- **Timers:** `workflow_timer` rows of `action-item` instances in PENDING past their `fires_at` by more than a few minutes mean db-scheduler is not running them (see [workflow-deadlines.md](workflow-deadlines.md)).
- **Mail:** the outbox for `ACTIONS_DIGEST` around 09:00 tenant time, and `ACTION_ESCALATED` at once; a bounce for a supervisor is an organisation-data problem (their email), not the action's.
- **Exports:** the tracker's XLSX export (at most 5,000 rows) is rate-limited (10 an hour per person, 429) and audited `actions.tracker.exported` with the filter and the count; a cell that would start a formula is written with a leading apostrophe.
- **Performance:** "My actions" 150 ms, the tracker 200 ms (300 ms with text), the report build 400 ms and read 150 ms, its PDF 2 s, the daily task 30 s per 5,000 digests, measured 7.7 s (`ActionsVolumeIT`, weekly in `load`, job `actions-volume`). Analytics reads the same actions for its overdue and closed-on-time figures ([decision-register-and-analytics.md](decision-register-and-analytics.md)).
- **Never** change an action's rows by hand: the original date, the reference and the source are protected by triggers, entries are insert-only for the app role, and the reports are frozen records. Corrections go through the screens, with a reason.

## Demo data

The demo tenant (`DemoActionsSeeder`, Phase 4 E07 T12; its zone is Asia/Dhaka, so the digest goes at 09:00 Dhaka time) has, on E06's held meetings: **MCC A1** from the first condition of MCC/…/001, owned by the position Head of Credit Risk (Laila Arjumand, a demo account), with an approved follow-up memo, closed with evidence; **BRC A1** from "Any other business", Demo Director's, done and waiting for verification; **BRC A2** from the Rupsha Steel item (Demo Director stepped out, so never sees it), Head of Credit Risk's, overdue and escalated to the Chief Risk Officer; **BRC A3** direct, Demo Head of Finance's, with a date asked for and agreed; **BRC A4** direct, cancelled with the risk accepted by the chair. The quarterly risk review's third revision has the action taken report with them. Seeders only fill gaps: on a long-lived environment, re-run the journeys after a seed change.
