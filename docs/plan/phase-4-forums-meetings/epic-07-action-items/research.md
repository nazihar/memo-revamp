# E07 Action items — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-21 unless stated. Phase research items covered here: R1 (versions of what E07 touches) and R7 (action tracking: RAG rules, closure verification, matters arising, follow-up of overdue actions). It also covers what supervisors expect, how board portals track actions, what their users criticise, and usability for older directors. Sources marked **[snippet]** were read only from a search result: the site blocked automated reads (403), refused the connection, timed out, or is members-only. Everything else was opened and read. A helper session collected the sources. The agent re-read the RBI direction (§3.3), the strongest regulatory anchor, before relying on it.

## 1. Starting point (code and plans, 2026-11-21, `master` at 0939bfd)

### 1.1 Module and agenda
- **`actions` module:** an empty Spring Modulith module (`com.memo.actions`). It has no migration folder.
- **The leak suite** already lists the module among those whose controllers must all be registered (`PaperSurfaceRegistry.modules`). It also holds an E07 placeholder: "Actions from the item: not shown to a recused person unless they own the action", on `/api/v1/actions`, `/api/v1/me/actions` and `/api/v1/meetings/{meetingId}/actions`. The meta-test fails once an endpoint under those paths exists while the placeholder is still there.
- **Matters arising (E03):** `StandingKind.MATTERS_ARISING` exists in the agenda policy (`forum/AgendaPolicy.kt`). Every preset agenda template has a standing item "Matters arising and actions", for noting. Nothing fills it yet.
- **Meeting events (E03–E05):**
  - `MeetingChanged(ENDED, …)` and `AgendaPublished(tenantId, meetingId, revision)` are published in the changing transaction, "for E04–E07".
  - `Recusals.isRecused`, `recusedFrom` and `ofMeeting` are documented as being for "E07's actions".
  - `PaperAccess` holds the one item rule (`itemCondition`, `lineCondition`, `titleCondition`).

### 1.2 The workflow engine (Phase 2, ADR-0034 to ADR-0042)
- **Subjects:** any module routes its items by implementing `WorkflowSubject`: eligibility, snapshot, attributes for CEL, `interested()` for SOD-4, summaries, `lock()` (the subject's rows are locked before the instance's) and `beforeAction`.
- **Steps:** review (positive action RECOMMEND), approve, parallel, route, DoA, branch, notify, subworkflow, escalate-to-forum.
- **Actions:** APPROVE, RECOMMEND, RETURN, REJECT, SEND_BACK (to the previous step), FORWARD, DELEGATE, ASK, REFER.
- **Assignees:** person, initiator (notify only), position (re-resolved when the holder changes: `StaffChanges.reresolve`), supervisor, unit head, role, queue.
- **Separation of duties:** SOD-1 excludes the initiator from every step. SOD-2 excludes anyone who already approved or recommended in the round, unless the tenant switched it off. SOD-4 excludes interested people.
- **Timers (ADR-0041):**
  - A step's `sla.due` is an ISO 8601 duration counted from the task's creation, in working time on the tenant's business calendar.
  - Reminders: one `before` and up to three `after`. Escalation: `notify`, `reassign` or `nextLevel` (the holder's supervisor).
  - One `workflow_timer` row and one db-scheduler execution per timer. A task that moves keeps its due time.
  - `WorkflowTimerFired` (REMIND_BEFORE, DUE, REMIND_AFTER) and `WorkflowEscalated` tell the subject's module, which notifies.
  - **There is no absolute due time.** Nothing can move a running task's due time, and nothing lets a subject name who a step goes to.
- **Definitions:** versioned and tenant-editable in the builder. They are not bound to a subject type. Every tenant has the built-in `sequential-approval`.
- **Published helpers:** `WorkingDays` (working days since or before a time, on the tenant calendar). `WorkflowHandOns.sendBack`, `claim` and `release`.

### 1.3 Memo, notifications and the minutes module
- **Memo (Phase 3):**
  - A memo is created by `POST /api/v1/memos` (subject, type, classification, metadata). A type with a template fills the document after the commit (ADR-0048).
  - Links between memos are `REFERS_TO`, `SUPERSEDES` and `FOLLOWS_UP` (ADR-0043, `memo.internal.MemoLinks`). They join memo to memo only.
  - `MemoReaders.canRead` and `readable(ids)` give other modules the memo read rule (ADR-0050).
  - No memo-creation API is published to other modules.
- **Notifications (ADR-0030):**
  - Kinds are an enum with a category. Email subjects take no parameters. `TASK_REMINDER` and `TASK_OVERDUE` exist for memo tasks.
  - The WATCHING category already delivers a digest (`MemoWatchDelivery`, one per hour of quiet). This is the precedent for batching.
- **Minutes (E06):**
  - Built: T1, the content-control spike and `common.numbering`. T2, the committee type's minutes policy `forum/V7`, `ForumDecisions` and `WorkflowForums.decided(…, details)`.
  - Not built: T3 onward. That includes decisions (`minutes/V1`), resolutions, `Decisions.of`, `DecisionRecorded`, `MinutesCorrections.since` and the minutes template's item loop.
  - E07 is therefore designed against E06's published plan (design §1.1, §2) and lists what it needs from E06 (design §11).
- **Migrations:**
  - `master` holds `forum/V7`, `meeting/V9`, `memo/V20`, `workflow/V16`, `notification/V4` and `search/V3`.
  - Open #203 (E05 T5) holds `meeting/V10`.
  - E06 plans `minutes/V1`–`V4`.
  - **E07 takes `forum/V8` and its own `actions/V1`–`V4`, and no meeting, memo or workflow migration** (design §7).

## 2. Versions (R1)
| Item | Finding | Source |
|---|---|---|
| Spring Boot | **4.2.0 GA was released on 19 November 2026**, with 4.1.3 and 4.0.10 the same day. The repository is on 4.1.1. Nothing in E07 needs 4.2. The upgrade is a dependency PR of its own (Renovate) and stays in the follow-ups of `STATUS.md` (the "Spring Boot 4.2 GA" item). Changing the framework inside a feature epic would mix two risks | <https://github.com/spring-projects/spring-boot/releases> |
| db-scheduler | 16.12.0, pinned. E07 uses it only through the workflow timers and one daily task per tenant | `gradle/libs.versions.toml` |
| Apache POI (SXSSF) | 6.0.0, pinned (Phase 3). E07 uses it for the tracker's XLSX export, with the streaming writer the Phase 3 legacy import already uses for its reconciliation workbook | `gradle/libs.versions.toml` |
| PDFBox | 3.0.8, pinned. E07 prints the action taken report through the memo module's published `ReportPages` | `docs/dependencies.md` |

**E07 adds no new library and no new service** (overview risk R9: the dev VM is full).

## 3. Action tracking in committee practice (R7)

### 3.1 The action log is a separate document that goes to the next meeting
- **CGI UK & Ireland, *The practice of minuting meetings*, 2nd edition (2024), read in full:**
  - "Many respondents maintain a separate actions schedule for details, action owner, deadline, etc. along with status. An updated actions schedule is presented to each meeting as part of the board pack and often discussed under 'matters arising'."
  - Minutes should make clear "who has responsibility for the action and the date by which it should be completed where a deadline has been agreed".
  - The secretary should "apply a materiality test", so only board-level actions are recorded.
  - It is common "to refer to individuals, particularly executives, by their title rather than their name".
  - Errors found after the minutes are approved are dealt with under matters arising at the next meeting, not by editing the minutes.
  - <https://www.cgi.org.uk/assets/files/free-guidance-notes/2024/the-practice-of-minuting-meetings-guidance-note-2nd-edition-1.pdf>
- **CGI factsheet, *What are minutes and why do we need them*:** "Assign specific actions to individuals with clear deadlines for follow-up." <https://cgi.org.uk/media/io3naipz/cgiuki-factsheet-what-are-minutes-and-why-do-we-need-them.pdf>
- **University of Manchester governance handbook:**
  - Actions belong in the minutes and in "a separate Matters Arising/Action Log".
  - The target date is "normally" the next meeting.
  - Actions should go out as soon as possible after the meeting.
  - <https://www.staffnet.manchester.ac.uk/governance/handbook/minutes-actions/>
- **Loughborough University committee handbook, §21:**
  - The action list, "also known as a matters arising summary", "clearly designates responsibility to named individuals" and "can also be used as a formal paper to the next meeting".
  - The secretary must tell people outside the committee who are given an action.
  - <https://lboro.ac.uk/committees/handbook/21-actions/>
- **ICSI (India), *Guidance Note on Meetings of the Board of Directors* (October 2017):**
  - The illustrative agenda has "To take note of the action taken in respect of the earlier decisions of the Board" and "To consider matters arising out of the Minutes of the previous Meeting".
  - The model minutes record an "Action Taken Report — The following action taken was noted by the Board", in a table of item number, item and action taken.
  - Items carried forward cite the source: "(Refer to Item No. 18 of the Minutes of the Meeting held on…)".
  - <https://www.independentdirectorsdatabank.in/img/partners/617fb17c6b2a6.pdf>
  - **Correction to the brief:** the 2015 text of Secretarial Standard SS-1 has no "action taken report" wording (searched in full, <https://ca2013.com/wp-content/uploads/2015/09/SS1-MEETINGSOFBOARDSOFDIRECTORS.pdf>). The report comes from the Guidance Note and from RBI (§3.3). The 2024 SS-1 revision could not be opened (icsi.edu refused connections), so this plan does not cite SS-1 for it.

**Adopted:**
- The forum's action log is a record of its own, apart from the minutes (the .docx).
- It is presented to the next meeting under the standing "Matters arising and actions" item, as an **action taken report** whose rows point back to their source ("BRC 3/2026, item 5").
- The minutes list each item's actions (an E06 template loop, design §11).
- Owners may be people outside the committee, and positions are shown by title first ("Head of Treasury (A. Karim)").
- The default due date is "by the next meeting".
- Actions go to their owners when the secretary issues them, which by default is when the meeting is marked as held.

### 3.2 Published action logs: columns, RAG keys and closure
- **Tourism NI board action points template:**
  - Columns: action point number, date raised, agenda topic, action, owner(s), when, current status.
  - Its stated purpose is "a single source for all board outstanding action items".
  - <https://www.tourismni.com/globalassets/business-development/support-by-topic/financial-support-new/corporate-governance/board-meetings-issues-log-template.pdf>
- **Shrewsbury and Telford NHS Trust board action log (2020):**
  - Columns: meeting date, minute reference, private or public, agenda item, action, responsible officer (a job title), target date, update, status, RAG.
  - RAG key:
    - GREEN "Complete / recommended to close";
    - AMBER "Incomplete but within due date for delivery / Not due";
    - RED "Not completed within date for delivery";
    - BLUE "Report to be provided at meeting / on agenda".
  - Revised target dates are kept stacked in the cell, so slippage stays visible.
  - <https://www.sath.nhs.uk/wp-content/uploads/2020/07/Action-Log-1.pdf>
  - A later log of the same trust separates "completed and closed at BoD meeting" from "complete / recommended to be closed at next BoD meeting". <https://www.sath.nhs.uk/wp-content/uploads/2020/12/04-Action-Log-1.pdf> **[snippet]**
- **Calderdale and Huddersfield NHS FT action log (2017):**
  - Key: Red "Overdue", Amber "Due this month", Green "Closed", Blue "Going forward".
  - It has a "Date actioned & closed" column.
  - <https://www.cht.nhs.uk/fileadmin/site_setup/contentUploads/About_us/Publications/BoardPapers/BOD_2017/2017_July/APP_C2_-_ACTION_LOG_-_Appendix_-_DRAFT_ACTION_LOG_-_BOD_-_PUBLIC_-_As_at_1_AUGUST_2017.pdf>
- **Scottish Police Authority rolling action log:**
  - Action numbers carry the meeting date (20230323-BM-001), and owners are job titles.
  - It shows the expected date of completion and the actual date of closure separately.
  - Updates are dated entries, many ending "Propose to close".
  - <https://www.spa.police.uk/spa-media/23uevh3r/item-2-2-spa-public-board-rolling-action-log.pdf>
- **Belfast HSC Trust action log cover sheet (July 2025):** the board is asked to "approve the green actions as closed". <https://belfasttrust.hscni.net/download/792/03-july-2025/23739/p135-2025-a-tb-public-3-july-cover-sheet-for-agenda-item-6-action-log.pdf>

**What the logs agree on, and where they differ:**
- **Columns:** a stable reference, the source meeting and minute, the action, a single responsible officer (usually a title), the target date with its revisions, dated updates, status and RAG.
- **Closure has two steps:** the owner proposes ("recommended to close"), and someone else confirms (the secretary, chair or meeting).
- **RAG meanings differ:**
  - green is "complete" in one log and "closed" in another;
  - amber is "not due" in one and "due this month" in another;
  - red is always "past its date".
- Logs usually print their key.

**Adopted:**
- **Red is always "past its due date, not done"** (every log agrees).
- **Amber is "due soon" by a window in working days, set per committee type**, or "at risk" by the owner's word, or "no update for a while".
- **Green is "on track"**, the most common reading in software (iBabs, §5).
- Done-but-not-verified gets its own colour and words ("Done, to be verified", blue in the NHS logs). Closed actions are not coloured.
- Every coloured status carries its words and an icon, and the key is printed on the tracker and the report (§7: colour alone fails older users and WCAG 1.4.1).
- The due date keeps its history: the original date, a revision count and each change with its reason.
- Updates are a dated log, never one overwritten field.
- The report has a "proposed to close" section the meeting can close in one step.

### 3.3 What supervisors expect
- **Basel Committee, *Corporate governance principles for banks* (2015), read in full:**
  - ¶46: the board "should hold members of senior management accountable for their actions".
  - ¶60: the board keeps "appropriate records (eg meeting minutes or summaries of matters reviewed, recommendations made, decisions taken and dissenting opinions)", "made available to the supervisor when required".
  - ¶66: committees keep records that "document the committees' fulfilment of their responsibilities".
  - ¶69: the audit committee ensures that "senior management is taking necessary corrective actions in a timely manner".
  - ¶167: supervisors set "a timetable for completion" of remedial actions, with "escalation procedures".
  - <https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks.pdf>
- **EBA Guidelines on internal governance (EBA/GL/2021/05):**
  - ¶151: "A formal follow-up procedure on findings and corrective measures taken should be put in place."
  - ¶224: "All audit recommendations should be subject to a formal follow-up procedure … to ensure and report on their effective and timely resolution."
  - <https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2021/1016721/Final%20report%20on%20Guidelines%20on%20internal%20governance%20under%20CRD.pdf>
  - A November 2026 final report (EBA/GL/2026/10) appears to replace them: <https://www.dirittobancario.it/wp-content/uploads/2026/11/Linee-guida-EBA-su-internal-governance-05-novembre-2026-EBA_GL_2026_10-Final-report.pdf> **[snippet]**. It was not verified, so nothing here depends on its paragraph numbers.
- **Reserve Bank of India, *Regional Rural Banks – Governance Directions, 2025* (RBI/DOR/2025-26/249, 28 November 2025), re-read by the agent:**
  - In the model board agenda (Chapter III, paragraph 9), item 1 is "Minutes and Action Taken Report". Its sub-items are "Confirmation of the minutes of the previous Board meeting" and "Actions taken on the decisions taken during the previous Board meetings".
  - Item 10(vii) is "Compliance in respect of outstanding observations of the Board".
  - <https://www.rbi.org.in/ScriptS/BS_ViewMasDirections.aspx?id=13057>
  - A commentary on these directions says their aim includes "elimination of routine compliance crowding on board agendas": <https://www.icsi.edu/media/webmodules/CSJ/Aug2026/30.pdf> **[snippet]**.
- **Bangladesh Bank:**
  - The August 2025 directive requires board and committee deliberations and dissent to be "recorded in detail in the minutes" (TBS News, <https://www.tbsnews.net/economy/banking/bb-mandates-recording-dissenting-opinions-board-meetings-1204361>).
  - NBFIs must hold special meetings on irregularities within two months of the report and send the minutes within 15 days (TBS News, 16 January 2022, <https://www.tbsnews.net/node/358396>).
  - The audit committee reviews compliance with audit and inspection recommendations quarterly (BRPD Circular 11 of 27 October 2013, <https://www.scribd.com/document/467462717/Board-oct272013brpde11> **[snippet]**).
  - **No Bangladesh Bank rule requiring a report on the implementation status of board decisions was found.** bb.org.bd blocked automated reads, so this means "not found", not "does not exist".
- **Internal audit follow-up as the model for verified closure:**
  - ACCA's guide summarises the IIA's 2024 Global Internal Audit Standards (Standard 15.2, "Confirming the Implementation of Recommendations or Action Plans").
  - Auditors "must obtain suitable evidence … and, where relevant, undertake testing" rather than accept management's word, and update status "in a tracking system".
  - Reports show "missed dates and revised dates", and the protocol covers "the escalation process for actions not cleared by the agreed date".
  - <https://www.accaglobal.com/gb/en/member/sectors/internal-audit/learn/management-actions.html>
  - The IIA Australia factsheet treats closing an action that is still outstanding as accepting the remaining risk: <https://iia.org.au/member-resources/factsheet-audit-action-close-out> **[snippet]**, members only.

**Adopted:**
- The action log is part of the record. Entries are append-only and audited.
- It can be exported for a supervisor, as the tracker's XLSX here; E08 adds actions to the cross-forum register.
- **Closure is verified by someone other than the owner**, with an optional completion note and evidence. Evidence is required per committee type (on in the Board Audit Committee preset).
- **Overdue actions escalate:**
  - reminders to the owner;
  - a notice to the owner's supervisor and the secretaries after a set time;
  - the next meeting sees them red in the action taken report, which is the escalation to the committee itself.
- **Cancelling an action whose work is not done is "closed, risk accepted"**, a decision only the chair takes. The secretary can record it on the chair's behalf, with how it was agreed.
- The report leads with what needs the meeting: overdue, proposed to close, and changes since the last meeting. Routine open rows come after, which answers RBI's "compliance crowding" point.

## 4. How board portals track actions
| Product | What it does | Source |
|---|---|---|
| **BoardPro** | Actions are added in the draft minutes under the item. Each has an owner, a due date and a description. Statuses: not started, in progress, on hold, done. Owners are told when the minutes are final. Reminders go a week before and on the due date. The action list is "a live register of all actions associated with your board". In the next pack's action list, actions are chosen by filter only (overdue, completed since, due in future): "Individual actions cannot be manually selected or excluded." "Action items cannot be permanently deleted." Several owners are allowed. Only an administrator, the secretary or the chair changes the owner or end date; members update status and comments. "Each Action gets its own email." Forwarding leaves the original owner accountable | <https://www.boardpro.com/features/actions>, <https://help.boardpro.io/en/articles/11273068-smart-agenda-item-action-list-new-ui>, <https://help.boardpro.io/action-items/action-item-faqs> |
| BoardPro (ideas portal) | No actions between meetings ("BoardPro doesn't allow for this currently"). Owners must be members, so people are added without an invitation as a workaround. Users asked in 2021 to move actions between committees | <https://ideas.boardpro.io/ideas/BP-I-147> |
| **Diligent Boards** | Actions are added to draft minutes only, with optional assignees and due date and "+ Add reminders". They appear in the minutes section and on the assignee's Actions dashboard. Administrators get a dashboard with configurable columns and filters and reminders at intervals. They can change the due date and assignees, add notes ("the latest one is shown"), mark complete or delete. Edits email the assignees by default | <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-add-ons/minutes/add-an-action-to-minutes.htm>, <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-add-ons/minutes/action-dashboard-for-administrators.htm> |
| BoardEffect | Workroom tasks: title, description, assignees, optional due date, reminder date, file, group, and "Notify me when the task is marked complete". Complete or not, nothing more. A 2026 post says the meeting's action log can be generated from the minutes | <https://help.diligentoneplatform.com/helpdocs/d1p/en-us/Content/boardeffect/admin/workroom/workroom_collaborate/creating_and_assigning_a_task.htm>, <https://www.boardeffect.com/blog/ai-innovations-connect-meeting-records/> **[snippet]** |
| **OnBoard** | A task is linked to its agenda item and minutes, with reminders, notes and files. "Outstanding tasks move forward with their notes and attachments" to the next meeting. Members get a "Centralized Task Overview". Any user can create and assign a task, with viewers, priority and status | <https://www.onboardmeetings.com/board-portal/onboard-tasks/>, <https://help.passageways.com/hc/en-us/articles/360042980952> **[snippet]** |
| **iBabs** | Actions with a traffic-light status, filtered by agenda type, owner, status (completed, pending, overdue) and due date. A separate decision list. Export to a branded document | <https://www.ibabs.com/en/actions-decisions/> **[snippet]** |
| **Board Intelligence** | Actions from approved minutes flow into the tracker "with the context, owner and due date intact", and updates flow back into draft minutes. People outside the portal can be assigned by name and email. A tracker per forum plus "All Actions" across forums. Export to Word or CSV for the pack | <https://www.boardintelligence.com/action-tracker> |
| Azeus Convene | Actions are added to the generated minutes. Reminder timing is configurable (for example three days before) | <https://www.azeusconvene.com/en-gb/articles/how-to-track-meeting-action-items-effectively> **[snippet]** |
| Nasdaq Boardvantage | "Create, assign, and track action items … with reminders and notifications" | <https://www.nasdaq.com/solutions/governance/boardvantage/features/meetings-workflow> **[snippet]** |
| Admincontrol | "Capture and follow up on decisions"; the dashboard shows outstanding tasks. No action workflow is described | <https://admincontrol.com/board-management-portal/features> |

### 4.1 Adopted, and where we do better
1. **Raise from the decision, a condition of it, an item without a decision, or the meeting.** BoardPro and Diligent tie actions to draft minutes. Here the secretary raises them on the decision panel (E06 flow AA) during or after the meeting, and the minutes print them. A condition of an "approved with conditions" decision becomes an action in one step ("Make condition 2 an action").
2. **Actions between meetings** (BoardPro's gap): the secretary can raise an action with no meeting, for example from the chair's instruction or a circular resolution. It carries a note of where it came from.
3. **One accountable owner, a person or a position.** BoardPro allows several owners. CGI and the NHS logs name one responsible officer, and a single owner is what makes overdue escalation meaningful. Up to five **contributors** may post updates. A position follows its holder through the workflow engine.
4. **Owners outside the committee** (Board Intelligence, Loughborough), but **inside the tenant.** Every owner is a user of the organisation, and their access to the action does not depend on committee membership. An outside party (an auditor, a consultant) is named in the text. A staff owner, often the secretary, carries the action for them: no external accounts on a bank's governance system (AGENTS §3.4).
5. **The owner changes the status and dates only through requests the secretary agrees** (BoardPro's split), with the agreement per committee type.
6. **Full history** (Diligent shows only the latest note): every update, date change, owner change and verification is a dated, attributed entry. Nothing is deleted: a draft is discarded and kept, an issued action is cancelled with a reason.
7. **The secretary curates the carried-forward list** (BoardPro's "cannot be manually selected or excluded" is a criticism to avoid). The report is built from rules: every open action, plus what closed or was cancelled since the last meeting. The secretary can still hold a row back from a revision with a reason, and the action stays open in the tracker.
8. **Reminders that do not flood** (BoardPro's "each Action gets its own email"):
   - in-app items arrive one per action as they happen;
   - emails are combined into one daily message per person, which lists only counts and references;
   - an escalation is sent at once.
9. **A tracker per committee and "My actions" across committees** (Board Intelligence). The cross-forum register and analytics are E08's.

## 5. What users criticise (all **[snippet]**: G2, Capterra and TrustRadius refused automated reads)
- **Convene (G2):**
  - a reviewer wants statuses beyond outstanding, completed and cancelled;
  - another wants "more flexible reporting" across teams;
  - another finds older records hard to reach.
  - <https://www.g2.com/products/azeus-systems-convene/reviews>
  - Our answer: the owner's own word ("on track", "at risk", "off track") beside the status, filters on everything the log shows, and closed actions kept and searchable in the tracker.
- **BoardPro (Capterra):** a reviewer "wishes it had action items on review only items". <https://capterra.com/p/164493/boardPro/reviews/?page=2>
  - Our answer: an action can come from any item, including items for noting.
- **iBabs (Capterra):** praised because actions no longer get lost in the minutes; criticised as impractical when other organisations do not use it. <https://capterra.com/p/180383/iBabs/reviews/>
  - Our answer: owners outside the committee but inside the organisation (§4.1, point 4).
- **Our Cat Herder blog:** "checking on action progress means emailing the administrator and waiting for a reply". <https://www.ourcatherder.com/to-portal-or-not-to-portal/>
  - Our answer: the chair and members read the tracker themselves.
- **OnBoard and Boardable (Capterra):** "older board members that lack practical tech knowledge … might push back"; "some parts of the website are not intuitive". <https://www.capterra.com/p/179015/OnBoard-Board-Portal/reviews/?page=28>, <https://www.capterra.com/p/221109/Boardable/reviews/>

## 6. Checking the overview's recommendations against practice (Phase 4 §10)
| Overview says | Practice | E07 |
|---|---|---|
| Statuses: open, in progress, done pending verification, closed, cancelled | Matches the logs (open, propose to close, closed) and BoardPro (not started, in progress, done) | Kept, plus DRAFT before the secretary issues an action (BoardPro and Diligent notify only from final or draft minutes; §3.1 says send them early) |
| RAG derived from the due date and progress by a tenant rule, with an override and reason | RAG meanings differ between organisations (§3.2) | Kept, with the rule **per committee type** (tenant configuration, reviewed by a second administrator like every type change, ADR-0056) rather than one tenant rule. A board and a credit committee chase differently. **Reported to the owner** (ADR-0067) |
| A due-date change needs a reason and, per forum type, the secretary's agreement | BoardPro: only the secretary or chair changes dates; NHS and IIA keep revised dates | Kept: OWNER_WITH_REASON, SECRETARY_AGREES (default) or CHAIR_AGREES; the original date and every revision kept |
| Reminders and escalations on the workflow timers and business calendar | IIA: an escalation process; Basel ¶167 | Kept. This needs four small, general additions to the engine (ADR-0068), because ADR-0041's timers count from the task's creation and an action's due date is set by the meeting |
| Closure through a workflow on the new `action-item` subject: the owner marks it done, the secretary or chair verifies it or sends it back | NHS: owner proposes, meeting or secretary confirms; IIA: evidence and an independent verifier | Kept. **The whole action runs on the engine** (owner's step, then verification), not only the closure, so reminders, substitutes, holders and escalation are the engine's. The verifier is never the owner |
| A follow-up memo raised from an action, linked back; the loop closes when the memo is decided and the action can then be verified | No portal links an action to an e-office memo | Kept; also link an existing memo. The decided memo does not close the action: the owner is told and marks it done, and the verifier sees the memo's outcome |
| Open actions carried forward to matters arising with their status at agenda publication | ICSI's action taken report, RBI's agenda item 1, CGI's actions schedule in the pack | Kept, as a frozen **action taken report** per published revision of the next meeting, with "closed since the last meeting, for noting" and E06's approved corrections |

No finding contradicts the overview. Two refinements are recorded in ADR-0067 and reported: the RAG rule per committee type, and the action's life (not only its closure) on the engine.

## 7. Usability for older directors and secretaries
- **NN/g, *Usability for Older Adults: Challenges and Changes* (2019):**
  - Problems: small, low-contrast text; small targets; input formats that punish; vague or hard-to-spot error messages.
  - Advice: accept several input formats and explain errors next to the field.
  - <https://www.nngroup.com/articles/usability-for-senior-citizens/>
- **NN/g, *Usability for seniors* rounds:**
  - Users over 65 succeeded on 55.3 % of tasks against 74.5 % for younger users.
  - Avoid changing navigation.
  - <https://www.nngroup.com/articles/usability-seniors-improvements/> **[snippet]**
- **NN/g, *Five Mistakes in Designing Mobile Push Notifications*:**
  - "send fewer notifications in a meaningful way";
  - combine bursts;
  - defaults matter because most users never change them.
  - <https://www.nngroup.com/articles/push-notification/>
- **W3C WAI, *Older Users and Web Accessibility*:**
  - declines in vision (colour perception included), dexterity and short-term memory;
  - large controls;
  - "clear notifications and feedback".
  - <https://www.w3.org/WAI/older-users/>
- **OnBoard, *Best board portal for older board members* (vendor):**
  - "minimal menu depth, clear labels, and appropriately sized buttons";
  - some directors keep asking for printed packets.
  - <https://www.onboardmeetings.com/blog/best-board-portal-for-older-board-members/>
- **Knock's alert-digest template:** urgent alerts bypass batching. <https://knock.app/template-library/workflows/alert-digest> **[snippet]**

**Adopted (ux-review principles):**
- "My actions" sits in the inbox's fixed place, with one primary button per action ("Post an update", "Mark as done").
- Status in words with an icon, never colour alone.
- The date field accepts typed dates and offers "By the next meeting (12 December)".
- Errors are written in plain words next to the field.
- 44 px targets.
- A printable action taken report.
- One daily email instead of one per action, and escalations at once.
- A director with no actions sees nothing new.

## 8. Sources tried that failed (not retried)
- **Refused, blocked or returned a page other than the document:**
  - icsi.edu: the whole site refused connections (SS-1 2024, the CS Journal);
  - bb.org.bd PDFs: a block page;
  - g2.com, capterra.com, trustradius.com: 403;
  - help.passageways.com, ibabs.com, azeusconvene.com: 403;
  - nasdaq.com: timed out;
  - bis.org/bcbs/publ/d328.pdf: an HTML page (the publications URL above works);
  - theiia.org's GIAS PDF and the AICD minutes PDF: HTML instead of the PDF.
- **Not found at all:**
  - a CGI action-log template;
  - a Governance Institute of Australia action-list guide;
  - the official text of IIA Standard 15.2;
  - any Bangladesh Bank rule on reporting the implementation of board decisions.
