# Phase 4 demo — forums, meetings, minutes, resolutions, actions

A walk through Phase 4 (milestone M2, committee meetings live) on the dev environment (`https://dev.memo-revamp.naziharitsolution.com`) or locally (`pnpm dev`, then `http://localhost:5173`). About 90 minutes: sections 1–8 show each epic on the seeded data and stand on their own; section 9 runs the whole committee cycle live (about 40 minutes). It assumes the Phase 3 demo (`../phase-3-memo-efile/demo.md`) has been seen.

**Read section 10 before showing dev.** The data below is what a fresh seed holds. Dev was seeded in steps since 2026-11-18, and on 2026-11-28 it did not yet have the E07 and E08 seeds.

Every demo account uses one password and one TOTP secret, `MEMO_DEMO_PASSWORD` and `MEMO_DEMO_TOTP_SECRET`. Neither is in the repository:
- **locally:** in `deploy/compose/.env`, generated and git-ignored;
- **on dev:** in `/opt/memo/dev/.env` on the dev VM, readable by the VM's administrator (`sudo`).

Use two or three private browser windows to switch people quickly. Minutes drafting, readers' copies, the minutes record and the papers' previews need the **documents stack** (ONLYOFFICE and the Tika server). It runs on dev; locally, start it with the `documents` profile. Without it, the memo's Word document is left out of an agenda's pack, as the PR smoke journeys do.

| Account | Role in the demo |
|---|---|
| `secretary@demo.example` (Demo Company Secretary) | Secretary of all six committees: submissions, agendas, invitations, attendance, decisions, minutes, actions, the register's export |
| `board.member@demo.example` (Demo Board Member) | Independent director. Chair of the Board Risk Committee (BRC) and the Board Audit Committee, member of the Board |
| `director@demo.example` (Demo Director) | Managing director's seat. Chair of the Management Credit Committee (MCC), ALCO and ExCo; member of the Board and the BRC, with Demo Head of Credit as his BRC alternate. The recused director of the demo |
| `head.credit@demo.example` (Demo Head of Credit) | Vice-chair of the MCC, member of ALCO and ExCo, the director's alternate on the BRC, presenter at the BRC; declares interests on credit items |
| `head.finance@demo.example` (Demo Head of Finance) | Holds the Chief Financial Officer position. Member of the MCC and ExCo, vice-chair of ALCO, a guest at the BRC; the dissenting vote |
| `user@demo.example` (Demo User) | Credit officer: raises the credit memos that escalate to the MCC |
| `laila.arjumand@demo.example` (Laila Arjumand) | Holds the Head of Credit Risk position, so owns the actions given to that position |
| `auditor@demo.example` (Demo Auditor) | The built-in Auditor role, plus **"Committee oversight"** (a custom role with `forum.read`, E08 T8): the register, analytics and the declarations register across every committee, in full within clearance |
| `admin@demo.example`, `reviewer@demo.example` | Tenant administrators. One drafts a committee type version and the other publishes it (maker-checker) |

Two seat holders are staff without sign-in, named by the demo's staff seating: the **Chairman** (a member of the BRC; Farhana Rahman on dev) and the **Chief Risk Officer** (a member of the MCC and a standing invitee of the BRC; Moushumi Das on dev). The secretary records their answers and attendance.

The demo data is seeded at start-up and only fills gaps. Dates are relative to the day each seeder first ran (in Asia/Dhaka, the demo tenant's zone), so on a long-lived environment the meetings drift into the past. Each epic's `log.md` says what its seed adds; the runbooks' "Demo data" sections say the same per area.

## 1. Committee types and committees (E01)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 1 | Committee types | `admin@` → Administration → Committee types | Board, Board committee and Management committee, made from presets. Each is versioned, and a version is drafted by one administrator and published by another. The Quorum tab writes the rule as one sentence beside the builder, with "Try it". The management type's newer version approves minutes **by circulation** and allows **a quorum override with a reason** (E06 T12) |
| 2 | Committees | `secretary@` → Committees | Six committees: Board of Directors, BRC, BAC, MCC, ALCO, ExCo. Each lists its chair, members today and status; the secretary is the company secretary for all six |
| 3 | Seats and terms | Board Risk Committee → Members, History, Policy | Demo Board Member chairs (independent, non-executive). The Chairman's and the CRO's seats are held **by position**. Demo Director's seat has an alternate. The CRO is a standing invitee without a vote. "Quorum today: …" is the rule in words. History keeps every row: nothing is deleted |
| 4 | My committees | `director@` → account menu → My committees | His seats: chair of three management committees, member of the Board and the BRC |

## 2. Submissions and the escalation link (E02)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 5 | Escalation by amount | `user@` → the sent memo "Term loan, Padma Spinning Mills" (BDT 15 million) | "Credit under authority" sends BDT 10 million or more to the MCC after the credit desk, before the delegation of authority; smaller amounts go straight to the authority. The memo reads "With the Management Credit Committee" and its committee panel shows the paper's timeline. The memo waits for the committee's decision |
| 6 | The queue | `secretary@` → MCC → Submissions | Waiting, Accepted, Deferred and Closed. Each triage dialog (accept, defer, return, decline, refer) says what it does to the memo and asks for a reason where one is needed. The chair gets "Open the paper" |

## 3. Meetings and the agenda builder (E03)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 7 | A published agenda with revisions | `secretary@` → BRC → Meetings → **Quarterly risk review** | Built from the standard agenda, with a consent section, an in-camera section, and the CFO as a guest. It was brought forward at the chair's request. Revision 2 replaced the risk appetite paper and added a late liquidity addendum, which the chair agreed in the app. Revision 3 (E07) added a late cyber insurance summary under "Matters arising and actions" |
| 8 | What a member sees | `director@` → My meetings → Quarterly risk review | "What changed" since he last looked: the replaced paper and the late paper. He asked to discuss "Business continuity test results", which lifts it out of the consent section. "Large exposures and concentration limits" is a line without papers: only the chair reads them |
| 9 | The builder | `secretary@` → MCC → **Credit approvals** → Agenda | Not published yet. The Padma Spinning Mills paper was placed from the tray. Show a move by drag and drop and the same move by keyboard, then the chair's agreement. Publishing is optional: it shows the agenda to the members and changes the demo data |

## 4. Conflicts of interest and recusal (E04)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 10 | Declaring an interest | `director@` → Quarterly risk review | "Term loan, Delta Textiles": "— You declared an interest", with no paper ("My brother-in-law is a director of Delta Textiles.", leaving the room). "Operational risk incidents, third quarter" waits for the chair's ruling, and until then he cannot read it either (fail closed) |
| 11 | The officers' view | `secretary@` → Quarterly risk review → Declarations | The Chairman's declaration on "Large exposures and concentration limits", recorded by the secretary "By email". The ruling still waiting for Demo Board Member |
| 12 | Carried from the memo | `secretary@` → MCC → Credit approvals | Demo Head of Credit stood aside on the Padma memo at the credit desk; placing it carried the interest to the item ("Interest declared on memo CR/…"), so the vice-chair is recused there |
| 13 | The register of declarations | `auditor@` → Declarations of interest | Every committee's declarations in one list, exported to Excel; each export is audited |

## 5. Invitations, attendance and quorum (E05)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 14 | Replies | `secretary@` → Quarterly risk review → Invitations and replies | Demo Director's apologies, with his alternate attending. The CFO's apologies. The Chairman's "Yes, in person", taken by phone, with "No invitation" on his row because he cannot sign in. The chair's answer is marked as being for the old time, because the meeting moved |
| 15 | The alternate | `head.credit@` → Quarterly risk review | "You are attending in place of Demo Director." He is recused from the director's items too (Delta Textiles) |
| 16 | A held meeting's register | `secretary@` → MCC → **Monthly credit review** → Attendance | Hybrid, kept at the times it happened. The CRO's seat holder arrived late (14:20). Demo Head of Credit stepped out for Meghna Foods and came back. Demo Head of Finance left early (15:15). The quorum was lost at 15:30 (2 of 4) when the vice-chair's connection dropped, and restored at 15:38. Show "Quorum by item…" |
| 17 | Calendar invitations | (explain) | Each invitation, update and cancellation is an iCalendar email with answers taken in the app. On dev, mail goes to Mailpit, not to real mailboxes. The recorded run in Outlook and Gmail is carried (E05 T9) |

## 6. Minutes and resolutions (E06)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 18 | **MCC/2026/001** with conditions and dissent | `secretary@` → Monthly credit review → Decisions, item "Working capital limit, Meghna Foods" | Approved with two conditions by 2–1. Demo Head of Finance's dissent is named, with the reason "The company's leverage has risen for two years running." Demo Head of Credit took no part. The decision carries its quorum snapshot (14:45, quorate with the vice-chair recused). The number was given when the meeting was marked as held |
| 19 | **The deferred item** | Same meeting, the watch-list review | Deferred to the next meeting: a decision recorded without a resolution number. The register lists it with the outcome "Deferred" |
| 20 | **The decision under override** | Same meeting, the credit portfolio report | Noted at 15:34, when the meeting had no quorum, under the committee type's override with a reason, and marked "To be ratified" at a later meeting |
| 21 | The memo moves on | `user@` → the Meghna Foods credit memo (BDT 80 million) | The committee panel and the note sheet carry MCC/2026/001. The route has gone on to the delegation of authority |
| 22 | Minutes by circulation | `secretary@` → Monthly credit review → Minutes | Drafted from the type's template, agreed by the chair, "No comments" from the members, the comment period closed early, approved by the chair. The record (PDF/A, locked) and an approved copy were made by their jobs. A **certified extract** of MCC/2026/001 is on the resolution |
| 23 | **BRC 1/2026** (BRC 2/2026 on dev) and the recused director's copy | `director@` → BRC → **Risk review, third quarter** → Minutes | Circulated. Demo Director stepped out for item 5, "Exposure above the single-borrower limit, Rupsha Steel". His copy keeps that decision but not its discussion, and his comment on "Cyber risk appetite metrics" is there. As `secretary@`, the same page shows two copies: everyone's, and the director's with item 5 redacted |
| 24 | Resolutions | `secretary@` → BRC → a resolution, e.g. BRC/2026/001 | BRC/2026/001–003 (Rupsha Steel, the cyber metrics, and the consent section decided together). The resolution page links its meeting, item and memo, and "In the register" |

## 7. Action items (E07)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 25 | **The actions A1–A4** | `secretary@` → BRC → Actions | **BRC A1** (from "Any other business", Demo Director's): done, waiting for verification. **BRC A2** (from BRC/2026/001, owned by the position Head of Credit Risk): overdue, and escalated to the holder's supervisor, the CRO. **BRC A3** (Demo Head of Finance's): a later date asked for and agreed, "(rev. 1)". **BRC A4**: cancelled, the risk accepted by the chair. Every colour has its word |
| 26 | Recusal on actions | `director@` → BRC → Actions | He does not see BRC A2: he stepped out of the Rupsha Steel item |
| 27 | MCC A1 and **the follow-up memo** | `laila.arjumand@` → Inbox → My actions → MCC A1 (or `secretary@` → MCC → Actions) | Raised from condition 1 of MCC/2026/001 and owned by the position, so its holder sees it. She raised the General memo "Meghna Foods: charge registered, first drawing may proceed" from it, and Demo Director approved it. She marked the action done with the registration certificate as evidence, and the secretary closed it. The memo links back ("Raised from action"). Separation of duties: neither the head of credit (interest) nor the head of finance (dissent) is on its route |
| 28 | **The action taken report** | `director@` → Quarterly risk review → "Matters arising and actions" → Action taken report | Frozen when revision 3 was published. BRC A1 to close, BRC A2 overdue, BRC A3 open, BRC A4 cancelled since the last meeting. There is also a PDF |
| 29 | My actions | `head.finance@` → Inbox → My actions | BRC A3 "originally …, revised once" |

## 8. The decision register and analytics (E08)
**The year of history** (`DemoHistorySeeder`, E08 T8), so the register and analytics have a year to show:

| | |
|---|---|
| BRC | "Risk review, January 2026" … "Risk review, October 2026": monthly, on the second Tuesday at 10:00–12:00 Dhaka time, for the eleven months before the seed's month. Months before the seats' terms (1 January 2026) are left out, and so is any month in which the committee already met |
| MCC | "Credit review, January 2026", March, May, July and September 2026: every other month, on the third Wednesday at 14:00–16:00 |
| Each meeting | Two items for decision, both approved and numbered (e.g. "Liquidity stress test results", "Large exposure to Karnaphuli Traders"; at the MCC "Term loan, Jamuna Rice Mills", "Project loan, Dhaleshwari Cement"), and a report for noting ("Risk dashboard, March"; "Credit portfolio report, March") |
| Attendance, BRC | Demo Director: apologies twice with his alternate standing in, once without, and absent without apologies once. The Chairman arrives late twice; the CRO leaves early twice |
| Attendance, MCC | Demo Head of Finance: apologies once. The vice-chair attends online once (hybrid). The CRO arrives late once and leaves early once |
| Quorum | At most one voting member is away from any meeting, so every decision has its quorum |

| # | Show | Who, where | What to point out |
|---|---|---|---|
| 30 | The register | `secretary@` → Decisions | Every decision of the committees they serve, newest first. Filters apply with Search and become chips. Facets by committee, outcome and year; "Showing n decisions" |
| 31 | By number | Type "MCC/2026/001", then the Meghna memo's `CR/…` number | One row each, in any spelling, with links to the resolution, the meeting and the memo |
| 32 | A restricted line | `director@` → Decisions → "BRC/2026/001", then "Rupsha" | The number finds a line: "Restricted item: You declared an interest in this item.", with no title, text or link. The words find nothing, and the outcome counts leave the line out |
| 33 | Exceptions | `secretary@` → More filters → With dissent; With conditions; To be ratified | MCC/2026/001 under dissent and conditions; the portfolio report under "to be ratified" |
| 34 | Oversight | `auditor@` → Decisions | Every committee in full within his clearance. Restricted items are lines for him too |
| 35 | Export for auditors | `auditor@` → committee BRC, this year, Resolutions only, Number order → Export… | A spreadsheet with the sheets Decisions, About this export and Number check, or a PDF for filing. Each export is audited with a hash of its rows, five an hour. "Check the numbers" says whether resolutions 1 to n are all accounted for |
| 36 | The analytics overview | `secretary@` → Committee analytics | Per committee, a sentence first ("…% attendance at n meetings · n actions overdue · …"), then the table, with bars beside it. "Based on n decisions you can see in full." Every number links to its list |
| 37 | Attendance per member | → BRC → Attendance per member | Over the last 12 months Demo Director attended 7 of 11 (64 %), with apologies 3 (2 by alternate) and 1 absence; the committee is at about 88 %. The alternate's attendance is shown but never counted as his. "Export for the annual report" |
| 38 | A member's view | `director@` → Committee analytics | His own row and the committee's total only, without late-paper counts and without an export |
| 39 | A committee's detail | → MCC → the committee's detail | Time from submission to decision ("too few" below five decisions), items deferred, actions overdue by owner with ageing and closed on time, papers on time per held meeting |

## 9. The committee cycle end to end
The exit journey `committee-cycle.spec` in fifteen legs, through the screens only (overview §5, exit criteria 1 and 3). Use a borrower name and amount not used before, e.g. "Term loan, Titas Poultry", BDT 25 million (above the BDT 10 million limit). Each leg is also an earlier journey. The owner's walk with its checklist is in `epic-08-register-analytics-close/ux-review.md`.

| # | Leg (epic) | Who, where | What to point out |
|---|---|---|---|
| 1 | The memo waits for the committee (E02) | `user@` → New memo → Credit proposal; `head.credit@` recommends it at the credit desk | Numbered `CR/…` on submit. After the desk it reads "With the Management Credit Committee", and the committee panel shows the paper |
| 2 | The secretary accepts it (E02) | `secretary@` → MCC → Submissions → Triage → Accept | Can be done by keyboard. The initiator reads "Accepted" |
| 3 | The agenda (E03) | `secretary@` → MCC → Meetings → Schedule a meeting (today, Dhaka time) → the builder: standard agenda, the paper from the tray; `director@` agrees | Past the paper deadline, the builder says the paper goes to the chair first. The chair agrees to the late item and to the agenda, and revision 1 is published |
| 4 | Invitations and replies (E05) | `director@`, `head.credit@`, `head.finance@` → the meeting → "Will you attend?" → Yes, in person; `secretary@` → Invitations and replies | The invitations went out when the meeting was scheduled. Three answers, and how each came |
| 5 | A declared interest (E04) | `head.credit@` → the item → Declare an interest, leaving the room | The vice-chair recommended the memo at the desk. His line becomes "— You declared an interest" |
| 6 | Attendance and quorum (E05) | `secretary@` → ⋯ → Start the meeting → "Mark everyone who said yes as present", the CRO's seat holder in person; Change the item; "Stepped out for this item" | "Quorate — meeting: 4 of 4 present, 3 needed." The vice-chair is out of the room for the item |
| 7 | The decision (E06) | `secretary@` → the decision panel | Approved with a condition by 2–1, with Demo Head of Finance's dissent and reason |
| 8 | The number (E06) | `secretary@` → Mark as held… | It says one memo moves on. The release job numbers the resolution `MCC/2026/…` |
| 9 | The memo moves on (E02/E06) | `user@` → the memo | The number on the committee panel and the note sheet; the memo no longer waits for the committee |
| 10 | Minutes by circulation (E06) | `secretary@` drafts from the meeting page; `director@` agrees; `head.finance@` answers "No comments"; the secretary ends the comment period; `director@` approves with his authenticator | The management type's way since E06 T12 |
| 11 | The action (E07) | `secretary@` → the decision → make the condition an action, owner the **position** Chief Financial Officer → Save and send to the owner | Owned by the position, so whoever holds it gets it |
| 12 | The follow-up memo (E07) | `head.finance@` → Inbox → My actions → Raise a memo (General), routed to `director@`, who approves it | The memo links to the action and back |
| 13 | Verified (E07) | `head.finance@` → Mark as done; `secretary@` → Verify | The verification shows the approved memo; the action is closed |
| 14 | The register (E08) | `secretary@` → Decisions → the resolution's number, then the memo's `CR/…` number; `auditor@` → the same | One row each, with "Resolution …", the outcome and the links. The auditor (oversight) reads the same row in full |
| 15 | The export (E08) | `secretary@` → Decisions, committee MCC → Export… → Spreadsheet | The file with the cycle's resolution, and the number check |

## 10. What dev does not show
Checked on 2026-11-28 by reading dev as `secretary@` and `auditor@`, without changing anything.

**Not deployed yet.** Dev runs `master` at bc2fa6fe (#253, E06 T12). E07 T12 (#256) is merged but not deployed, and E08 T8 (#261) is open. Until both are deployed and the seeders have run at start-up:
- dev has no demo actions: no BRC A1–A4, no seeded MCC action, no follow-up memo, and no revision 3 or action taken report on the quarterly review (§7);
- there is no year of history: analytics counts one held BRC meeting and two held MCC meetings, and the register holds only the E06 seed's and the journeys' decisions (§8);
- `auditor@` has no "Committee oversight": the register and analytics answer "for committee members, officers and oversight" (403), and the Declarations of interest entry is not in his menu (§4 row 13).

**After the deploys,** check that the backend log has "Demo committee history seeded: n meetings" (15 expected on dev: 10 BRC, 5 MCC), with no "Demo history: … not seeded" or "Demo actions: …" warnings. Then re-read §7 and §8 on dev.

**Dev's older meetings** (the E06 and E07 close notes):
- **Meeting numbers differ.** Meetings are numbered at publication. Dev's quarterly review was published first, so it is **BRC 1/2026**, and "Risk review, third quarter" is **BRC 2/2026**. On a fresh seed it is the other way round. BRC/2026/001–003 are the same on both.
- **The quarterly review is in the past.** It is dated 23 November 2026 and still "Scheduled". Show it as a published agenda, not as the next meeting.
- **The MCC "Monthly credit review" (MCC 1/2026) was held under the management type's first version.** Its minutes are "at the next meeting" and stop at Circulated: there is no approval by circulation, no record and no extract. "The next meeting" they are linked to is MCC 2/2026, a journey's leftover (below). The override was refused, so there is **no decision under override**: the portfolio report is undecided, and "To be ratified" is empty. Its Meghna Foods item has no memo, so MCC/2026/001 is there, with both conditions, the 2–1 vote, the named dissent and the head of credit taking no part, but **no memo moves on** (§6 rows 20–22). The cycle in §9 makes a new meeting on the type's current version, so its minutes leg runs.
- **Dev's management type predates E07 T2.** An MCC action's owner asks the secretary for a new date, where a fresh seed's MCC lets the owner move it with a reason. The date request in §7 is on a BRC action.
- **Journey leftovers** (titles with a time stamp): MCC 2/2026 "E2E committee cycle 1795850059094" is stuck "In progress" in UTC (the run before #253). MCC 3/2026 "E2E committee cycle 1795850875877" has its minutes approved, MCC/2026/002 and **MCC A1/2026**. So the seeded Meghna Foods action will be **MCC A2/2026** on dev, and every number a live run makes will be one higher than on a fresh seed.

**The history's limits** (E08 T8: what the record cannot be told after the fact stays as it happened):
- **Papers are counted as late.** The history agendas were published on the day of seeding, so "Papers on time" counts every history meeting as late. It reads close to 0 % for both committees.
- **There is no time to decision.** The history's items are plain items, not submissions (a submission's times are real), so they are not counted. "Time from submission to decision" stays "too few" until five submissions are decided. "Items deferred" counts submissions only, so the MCC's deferred watch-list review is not in it.
- **There are no minutes.** None are drafted for the history meetings, so the register's state for them is "Numbered" or "Released", never "Minutes approved".
- **Numbers follow the order of seeding.** The seeder works from the most recent month back, so October's meeting and resolutions have the lowest history numbers and January's the highest. Number order and date order differ; the number check is still complete.
- **Action taken reports reflect the day of seeding.** Each history meeting's report lists the committee's actions as they stood on that day.

## 11. If something looks wrong
| What | Where to look |
|---|---|
| A committee, a seat, an alternate or the quorum sentence | [committees.md](../../runbooks/committees.md) |
| A paper missing from the pack, a late paper, an agenda that will not publish, a declaration, a ruling, the alternate | [meetings-and-agendas.md](../../runbooks/meetings-and-agendas.md) ("Conflicts of interest", "Attendance and quorum", "Demo data") |
| An invitation not received or not updated | [meeting-invitations.md](../../runbooks/meeting-invitations.md) |
| A number not given, minutes stuck, a copy or record that failed, an extract | [minutes-and-resolutions.md](../../runbooks/minutes-and-resolutions.md) |
| An action not overdue, not escalated, or missing from the report | [action-items.md](../../runbooks/action-items.md) |
| The register missing a decision, an export refused, an analytics number that looks wrong | [decision-register-and-analytics.md](../../runbooks/decision-register-and-analytics.md) ("When a number looks wrong") and [search.md](../../runbooks/search.md) |
| Demo data missing on dev | The backend log's seeder lines (above). The seeders only fill gaps, so fix dev's data through the product, never by SQL |

## 12. Evidence
**Automated journeys** (Playwright, smoke stage on every pull request; they need no document converter):

| Epic | Spec |
|---|---|
| E01 | None yet; its journeys are open (STATUS) |
| E02 | `committee-escalation.spec.ts` |
| E03 | `meeting-agenda.spec.ts`, `meeting-page.spec.ts` |
| E04 | `declarations.spec.ts` |
| E05 | `meeting-replies.spec.ts`, `meeting-attendance.spec.ts` |
| E06 | `meeting-decisions.spec.ts`, `minutes-approval.spec.ts`, the E06 leg of `committee-escalation.spec.ts` |
| E07 | `action-items.spec.ts`, `committee-actions.spec.ts` |
| E08 | `meeting-analytics.spec.ts`; the exit journey `committee-cycle.spec.ts` (§9) |

**Integration:** `CommitteeCycleIT` walks every link of the cycle both ways, checks each step's audit entry by its actor, and verifies the tenant's audit chain. `DemoMeetingsIT` checks the seeds above, including that a second run adds nothing. **Volume** (E08 T5): `RegisterVolumeIT` at 55,000 and 115,000 decisions, `AnalyticsVolumeIT`, and `CommitteeLoadSimulation` at 50 people, in the weekly `load` workflow. **Usability:** the owner's walk through the cycle and the moderated walkthrough, both waiting for the owner (`epic-08-register-analytics-close/ux-review.md`). **Runbooks:** `docs/runbooks/` (committees, meetings and agendas, meeting invitations, minutes and resolutions, action items, decision register and analytics, search).
