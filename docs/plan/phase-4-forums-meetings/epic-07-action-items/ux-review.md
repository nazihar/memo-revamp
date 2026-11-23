# E07 Action items — UX review

> `AGENTS.md §5` Step 3. **The owner delegated these screens.** Phase 4 Q1 sends only the E01, E02/E03 and E06 flows to the owner before build. E07's screens are reviewed under the delegated gate, so the review table below records "delegated", and the web tasks (T10, T11) do not wait.
>
> The wireframes are structural; the look is the existing Ledger direction and its components. "Committee" is the default English word for a forum. Flows continue from E06's AI:
> - **AJ** raising actions (secretary);
> - **AK** "My actions";
> - **AL** the action page (owner);
> - **AM** verification;
> - **AN** the committee's action tracker;
> - **AO** matters arising, the action taken report;
> - **AP** the follow-up memo;
> - **AQ** the committee type's Actions tab;
> - **AR** notifications and emails.

## Design principles taken from the research
1. **One owner, one date, one next step.** Every action says who, by when and what happens next, in words: "Head of Treasury (A. Karim) — due 12 December — waiting for an update" (CGI; research §3.1).
2. **Colour is never alone.** Every RAG status has its word and an icon, and the key is on the page (WCAG 1.4.1; research §7).
3. **The secretary can record what they are told.** Updates "on behalf, by phone" are normal, as in E05 (principle 3).
4. **Nothing is lost and nothing is rewritten.** Dates show where they started ("originally 15 December, revised once"). Updates are a dated timeline. Corrections are visible.
5. **Fewer, better messages.** One email a day at most for reminders. Escalations at once. No action text in any email (research §4.1 point 8).
6. **For the director: big, plain, calm** (E03 principle 5). 44 px targets. One primary button per action. A director with no actions sees nothing new.
7. **The next meeting sees the truth** in a fixed report: what needs the committee first, then the rest (research §3.3).

## Flow AJ — Raising actions (secretary)

**AJ1. On the decision panel** (E06 flow AA): under a recorded decision
```
5  Credit facility — Karnaphuli Textiles Ltd                       For decision
   ✓ APPROVED WITH CONDITIONS — Resolution MCC/2026/001 (at the meeting's end)
   Conditions
     1. Security over the new spinning unit before drawdown.   [ Make this an action ]
     2. Quarterly covenant report to the committee.            [ Make this an action ]
   ──────────────────────────────────────────────────────────────────────────────
   Actions from this item (1 draft)
     Draft · Report covenant compliance quarterly · Head of Credit Risk · by 12 Mar 2027  [ Edit ]
   [ + Add an action ]
```
- Items without a decision ("For discussion", "For noting") have "+ Add an action" too.
- The meeting page has "Actions from this meeting" for actions not tied to an item.

**AJ2. The action form** (a modal dialog, ADR-0021)
```
New action — from item 5, condition 2
──────────────────────────────────────────────────────────────────────────────
What is to be done *        [ Report covenant compliance to the committee quarterly       ]
Details                     [                                                              ]
What the owner is told      [ Quarterly covenant report to the committee.                  ]
  ⓘ The owner will see this text. They will not see the item's papers or the discussion.
Owner *                     ( ) A person   (•) A position    [ Head of Credit Risk ▾ ]
                            Now held by S. Chowdhury
Also involved (optional)    [ + Add a person ]   up to 5; they can post updates
Due *                       ( ) By the next meeting (Thursday 12 December 2026)
                            (•) On a date  [ 12/03/2027 ]  (type a date or pick one)
Priority                    (•) Normal  ( ) High
Level                       Confidential (from the item)   [ Change ]
                                              [ Cancel ]  [ Save as draft ]  [ Save and send to the owner ]
```
- **Warnings in words:**
  - "S. Chowdhury declared an interest in item 5. They will see this action, but not the item."
  - "A. Rahman is not cleared for Confidential and cannot own this action."
- **The due date** accepts typed dates in the tenant's format and day-month-year. The error says "Please enter a date on or after today, for example 12/03/2027".
- "Save and send to the owner" is offered once the meeting is held. During the meeting there is only "Save as draft".

**AJ3. "Mark the meeting as held"** (E06's dialog gains one line)
```
Mark BRC 3/2026 as held
  • 4 decisions will be numbered as resolutions.
  • 2 memos will move on with their committee's decision.
  [✓] Send the 5 draft actions to their owners now
                                                      [ Cancel ]  [ Mark as held ]
```
- Afterwards the meeting page shows "5 actions sent to their owners". Any left as drafts show as "3 draft actions not sent — [ Review and send ]".

## Flow AK — "My actions" (everyone)
In the inbox, beside "My tasks", with a count badge ("My actions 3").
```
My actions
 [ To do (3) ]  [ To verify (2) ]  [ I'm involved (1) ]
──────────────────────────────────────────────────────────────────────────────
 ▲ Overdue      BRC A9/2026   Revise the liquidity contingency plan
                Board Risk Committee · due 28 Nov (originally 14 Nov, revised once)     [ Open ]
 ● Due soon     MCC A1/2026   Report covenant compliance quarterly
                Management Credit Committee · due 5 Dec                                  [ Open ]
 ✓ On track     BRC A14/2026  Present the revised collateral policy
                Board Risk Committee · due 12 Mar 2027                                   [ Open ]
──────────────────────────────────────────────────────────────────────────────
 Key: ▲ Overdue or off track  ● Due within 10 working days, at risk, or no recent update
      ✓ On track  ◐ Done, to be verified
```
- Sorted by due date, overdue first. "To verify" lists actions waiting for the viewer's verification (or their pool's).
- **Empty state:** "You have no actions. Actions given to you by a committee will appear here."

## Flow AL — The action page (owner)
```
BRC A9/2026 · Board Risk Committee                                ▲ Overdue by 3 working days
Revise the liquidity contingency plan
──────────────────────────────────────────────────────────────────────────────
Owner        Head of Treasury (A. Karim)          Due   28 November 2026
Also         R. Hasan                                   originally 14 November, revised once
From         BRC 3/2026, item 7 — Resolution BRC/2026/011
What the committee asked:  "Revise the contingency funding plan to reflect the new stress scenarios."
──────────────────────────────────────────────────────────────────────────────
 [ Post an update ]   [ Ask for a new date ]   [ Raise a memo ]   [ Mark as done ]
──────────────────────────────────────────────────────────────────────────────
Timeline
 26 Nov  A. Karim — "Draft plan with Treasury Ops; final by 2 Dec."   At risk
 14 Nov  Due date changed to 28 Nov by the secretary (S. Ahmed): "Stress results delayed."
 10 Nov  R. Hasan — "Stress scenarios received from Risk."             + scenarios.xlsx
  1 Nov  Sent to Head of Treasury by the secretary
```
- **AL2. Post an update** (dialog): "What has happened?" in a large text box. "How is it going?" as a radio group: On track, At risk, Off track. "Attach files" (optional).
- **AL3. Ask for a new date:** "New date" and "Why" (required). "The secretary will be asked to agree. Until then the date stays 28 November."
- **AL4. Mark as done:** "What was done" (required) and evidence ("Required by this committee" when the type says so). Then: "Sent to the committee secretary to verify. You will be told when it is closed."
- A recused owner sees the page the same way. The "From" line shows only "BRC 3/2026, item 7" when the title is hidden from them, with no link to the item.

## Flow AM — Verification (secretary or chair)
```
Verify BRC A9/2026 — Revise the liquidity contingency plan              ◐ Done, to be verified
──────────────────────────────────────────────────────────────────────────────
Done by A. Karim on 2 Dec: "Plan revised and approved by ALCO on 1 Dec."
Evidence:    CFP-2026-v3.pdf (open)
Follow-up memo:  MEMO/2026/0412 "Revised contingency funding plan" — Approved on 1 Dec
──────────────────────────────────────────────────────────────────────────────
Note (optional for closing, required to send back)  [                                   ]
                                  [ Send it back to the owner ]  [ Close the action ]
```
- **AM2. At the meeting:** on the "Matters arising" item, the report's "Proposed to close" section has checkboxes and "Close the actions the committee agreed (4)". The remark "Agreed at BRC 4/2026, item 3" is added for them.
- A verifier who is also the owner never sees this screen for their own action. "Another secretary will verify this" shows instead.

## Flow AN — The committee's action tracker (secretary, chair, members)
```
Board Risk Committee — Actions                                  [ New action ]  [ Export ]
 Status [ Open ▾ ]  Colour [ All ▾ ]  Owner [ Anyone ▾ ]  Meeting [ Any ▾ ]  [ Search actions… ]
 ▲ Overdue 3   ● Due soon or at risk 2   ✓ On track 7   ◐ To verify 2              [ Ask owners for an update ]
──────────────────────────────────────────────────────────────────────────────
 Ref           Action                               Owner                  Due              Status
 BRC A9/2026   Revise the liquidity contingency…    Head of Treasury       28 Nov (rev. 1)  ▲ Overdue
 BRC A11/2026  Board training on climate risk       Company Secretary      2 Dec            ● At risk
 …
──────────────────────────────────────────────────────────────────────────────
 Key (this committee): ▲ past its date or off track · ● due within 10 working days, at risk, or no update for
 20 working days · ✓ on track · ◐ done, to be verified
```
- A row opens the action page, which has the officer's extra buttons: "Change the date", "Change the owner", "Set the colour" (with a reason and an optional end date), "Cancel the action", "Reopen", "Keep out of the next report".
- **Cancel** offers: No longer needed; Replaced by another action; Duplicate; **Risk accepted by the chair** (the chair, or "recorded for the chair" with how it was agreed).
- **Members** see the same list without the officer buttons or export. Nothing from items they declared an interest in is shown, and the counts do not include it.
- On a phone, each row becomes a card with the status word first.

## Flow AO — Matters arising: the action taken report (members)
On the next meeting's agenda:
```
3  Matters arising and actions                                                 For noting
   Action taken report (as published on 28 November)
   2 proposed to close · 3 overdue · 9 open · 4 closed since the last meeting   [ Open the report ]
```
```
Action taken report — Board Risk Committee, BRC 4/2026                          [ Print ]
Built when the agenda was published on 28 November 2026 at 16:05
──────────────────────────────────────────────────────────────────────────────
1. Proposed to close (2) — the committee is asked to agree
   BRC A9/2026   Revise the liquidity contingency plan   Head of Treasury   ◐ Done 2 Dec
                 "Plan revised and approved by ALCO on 1 Dec."  Memo MEMO/2026/0412 approved
2. Overdue (3)
   BRC A5/2026   …                                                         ▲ due 30 Oct (rev. 2)
3. Open (9)  …
4. Closed since the last meeting, for noting (4)  …
5. Cancelled since the last meeting, for noting (1)
   BRC A2/2026   …   Risk accepted by the chair on 20 Nov: "Superseded by the new risk appetite."
6. Corrections to earlier minutes (1)  …
```
- "Print" gives the reader's rows as a PDF, for directors who read on paper.

## Flow AP — The follow-up memo (owner)
```
Raise a memo from BRC A9/2026
  Memo type *        [ General memo ▾ ]   (the types you may create)
  Subject            [ Revised liquidity contingency plan              ]
  Level              Confidential (from the action)   [ Change ]
                                              [ Cancel ]  [ Create the draft memo ]
```
- The memo opens as a draft from the type's template. At the top it says: "Raised from action BRC A9/2026 (Board Risk Committee)", with a link for people who can see the action.
- The action page lists "Memo MEMO/2026/0412 — In approval" and later "Approved on 1 Dec. You can now mark the action as done."
- "Link an existing memo" offers a search over memos the person can read.

## Flow AQ — The committee type's Actions tab (administrators; maker-checker)
```
Committee type: Board committee — version 4 (draft)          Changes are reviewed by a second administrator
 [ General ] [ Members ] [ Agenda ] [ Meetings ] [ Minutes ] [ Actions ]
──────────────────────────────────────────────────────────────────────────────
Amber when due within        [ 10 ] working days
"No recent update" after     [ 20 ] working days   [✓] use this rule
A new due date needs         (•) the secretary's agreement  ( ) the chair's  ( ) only a reason
Actions are verified by      (•) a secretary  ( ) the chair
Evidence when marking done   [ ] required
Default due date             (•) the next meeting  ( ) [ 20 ] working days
Ask owners for an update     [ 5 ] working days before the papers are due   [✓]
Reminders and escalation     follow the workflow "Action items (built in)"   [ Open in the builder ]
```

## Flow AR — Notifications and emails
- **AR1. A new action** (subject without parameters: "A committee action for you")
```
Dear A. Karim,
The Board Risk Committee has given you an action, due 12 March 2027 (reference BRC A14/2026).
  [ Open the action in memo ]
```
- **AR2. The daily digest** (subject: "Your committee actions"), at 09:00 on working days, only when there is news:
```
You have committee actions that need attention:
  Overdue:        BRC A9/2026
  Due soon:       MCC A1/2026
  Sent back to you: BRC A11/2026
  [ Open My actions ]
```
- **AR3. Escalation** (subject: "An overdue committee action"), to the owner's supervisor:
```
An action of the Board Risk Committee held by A. Karim (Head of Treasury) is 10 working days overdue
(reference BRC A9/2026, due 28 November).  [ Open in memo ]
```
- **In-app items** say the same with the reference, never the action's title.

## Decisions taken under the delegated gate
- **Drafts first, issued by the secretary,** with "Send the drafts" at "Mark as held" ticked by default.
- **One owner** (a person or a position) and up to five people involved.
- **Owners anywhere in the organisation, not only members;** no external accounts.
- **RAG in words with icons;** the key per committee type on every list and the report.
- **The owner asks for a new date; the secretary agrees** (default), with the original date always shown.
- **Closure verified by someone other than the owner;** closures can be agreed at the meeting in one step.
- **"Risk accepted" is a cancellation only the chair makes,** or the secretary records for the chair.
- **A follow-up memo does not close the action;** the owner is told to mark it done.
- **The action taken report is fixed when the agenda is published,** with the sections that need the meeting first, and printable.
- **One digest email a day at most;** escalations at once; no action text in email.

## Questions for the owner
None about the screens. Two refinements of the overview are reported (ADR-0067):
- the RAG thresholds are per committee type (still tenant configuration, reviewed by a second administrator), not one tenant rule;
- the whole action runs on the workflow engine (the owner's period and its verification), not only its closure, which needs four general engine additions (ADR-0068).

**No real-world input is needed.**

## Review
| Flow | Date | Result |
|---|---|---|
| AJ raising actions (secretary) | 2026-11-21 | Delegated (Phase 4 Q1); reviewed by the agent against the research and E03's principles |
| AK "My actions" | 2026-11-21 | Delegated (Phase 4 Q1) |
| AL the action page (owner) | 2026-11-21 | Delegated (Phase 4 Q1) |
| AM verification | 2026-11-21 | Delegated (Phase 4 Q1) |
| AN the committee's action tracker | 2026-11-21 | Delegated (Phase 4 Q1) |
| AO matters arising, the action taken report | 2026-11-21 | Delegated (Phase 4 Q1) |
| AP the follow-up memo | 2026-11-21 | Delegated (Phase 4 Q1) |
| AQ the committee type's Actions tab | 2026-11-21 | Delegated (Phase 4 Q1) |
| AR notifications and emails | 2026-11-21 | Delegated (Phase 4 Q1) |
| Manual keyboard and screen-reader pass over AN and AL | 2026-11-24 | The agent's keyboard and structure pass done (T12, below), two defects fixed; the screen-reader pass by a person is carried (checklist below) |

## Keyboard and screen-reader pass (T12, 2026-11-24)
Done by the agent in Chromium (Playwright, headless) against a local E2E stack with the T12 demo seed: flow AN as the company secretary on the Board Risk Committee's Actions tab, and flow AL on two action pages (the secretary on BRC A2, overdue and escalated; Demo Director on BRC A1, done and waiting for verification). The journeys `action-items.spec` and `committee-cycle.spec` drive the rest of AJ, AK, AL, AM, AO and AP with axe on every page and dialog on the way. A screen reader cannot be driven from there, so the spoken output is for a person (below).

### What the agent checked, and found
- **axe** (WCAG 2.2 A/AA, serious and critical): nothing on the tracker, the action page (owner, contributor-less owner, secretary), the update, date request, date answer, raise-a-memo, mark-as-done and verify dialogs, the decision panel's action form, and the action taken report. The journeys keep this on every run.
- **Tab order, the tracker (AN):** after the shell and the committee page's tabs, the Actions tab panel (one stop, the ARIA tab pattern), "New action", "Export", the filters as native selects in reading order (Status, Colour, Owner, Meeting), "Search actions", "Sort by", the totals as toggle buttons read with their words and counts ("Overdue or off track: 1", "Due soon or at risk: 0", "On track: 1", "To verify: 1"), "Ask owners for an update", then each row's reference link ("BRC A2/2026"). Every control is 44 px high; a visible 2 px focus ring on every stop.
- **Tab order, the action page (AL, AN):** "Open the meeting", then the person's own buttons ("Post an update", "Ask for a new date" or "Change the date", "Link an existing memo", "Mark as done" or "Verify"), then the officers' region ("Edit", "Record an update for the owner", "Change the owner", "Set the colour", "Keep out of the next report", "Cancel the action"). 44 px targets, the 2 px ring.
- **Dialogs:** each opens as a modal with focus on Close; Escape closes it and focus returns to the button that opened it ("Change the owner"). After a change the page says what happened in a live region that takes focus ("Your update was posted.", "The new date was agreed. The owner is told.", "The action is closed. The owner is told.").
- **Reflow at 400 %** (320 CSS px): nothing scrolls sideways on the tracker (rows become cards) or the action page.
- **Colour never alone:** every colour on the tracker, the totals, the action page and the report is a word with an icon ("Overdue ▲", "Done, to be verified ◐", "Cancelled ⨯").

### Defects found and fixed here
1. **"Post an update" and "I have started" offered to a secretary who is not the owner** (flow AN on BRC A2): the server's abilities counted a secretary as able to update and start, but both endpoints take a secretary only when writing for the owner, so pressing either answered "You may not do this". `ActionAbilities.update` is now the owner or a contributor and `start` the owner (`ActionAbilityRules`); a secretary writes for the owner with "Record an update for the owner" (`updateOnBehalf`), as before.
2. **The follow-up memo missing on the way back** (flow AP, found by `committee-cycle.spec`): after "Raise a memo", following "Raised from action …" from the new memo back to the action within five seconds showed "No memo follows up this action yet." from the cache. The dialog now stores the action the server answers with and refreshes the timeline before opening the memo (`FollowUpMemos.tsx`).

### Observed, not changed
- **The owner of an action waiting for verification** still has "Post an update" and "Ask for a new date": the server allows both until the action is verified (a date request is withdrawn when it is marked as done, not refused after).
- **The committee page's "All committees" link** is 20 px high (E01's breadcrumb, not this epic's screens); it is a text link at the top of the page, well spaced.
- **After the last row** focus goes to the document and then the skip link, as on every page.
- **After moving to another page** focus starts at the top of the document, as everywhere in the app; the skip link is the first stop.

### For a person with a screen reader (still to do)
Run with NVDA + Firefox and VoiceOver + Safari (macOS and iPad), in English and Arabic. Use the demo seed: the company secretary on the Board Risk Committee's Actions tab (BRC A1–A4) and on the quarterly risk review's action taken report; Demo Director on BRC A1 (done, to be verified) and in "My actions"; Laila Arjumand (Head of Credit Risk) on MCC A1 (closed with evidence and an approved follow-up memo) and BRC A2 (overdue, escalated).
1. **The tracker's totals** read as toggle buttons with their state ("pressed") and their words and counts; choosing one is announced by the list changing size.
2. **The rows** read the reference, the title, the colour in words, the owner ("Head of Credit Risk (Laila Arjumand)"), and the date with "(rev. 1)" where it was moved.
3. **The key** under the tracker and the report reads the committee's numbers ("due within 10 working days").
4. **The action page's header** reads the reference and committee, the colour in words, "Overdue by n working days", the owner and the date with where it started.
5. **Each dialog** reads its name, its fields' labels and hints ("What has happened?", "How is it going?" as a radio group, "New date", "Why"), and its refusal in words where focus lands.
6. **After each change** the live region's sentence is read once.
7. **The timeline** reads each entry in words with who wrote it and when, evidence files by name, and corrections under what they correct.
8. **The action taken report** reads its sections in order with their counts, each row's facts, the follow-up memos "as they stand now", and "held back" to officers only.
9. **The follow-up memo:** "Raise a memo from MCC A1/2026" reads the type chooser and the level with its confirmation; the memo page reads "Raised from action …" as a link.
10. **Arabic:** right to left; references ("BRC A2/2026"), names and English titles keep their own direction inside Arabic sentences; the icons sit at the right end of the colour words.
11. **Zoom and contrast by eye:** 200 % and 400 % in a real window, light and dark: the colour badges, the totals' pressed state, the focus rings.
