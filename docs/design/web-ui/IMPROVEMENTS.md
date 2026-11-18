# UX improvement backlog

Ideas for making the web app easier to use, collected while redesigning it (2026-11-18). Each says what it is, why it matters, and what it needs from the backend. Some go beyond what the API offers today; those say what would have to be added.

Status:
- **Designed**: on the canvas (see `README.md`), with notes in `NOTES.md`.
- **Idea**: not designed yet. Pick it up when there is time, design it, then plan and build it as usual (`AGENTS.md` §5).

Priority: **High** = do first; **Medium** = worth doing; **Low** = nice to have.

## Approvers

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 1 | **Approve and go to the next task.** Decision dialogs offer "Open my next task after this" (remembered per person). The memo header shows "1 of 7 in My tasks" with previous and next. | Approvers clear a queue in one flow instead of going back to the list after each decision. | Frontend only. Uses the Inbox order the client already has. | High | Designed |
| 2 | **"Since you last looked"** at the top of a memo opened before. Shows what you asked for last time, the resubmission and its remark, document versions (with Compare), attachments added and new notes. | Re-reviewing a returned memo is where approvers lose the most time. | API item 10: record when each person opens a memo and return what changed since. Version comparison already exists. | High | Designed |
| 3 | **Unread markers.** A blue dot and a heavier subject on My tasks and Watching rows with news since you last opened the memo, and a "New" tag on notes added since your last visit. | You see at a glance what moved. | API items 10–11: last seen, and `unread` on inbox and watching items. | High | Designed |
| 4 | **Approve straight from a notification on the phone.** A notification opens the decision sheet directly, and step-up uses Face ID or fingerprint. | Senior approvers approve on the go instead of waiting until they are at a desk. | **Web:** decision links such as `/memos/<id>?decide=approve` (frontend only); passkey step-up with Phase 1b E02. **Mobile app:** push notification to the decision screen with biometric step-up; needs the mobile token issuer (Phase 1b E03). Push may only say "1 memo is waiting for your decision". | High | Idea |
| 5 | **Reading pane on wide screens.** At 1400px and wider, Inbox shows the list and the selected memo side by side, with the decision bar. | Scan and act without leaving the list. | Frontend only. | High | Designed: a per-person switch, off by default |
| 6 | **Inline document preview** on the memo page (first pages of the PDF rendering). | Most approvers never need to open the full viewer. | Frontend only; renderings exist. | Medium | Idea |
| 7 | **Keyboard shortcuts:** J/K move in Inbox, Enter opens, A / R / X open Approve / Return / Reject, / searches, ? lists them. | Faster for people who approve many memos a day. | Frontend only. | Medium | Idea (J/K hinted on the canvas) |
| 8 | **Saved remarks:** pick from your own common return reasons. | Less retyping of the same reasons. | New: per-person remark snippets. | Low | Idea |
| 9 | **"Why am I on this route?"** On the task card: the delegation-of-authority rule and amount band that put this person on the route. | Builds trust in automatic routing. | The route entry needs to carry the rule that matched. | Medium | Idea |

## People who send memos

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 10 | **Expected timeline** on Draft and on your memo in review: who acts when, from each step's time limit ("likely with the MD by 24 Nov"). | Sets expectations; fewer "where is it?" calls. | Expose the step time limits (they exist in the workflow timers). | Medium | Idea |
| 11 | **Send a reminder** to the person holding an overdue step. Rate-limited and recorded in the history. | Replaces chasing people by phone. | New endpoint. | Medium | Idea |
| 12 | **Start from this memo:** copy subject, type, details and route into a new draft. | Recurring memos, and the follow-up after a rejection. | New endpoint, or done in the browser from data it can already read. | Medium | Idea |
| 13 | **Co-author presence:** "Nusrat is editing the document" on the draft. | Co-authors don't overwrite each other. | Expose who is in the editing session (the editor already knows). | Low | Idea |

## Finding things and keeping up

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 14 | **⌘K for actions:** jump to a page, start a new memo, open a recently viewed memo. | One place to go for anything. | Frontend only; recently viewed can be kept in the browser. | Medium | Idea |
| 15 | **Alerts on saved searches:** "tell me when a new memo matches". | Keep up without searching again. | New. | Low | Idea |
| 16 | **Notifications grouped by memo** ("3 updates on CR/2026/0142"), and a **daily digest** with quiet hours. | Less noise. | Grouping: frontend only. Digest and quiet hours: backend. | Medium | Idea |
| 17 | **Snooze a task:** "remind me tomorrow at 9" hides it from My tasks until then. | Keeps My tasks to what you can act on now. | New. Must not change the step's due date. | Low | Idea |

## Away and acting for others

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 18 | **Self-service out-of-office:** dates and a delegate from the account menu, and a banner while it is on ("You're away until 25 Nov; tasks go to Nusrat Jahan"). | People set it themselves instead of asking an administrator. | Phase 1b E04 (out-of-office) plans the backend. | Medium | Idea |
| 19 | **"Acting for" filter** in Inbox: your own tasks vs tasks you hold for others. | Clear separation when covering for someone. | Frontend only; the field exists. | Low | Idea |

## Occasional and older users

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 20 | **Text size and a comfortable density setting**, plus a high-contrast theme. | The compact default suits daily users but not occasional senior readers (`AGENTS.md` §3.7). | Frontend only. | Medium | Idea |
| 21 | **Explain every blocked state** with a next step and a contact ("Your administrator, Arif Rahman, can reassign it"). | No dead ends. | Partly frontend; needs who to contact. | Medium | Idea |

## Small wins

| # | Improvement | Needs | Status |
|---|---|---|---|
| 22 | **Undo for reversible actions** (Claim, Release, Stop watching, Revoke): a five-second Undo in the toast instead of a confirm. Decisions keep their confirm dialog; the record cannot change. | Frontend; the undo calls the reverse action. | Idea |
| 23 | **Optimistic updates** for Watch, Claim and mark-as-read, so the app feels instant. | Frontend only. | Idea |
| 24 | **Remember last choices:** the Inbox tab, sort and filters per person. | Frontend only (browser storage). | Idea |
| 25 | **Relative dates for recent things** ("2 hours ago"), absolute for older, applied the same way on every screen. | Frontend only. | Idea |

## Admin

| # | Improvement | Why | Needs | Priority | Status |
|---|---|---|---|---|---|
| 26 | **Warn about look-alike numbers.** The Numbering tab checks the other series and warns when two can produce numbers that read the same. Example: a Policy series counted per unit, from a unit coded HR, gives HR/2026/0012, like the HR request series. | Two memos can carry the same-looking number, and people quote numbers. | Frontend check over the series list (`GET` numbering series), or a server validation on save. | Medium | Idea |
| 27 | **One spelling of "Built-in".** Roles say "Built-in", Workflows say "Built in". | The same idea reads the same everywhere. | Frontend messages (`access-messages.ts`, `workflow-messages.ts`). | Low | Idea |
| 28 | **Show more instead of Load more** on Audit and Import, like every other list. | One word for one action. | Frontend messages (`admin-messages.ts`, `imports/messages.ts`). | Low | Idea |
| 29 | **One success word per kind of action:** "Saved." after a change is saved, "Done." after an action that is not a save. Today both appear for similar actions (for example "Done." after creating a substitution, "Saved." after editing a queue). | Predictable feedback. | Frontend messages. | Low | Idea |
| 30 | **Approval confirmation on the Security page:** "Approving memos: a confirmation lasts (minutes)", 5–240. The server has the setting (`security.stepup.approval.minutes`) but no page shows it. | Administrators can tune how often approvers confirm it is them. | Add the field to the security settings API and page. | Low | Idea |

## Decisions

- **Reading pane (5): a per-person switch, off by default** (2026-11-18). A "Reading pane" switch in the My tasks toolbar, shown only at 1400px and wider, turns it on and is remembered per person. When on, the list and the selected memo sit side by side with the decision bar; "Open the full page" covers the note sheet, attachments, linked memos and history. Links from notifications, search and Home still open the full page.
