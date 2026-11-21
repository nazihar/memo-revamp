# E05 Invitations, attendance and quorum — UX review

> `AGENTS.md §5` Step 3. **The owner delegated these screens.** Phase 4 Q1 sends only the E01, E02/E03 and E06 flows to the owner before build; E05's screens are reviewed under the delegated gate, so the review table below records "delegated" and the web tasks (T7, T8) do not wait. Wireframes are structural; the look is the existing Ledger direction and components. "Committee" is the default English word for a forum. Flows continue from E04's U: **V** invitation and reply (members), **W** invitations and replies (secretary), **X** attendance at the meeting (secretary), **Y** quorum (secretary, chair, members), **Z** the committee type's Meetings tab.
>
> **The one real-world input** E05 needs from the owner is not a screen review: it is the mailboxes and relay for the recorded run in real calendar clients (T9). See "Owner input" at the end.

## Design principles taken from the research
1. **One question, three answers in words:** "Will you attend?" — "Yes, in person", "Yes, online", "No, I send my apologies" (iDeals' wording, without "Maybe"; research §4, §6).
2. **The calendar is for remembering, memo is for answering.** The invite's first line says how to reply; nobody depends on a calendar's Accept button (research §3.3).
3. **The secretary can record what they are told.** "Recorded by the secretary — by phone" is a normal answer, not a workaround (OnBoard's gap, research §4).
4. **Answers survive small changes.** Only a new date or time asks again; a new room or link does not (better than OnBoard's reset).
5. **The secretary always knows whether the meeting is quorate,** for the meeting and the item in hand, with what is missing in words (CGI §5.2.1).
6. **Recording attendance must keep up with a live meeting:** prefilled from the replies, one action for everyone who said yes, the item in progress filled in for every arrival and departure.
7. **For the director: big, plain, calm** (E03 principle 5): 44 px targets, nothing to do at the meeting itself.

## Flow V — Invitation and reply (members)

**V1. The invitation email** (subject without parameters: "Meeting invitation"; the calendar part makes it a meeting request)
```
From: Board Risk Committee (memo) <meetings@bank.example>
Subject: Meeting invitation

Dear Demo Director,

You are invited to a meeting of the Board Risk Committee.

  Thursday 12 December 2026, 10:00–13:00 (Dhaka time)
  Board Room, Level 12, Head Office — or join online

Please tell us whether you will attend:
  [ Open the meeting in memo ]
Your calendar's Accept and Decline buttons do not reach the committee secretary.

The agenda and papers will be on the meeting page when they are published.
──────────────────────────────────────────────
Attached: invite.ics
```
- **Updates** say what changed, in words, without the old and new values in the subject: "The date or time of this meeting has changed. Please tell us again whether you will attend." / "The place or the joining link has changed."
- **Cancellation:** "This meeting has been cancelled." The reason is on the meeting page, not in the email.
- **With a neutral title** (committee type setting): the email and calendar say "Board committee meeting" instead of the committee's name.

**V2. "Will you attend?" on the meeting page** (E03 P1's place for E05, under the time)
```
Board Risk Committee — BRC 3/2026
Thursday 12 December 2026 · 10:00–13:00 Dhaka time
Board Room, Level 12, Head Office · or join online  [ Join the meeting ]
──────────────────────────────────────────────────────────────────────────────
Will you attend?                                   Please reply by Tuesday 10 December
 ┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────────────┐
 │  Yes, in person      │ │  Yes, online         │ │  No, I send my apologies     │
 └──────────────────────┘ └──────────────────────┘ └──────────────────────────────┘
 [ Add to my calendar ]
──────────────────────────────────────────────────────────────────────────────
Opening
 1  Welcome and apologies …
```
- The three answers are a radio group drawn as large buttons; choosing one saves it at once and shows the result:
```
Will you attend?   ✓ You said: Yes, online (12 Nov, 10:14)          [ Change my answer ]
                   A note for the secretary (optional): [ I will join after 11:00      ]
```
- "Yes, in person" is not offered for an online meeting, nor "Yes, online" for an in-person one.
- After the meeting starts the card becomes: "You are recorded as present (online)" or "You sent your apologies".

**V3. Apologies, with the alternate** (only when the member's seat has an alternate)
```
No, I send my apologies
  Your alternate is A. Rahman.
  (•) A. Rahman will attend in my place
  ( ) Nobody will attend in my place
  A note for the secretary (optional): [                                    ]
                                              [ Cancel ]  [ Send my apologies ]
```
- Confirmation: "Your apologies are recorded. A. Rahman has been asked to attend in your place and will receive the invitation."
- The alternate's own page shows: "You are attending in place of Demo Director." with the same three answers.
- Refusals in words: "Your alternate is also a member of this committee and cannot attend in your place." / "A. Rahman is already attending in place of another member."

**V4. After a change of date or time**
```
Will you attend?   ⚠ You replied "Yes, in person" for the old time (Thursday 12 December, 10:00).
                   The meeting is now on Thursday 12 December at 14:00. Will you attend?
 [ Yes, in person ]  [ Yes, online ]  [ No, I send my apologies ]
```

**V5. An answer recorded by the secretary** (in-app and email to the member)
```
The secretary recorded your answer for the Board Risk Committee meeting on 12 December:
"No, I send my apologies" — told by phone on 9 December.
If this is not right, open the meeting and change your answer.   [ Open the meeting ]
```

**V6. My meetings** (E03 N3) gains the answer per meeting: "You said: Yes, in person", "Please reply", "Reply again: the time changed".

## Flow W — Invitations and replies (secretary)

**W1. The "Invitations and replies" panel on the meeting** (a tab beside E03's agenda for officers)
```
Invitations and replies — BRC 3/2026, Thursday 12 December 10:00–13:00
Invitations sent 12 Nov 10:02 · last update 3 Dec 15:40 (new time)           [ Resend… ]
Replies: 5 yes (3 in person, 2 online) · 1 apologies · 1 not yet · 1 for the old time
If everyone who said yes attends: quorate — 5 of 7 voting members, 4 needed, chair present.
⚠ Item 5: not quorate even then — 3 of 4 needed (2 members have declared an interest).
──────────────────────────────────────────────────────────────────────────────────────────
Name             Role         Reply                       How        Invitation            
Dr F. Karim      Chair        Yes, in person              in memo    ✓ delivered 3 Dec      ⋯
Demo Director    Member       Apologies — alternate       in memo    ✓ delivered 3 Dec      ⋯
  A. Rahman      Alternate    Yes, in person              in memo    ✓ delivered 9 Dec      ⋯
S. Ali           Member       Yes, online                 by phone*  ✓ delivered 3 Dec      ⋯
M. Hossain       Member       Not yet                     —          ⚠ not delivered (retry)⋯
N. Chowdhury     Member       Yes (for the old time)      in memo    ✓ delivered 3 Dec      ⋯
J. Begum         Guest (CFO)  Yes, in person              in memo    ✓ delivered 12 Nov     ⋯
* recorded by K. Secretary on 9 Dec: "called the office"
```
- Notes from members appear under their row ("I will join after 11:00").
- The forecast lines come from the replies and E04's declarations; the item warning links to the Declarations panel (flow T).
- **⋯ per row:** "Record an answer…", "Resend the invitation", "Show messages sent" (date, kind, delivery).

**W2. Record an answer** (dialog)
```
Record an answer for M. Hossain
  Answer:  ( ) Yes, in person   ( ) Yes, online   (•) Apologies   [ ] alternate will attend
  How did they tell you?  ( ) Email  (•) Phone  ( ) In person  ( ) Other
  Note (optional): [ called on 9 Dec                                   ]
  M. Hossain will be told that you recorded this answer.
                                                   [ Cancel ]  [ Record the answer ]
```

**W3. Resend** — "Resend the current invitation to: (•) everyone who has not replied (2) ( ) everyone (8) ( ) M. Hossain only." Refusal in words: "The invitation was sent to M. Hossain 4 minutes ago. You can resend it after 10:46."

## Flow X — Attendance at the meeting (secretary)

**X1. The register** (opens when the secretary starts the meeting; full screen on a tablet)
```
Attendance — BRC 3/2026                    Now: item 3  Minutes of the last meeting  [ Change ▾ ]
● Quorate: 5 of 7 voting members present, 4 needed · chair present · 3 independent
──────────────────────────────────────────────────────────────────────────────────────────
[ Mark everyone who said yes as present (4) ]
Members
 Dr F. Karim      Chair    ● In person since 10:02                      [ Left… ] [ ⋯ ]
 S. Ali           Member   ● Online since 10:05                         [ Left… ] [ ⋯ ]
 N. Chowdhury     Member   ○ Expected   [ In person ] [ Online ] [ Apologies ] [ Absent ]
 Demo Director    Member   Apologies (from their reply) — A. Rahman attends in their place
 M. Hossain       Member   Apologies (recorded by phone)
 R. Das           Member   ● In person since 10:40 · joined during item 3 (late)  [ ⋯ ]
 K. Secretary     Secretary● In person since 09:55
Alternates
 A. Rahman        for Demo Director  ● In person since 10:02 · counts for the number only
In attendance
 J. Begum         Guest (CFO)        ● In person since 10:02
 Head of Credit   Presenter, item 5  ○ Expected
──────────────────────────────────────────────────────────────────────────────────────────
History ▸  (arrivals, departures, corrections)
```
- **Mark everyone who said yes as present** asks once: "Mark 4 people present in the way they said (3 in person, 1 online)? You can change anyone afterwards."
- **Arrival and departure** buttons record "now" and the item in progress; ⋯ offers "at another time…" (time picker within the meeting's window), "Changed to online", "Stepped out for this item", "Correct an entry…".
- **Now: item n** is a menu of the published items in order; the secretary moves it as the meeting proceeds. It is optional; without it, times are used.
- **Stepped out for this item:** shown on the person's row for the current item: "Stepped out for item 5" with [ Back ]. When a member declared an interest in the item in progress, their row shows "Declared an interest in this item" and offers "Stepped out for this item".

**X2. Correct an entry** (during the meeting, and after it until the minutes are approved)
```
Correct an entry for R. Das
  Entry: Arrived in person at 10:40 during item 3
  What is wrong?  [ R. Das arrived at 10:20, during item 2                 ]
  [ ] Record the right entry now:  Arrived  (•) in person ( ) online  at [10:20]  item [2 ▾]
                                                 [ Cancel ]  [ Record the correction ]
```
- The wrong entry stays in the history, struck through, with who corrected it, when and why.

## Flow Y — Quorum (secretary, chair, members)

**Y1. The live banner** (secretary and chair, on the register, the agenda and E06's decision screen; refreshed every 15 seconds)
```
● Quorate — meeting: 5 of 7 present, 4 needed.  Item 5: 3 of 7 counted, 4 needed ✕
  Item 5 is not quorate: 2 members have declared an interest and do not count for it.
```
- **Quorum lost** (in-app notice to the chair and secretaries, and a polite live announcement on screen):
```
✕ The meeting is not quorate since 11:42 (during item 6): 3 of 7 present, 4 needed.
  No decision can be recorded until quorum is restored.
```
- Members and alternates see a summary on the meeting page during the meeting: "The meeting is quorate." or "The meeting is waiting for quorum."

**Y2. The per-item view** (from the banner or the agenda; officers)
```
Quorum for item 5 — Term loan, Delta Textiles
Needed: 4 (a majority of 7 voting members) · the chair or a vice-chair · at least 2 independent
Counted (3): Dr F. Karim (chair, independent), R. Das, A. Rahman (alternate for Demo Director)
Not counted:
  S. Ali — declared an interest in this item
  N. Chowdhury — stepped out for this item
  M. Hossain — apologies
Result: not quorate — 3 of 4 needed; independent members: 1 of 2 needed.
```
- An alternate is listed as counting for the number; the line explains "Alternates count for the number of members only, not as chair or as independent members."
- A secretary recused from the item sees counts only: "3 counted, 4 needed; 2 not counted because of declared interests."

**Y3. Recording a decision without quorum** (opened by E06's decision screen; only when the committee type allows it)
```
This item is not quorate: 3 of 4 needed.
The committee's rules allow a decision without a quorum only with a reason,
and it must be ratified at a later quorate meeting.
  Reason: [ Urgent: the borrower's facility expires on 15 December          ]
  The chair and the other secretaries will be told.
                                        [ Cancel ]  [ Record without quorum ]
```
- When the type does not allow it, E06's screen says: "No decision can be recorded: the item is not quorate (3 of 4 needed)."

## Flow Z — The committee type's Meetings tab (administrators; maker-checker)
```
Committee type: Board committee — version 4 (draft)
Settings │ Quorum │ Outcomes │ Agenda │ Meetings │ Changes
──────────────────────────────────────────────────────────────────────────────
Calendar invitations
  Title in the calendar:  (•) The committee's name   ( ) A neutral title: [ Board committee meeting ]
  (Use a neutral title for committees whose name should not appear in people's calendars.)
Replies
  Ask for replies by: [ 2 ▾ ] working days before the meeting   [x] Remind those who have not replied
  [x] Remind everyone attending the working day before the meeting
Decisions without a quorum
  (•) Never allowed
  ( ) Allowed with a reason, to be ratified at a later quorate meeting
──────────────────────────────────────────────────────────────────────────────
                                                    [ Save draft ]  [ Submit for review ]
```
- The **Quorum** tab's sentence (E01 flow J) gains, when "Alternates count" is on: "Alternates count for the member they replace, for the number of members only."

## Decisions taken under the delegated gate
- **Three answers, no "Maybe"**; the answer can be changed until the meeting starts; a note to the secretary.
- **The secretary records answers given by email, phone or in person,** and the member is told.
- **Only a new date or time asks for the answer again.**
- **Calendar replies are not read**; the invite says so in its first line.
- **Invites go to members, guests and alternates standing in**, not to item presenters (they use "Add to my calendar").
- **Apologies can bring the seat's alternate,** who reads what the member reads and is recused where the member is.
- **The register is prefilled** from replies; one action marks everyone who said yes present.
- **"Now: item n"** labels arrivals and departures and drives the per-item quorum.
- **Alternates count for the number only,** never as chair or as an independent member (ADR-0064).
- **A decision without quorum is a committee-type setting, off by default,** with a reason and "to be ratified" (ADR-0064; the overview had a tenant setting).
- **A neutral calendar title** per committee type.

## Questions for the owner
None about the screens. Two changes from the overview are reported (ADR-0064): the quorum override is a committee-type setting reviewed by a second administrator, not a tenant setting; and an alternate never meets a "chair or vice-chair" condition or an "independent members" condition, and does not stand in for an item their member is recused from.

## Owner input: mailboxes and a relay for the recorded run (T9)
The one real-world input E05 needs (Phase 4 Q5 answer (a)). **When:** once invitations and replies (T2, T3) are deployed to dev; the agent will ask then. **What:**
1. **An Outlook mailbox** (Microsoft 365 preferred, or Outlook.com), read in Outlook on the web and, if available, Outlook for Windows.
2. **A Gmail mailbox** (gmail.com or Google Workspace) with Google Calendar; tell the agent its "Add invitations to my calendar" setting.
3. **Optional:** Apple Calendar on a Mac, iPhone or iPad.
4. **An SMTP relay** dev may use for one run: host, port, STARTTLS or TLS, user name and (app) password, and a from address on a domain with SPF and DKIM, allowed to send outside.

**How the credentials are kept:** in a dedicated test tenant on dev (not the demo tenant). You type the relay password yourself into that tenant's Mail settings, where it is encrypted with the tenant's key and never shown or logged (ADR-0030). It never goes into the repository, environment files, Ansible, CI, fixtures or the chat. Your mailbox passwords are never asked for. The relay is deleted after the run. Full details: `tasks.md`, "Owner input".

**About 30 minutes of your time.** If you cannot provide these then, the check is carried to the first customer (Phase 4 Q5).

## Review
| Flow | Date | Result |
|---|---|---|
| V invitation and reply (members) | 2026-11-18 | Delegated (Phase 4 Q1); reviewed by the agent against the research and E03's principles |
| W invitations and replies (secretary) | 2026-11-18 | Delegated (Phase 4 Q1) |
| X attendance at the meeting (secretary) | 2026-11-18 | Delegated (Phase 4 Q1) |
| Y quorum (secretary, chair, members) | 2026-11-18 | Delegated (Phase 4 Q1) |
| Z the committee type's Meetings tab | 2026-11-18 | Delegated (Phase 4 Q1) |
| Manual keyboard and screen-reader pass over V and X | 2026-11-21 | The agent's keyboard and structure pass done (T10, below), two defects fixed; the screen-reader pass by a person is carried (checklist below) |

## Keyboard and screen-reader pass (T10, 2026-11-21)
Done by the agent in Chromium (Playwright, headless) against a local E2E stack with the T10 demo seed. Flow V: a hybrid Board Risk Committee meeting as Demo Director (answering, changing to apologies with the alternate), Demo Head of Credit as the alternate, and the company secretary's Invitations and replies tab; the seeded "Quarterly risk review" (the stale answer, the Chairman recorded by phone). Flow X: a Management Credit Committee meeting started by the secretary (the register, the item in progress, a departure that loses the quorum and the return that restores it, "Quorum by item…") and the seeded held "Monthly credit review". A screen reader cannot be driven from there, so the spoken output is for a person (below).

### What the agent checked, and found
- **axe** (WCAG 2.2 A/AA, serious and critical): nothing on the meeting page with "Will you attend?" before and after answering, the apologies dialog, the Invitations and replies tab, the register with the quorum banner, and "Quorum by item…", in English and Arabic. The journeys `meeting-replies.spec` and `meeting-attendance.spec` keep this on every run (page-wide checks from the top of the page, `checkPageA11y`).
- **Structure, flow V:** a region "Will you attend?" with its H2, "Please reply by …", a group "Will you attend?" of three buttons, the "Add to my calendar" link and its hint. When an answer is changed the current one is `aria-pressed="true"`. "Your answer is saved." is in a status region inside the card. The apologies dialog is a native modal `<dialog>` named "No, I send my apologies", with "Your alternate is Demo Head of Credit." as the name of its radio group and the alternate checked by default.
- **Structure, flow X:** H2 "Attendance — MCC n/2026", "Now: item n …", the counts, then the tables "Members" and "In attendance", each with a caption, column headers and the person's name as row header. Every row button carries the person ("In person: Demo Director", "Left: Demo Head of Finance", "More for Demo Director"). The quorum banner is a region "Quorum" with its H2, a polite status region, the sentence, the unmet conditions as a list and "Quorum by item…".
- **Tab order** follows the reading order with a visible 2 px focus ring on every stop. Flow V: skip link, navigation, top bar, "Back to Board Risk Committee", "Join the meeting (opens in a new tab)", the three answers, "Add to my calendar". Flow X: after the bulk action, row by row: the state's one-press buttons, then "More for …".
- **Keyboard only:**
  - **flow V:** Enter on "Yes, online" saves and moves focus to "You said: Yes, online (…)". "Change my answer" moves focus to the current answer. "No, I send my apologies" opens the dialog with focus on Close; Tab goes Close → the alternate's radio → the note → Cancel → "Send my apologies"; Escape closes it with focus back on "No, I send my apologies". After sending, the status says who was asked to attend;
  - **flow X:** "Start the meeting…" from the meeting's ⋯ menu lands on the Attendance tab. "Mark everyone who said yes as present (3)" asks once. "In person: …" moves focus to the person's new state line ("In person since 5:42 PM · joined late"). "More for …" and "Change the item" open menus with focus on their first entry and arrow keys between entries. Escape closes them.
- **Targets:** the answers are 48 px high and "Add to my calendar" 44 px; the register's buttons 44 px high and "More for …" 44 × 44. The dialog's radio buttons are 20 px, each inside its label, a row at least 44 px high (as in E04).
- **Reflow at 400 %** (320 CSS px wide), after the fix below: nothing scrolls sideways on the member's meeting page, the Invitations and replies tab or the register; the tables scroll inside their own boxes, which WCAG 1.4.10 allows for data tables. Both journeys now check it.

### Defects found and fixed here
1. **The officers' meeting page scrolled sideways at 400 % zoom** (WCAG 1.4.10), by 400 px on the Invitations and replies tab and 336 px on the register. Three causes:
   - the tab list (Agenda, Attendance, Invitations and replies, Declarations) did not wrap;
   - the register's "History: arrivals, departures and corrections (n)" button did not wrap;
   - each table's visually hidden "Actions" column header is absolutely positioned, and its scroll box was not a positioning context, so the header was placed against the page, 700 px from the left.

   The tab list wraps (`MeetingPage.tsx`), the History button wraps (`AttendancePanel.tsx`), and both tables' scroll boxes are `relative` (`AttendancePanel.tsx`, `InvitationsPanel.tsx`).
2. **The Invitations and replies tab left out members who cannot hold an invitation** (found with the demo's Chairman, whose account cannot sign in). The panel listed only the people invited or invitable, while the replies counted everyone who answers the meeting. The Chairman's answer recorded by phone was counted ("1 yes") but had no row, and a secretary could not record an answer for such a member from the tab at all. The panel now lists everyone who answers the meeting. Someone without an invitation is shown as "No invitation: they cannot sign in or are not cleared for this meeting", with "Record an answer…" (`InvitationPanel.kt`, `InvitationsPanel.tsx`, new message en/ar; `RepliesIT`).

### Observed, not changed
- **Changing the language redraws the meeting page on its default tab** (Agenda while scheduled, Attendance while the meeting runs). A secretary who switches language on the Invitations and replies tab has to choose the tab again.
- **Tab past the last control of a dialog** goes to the browser's own toolbar and comes back to the dialog's first control. That is the native modal `<dialog>` (ADR-0021): the page behind stays inert, so this is not a keyboard trap.
- **Other tables with hidden text in a scroll box** may have the same overflow as defect 1 where a hidden header sits far right; those the journeys check do not. A shared table scroller would prevent it; left for a later sweep.

### For a person with a screen reader (still to do)
Run with NVDA + Firefox and VoiceOver + Safari (macOS and iPad), in English and Arabic. Use the seeded "Quarterly risk review" (Demo Director has sent apologies with Demo Head of Credit standing in; Demo Board Member's answer is for the old time; the Chairman's was recorded by phone), Demo Head of Credit as the alternate, the company secretary for the Invitations and replies tab, a new meeting started by the secretary for the live register (as `meeting-attendance.spec` does), and the seeded held "Monthly credit review" for the register after the meeting.
1. **"Will you attend?":** the region and the group are announced once; the three answers read as buttons, and when changing an answer the current one is read as pressed.
2. **After answering:** "You said: Yes, online (…)" is read where focus lands; "Your answer is saved." is announced once, not twice.
3. **The old time** (Demo Board Member): "You replied “Yes, in person” for the old time (…). The meeting is now on … Will you attend?" reads in order before the answers.
4. **Apologies:** the dialog's name and "Your alternate is Demo Head of Credit." are read; the two choices read as a radio group with the alternate chosen; the note's hint is read with the field; after "Send my apologies" the status ("… has been asked to attend in your place …") is announced and focus lands somewhere sensible.
5. **"Add to my calendar"** is announced as a link that downloads a file, with its hint read after it.
6. **The alternate** (Demo Head of Credit): "You are attending in place of Demo Director." is read before the answers.
7. **Invitations and replies:** the table's caption and headers are read with each cell; "Apologies — alternate attends" on Demo Director's row and "Alternate for Demo Director" on the next row make the link clear; "No invitation: …" on the Chairman's row makes sense; "Record an answer for …" reads its two groups ("Answer", "How did they tell you?") as groups, the required error with its field, and "The answer is recorded." is announced.
8. **The live register:** "Attendance — MCC n/2026" and "Now: item n …" are read; each row's buttons name the person; after a press the person's new state is read where focus lands; "Mark everyone who said yes as present" reads the count and the way people said they attend; the History toggle reads expanded or collapsed.
9. **The quorum banner:** "Quorum lost: … No decision can be recorded until quorum is restored." and "Quorum restored: …" are announced politely, once each, without cutting off what is being read; the 15-second refresh announces nothing when nothing changed.
10. **"Quorum by item…":** the item select, then "Needed: n of m", the counted names and the "Not counted:" reasons read as lists, and the result.
11. **The held register** (Monthly credit review): "The meeting is over: what you record now corrects the register, with a reason, …" is read; "Record a missed entry…" reads its reason as required; the History reads voided entries with "Voided:" first.
12. **Arabic:** right to left; Latin names, times and "MCC 1/2026" keep their direction inside Arabic sentences; numbers in the quorum sentences read naturally.
13. **Zoom and contrast by eye:** 200 % and 400 % in a real window, light and dark themes: the wrapped tab list, the focus rings on the answers and the register's buttons, the quorum state shown in words with its icon, never by colour alone.
