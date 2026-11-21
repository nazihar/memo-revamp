# E04 Conflicts of interest and recusal — UX review

> `AGENTS.md §5` Step 3. **The owner delegated these screens.** Phase 4 Q1 sends only the E01, E02/E03 and E06 flows to the owner before build; E04's screens are reviewed under the delegated gate, so the review table below records "delegated" and the web tasks (T6–T8) do not wait. Wireframes are structural; the look is the existing Ledger direction and components. "Committee" is the default English word for a forum. Flows continue from E03's R: S declaring (members), T managing declarations (secretary and chair), U the register of declarations.

## Design principles taken from the research
1. **The declaration is the protection.** Declaring withholds the papers at once; nobody has to remember to change permissions (research §4: every portal makes it a separate administrator task).
2. **The safe choice is the default and is said in words:** "I will leave the room for this item (recommended). You will not receive its papers" (EBA ¶125, ORIC, the ABA).
3. **Asking the chair is possible, and safe while waiting:** papers are withheld until the chair decides, and the screen says so (fail-closed, ADR-0062).
4. **Nothing to re-declare:** interests on the memo and from an earlier meeting for the same paper are already there, marked "carried over".
5. **One confirmation per meeting** ends the reminders; a later revision asks only about new items (CGI §5.2.2: an actual review, not boilerplate).
6. **For the director: big, plain, calm** (E03 principle 5): 44 px targets, one question per item, no codes.
7. **The secretary records what was said in the room** in one dialog, because many directors will tell the secretary rather than use the app.

## Flow S — Declaring (members)

**S1. The meeting page with declarations** (E03 P1, with E04's additions marked ◆)
```
Board Risk Committee — BRC 3/2026
Thursday 12 December 2026 · 10:00–13:00 Dhaka time
──────────────────────────────────────────────────────────────────────────────
◆ Declarations of interest: please read the agenda and tell us about any interest.
◆ 9 items to review                         [ Review and declare ]
──────────────────────────────────────────────────────────────────────────────
Opening
 1  Welcome and apologies                                 10:00   For noting
 2  Declarations of interest                              10:05   For noting
◆    S. Ali — will leave the room for item 5
◆    You — will leave the room for item 6
 3  Minutes of the last meeting                           10:10   For decision
     [ 📄 Minutes of BRC 2/2026 · 6 pages ]  ✓ opened        [ Declare an interest ]
For decision
 5  Term loan, Delta Textiles                             10:25   For decision
     Presenter: Head of Credit · Late paper (chair agreed)
     [ 📄 Paper: CR/HO-CR/2026-27/0042 · 9 pages ]
     [ 📄 Security valuation report · 5 pages ]           [ Declare an interest ]
 6  Market risk limits                                    10:55   For decision
◆    You declared an interest. You will be asked to leave the room for this item.
◆    Its papers are not shown to you.                      [ My declaration ]
 7  Liquidity stress results                              11:15   For decision
◆    Waiting for the chair's decision on your declaration.
◆    Its papers are withheld until the chair decides.      [ My declaration ]
```
- **"Declare an interest"** sits on every item the member can see, as a secondary button.
- **The recused line** keeps the item's number, time, purpose and title (unless the item hides it: "6 Restricted item"), so the member knows when to step out. Nothing else of the item is shown.
- **The Declarations standing item** lists the declarations on items the viewer can see: names and what they will do. The nature is not shown to other members.

**S2. Declare an interest** (side sheet; full screen on a phone)
```
Declare an interest — item 5, Term loan, Delta Textiles
Tell the committee about any interest that could affect, or seem to affect,
your judgement on this item. For example: you, a family member or a close
associate works for, owns part of, or lends to a company in this item.

What is your interest?
[ My brother-in-law is a director of Delta Textiles.                    ]
  (only the secretaries, the chair and auditors see this)

What would you like to do?
(•) I will leave the room for this item. (recommended)
    You will not receive its papers or take part in its decision.
( ) I ask the chair to decide whether I may take part.
    Until the chair decides, the papers are withheld from you.

                                         [ Cancel ]  [ Declare ]
```
- After **Declare**: "Thank you. The secretary and the chair have been told. You will not see this item's papers." The page returns to S1 with the recused line.
- Fields keep 44 px targets; the text box grows; errors say what to do ("Please say what your interest is").

**S3. Review and declare** (from the banner; a checklist of the items to review)
```
Review the agenda for 12 December — declarations of interest
For each item, choose only if you have an interest. Items you do not mark need nothing.
 1  Welcome and apologies                              [ I have an interest ]
 3  Minutes of the last meeting                        [ I have an interest ]
 5  Term loan, Delta Textiles                          ✓ Declared — leaving the room
 6  Market risk limits                                 [ I have an interest ]
 7  Liquidity stress results                           [ I have an interest ]
 8  Committee terms of reference (consent)             [ I have an interest ]
──────────────────────────────────────────────────────────────────────────────
[ I have declared my interests in this agenda ]
  You will be asked again only if new items are added.
```
- **"I have an interest"** opens S2 for that item.
- **The confirmation** records the revision ("Confirmed on 9 Dec for revision 2"). The banner disappears. A later revision that adds items brings back "2 new items to review".
- **One reminder** goes on the working day before the meeting to those who have not confirmed: "Please review the agenda for the Board Risk Committee meeting on 12 December and declare any interests." No item names in the email.

**S4. My declaration** (from the recused line)
```
Your declaration — item 6, Market risk limits
Declared on 9 Dec 2026, 14:10 · by you in the app
"I am a non-executive director of a counterparty in the limit review."
You chose: I will leave the room for this item.

[ Ask the chair to decide instead ]   [ Withdraw this declaration… ]
```
- **Ask the chair to decide instead** keeps the papers withheld until the chair rules.
- **Withdraw** asks for a reason and says: "The secretary and the chair will be told, and the withdrawal will be in the minutes." [Cancel] [Withdraw]
- **Carried over:** "Carried over from memo CR/HO-CR/2026-27/0042, where an interest was recorded for you on 2 Dec." or "Carried over from BRC 2/2026, where this paper was deferred."
- **Recorded for you:** "Recorded by Company Secretary on 9 Dec, from your email of 8 Dec." The member is told by notification when this happens.

## Flow T — Managing declarations (secretary and chair)

**T1. The Declarations panel** (a tab on the meeting for officers: Agenda │ Declarations │ …)
```
BRC 3/2026 — Declarations of interest                          Revision 2 published
Reviewed: 5 of 7 members     Not yet: N. Rahman, K. Hossain   [ Remind them now ]
──────────────────────────────────────────────────────────────────────────────
Item 5  Term loan, Delta Textiles
  S. Ali       Leaving the room        "Brother-in-law is a director of Delta…"   ⋯
               ⓘ Also reads the memo through the memo's own access (approver).
Item 6  Market risk limits
  Demo Director Leaving the room       "Non-executive director of a counterparty…" ⋯
  K. Hossain   Carried over from the memo (leaving the room)                      ⋯
  ⚠ Even if every member attends, item 6 cannot be decided: 3 of 7 members have
    declared an interest and the committee needs 4 (members with an interest do not count).
Item 7  Liquidity stress results
  R. Khan      Waiting for the chair    "Holds shares in a bank in the stress set"
               [ Record the chair's decision… ]                                    ⋯
──────────────────────────────────────────────────────────────────────────────
[ + Record a declaration… ]
```
- **Row ⋯:** History · Withdraw (with reason).
- **"Remind them now"** sends the same reminder as the sweep, once per person and revision.
- **The quorum warning** comes from the committee's quorum rule with every voting member present. The tip suggests: "Consider inviting alternates (from the next release) or referring the item to the Board."
- **"Also reads the memo"** tells the secretary that recusal withholds the committee's papers, not the memo itself; the memo's access is changed on the memo.

**T2. Record a declaration** (side sheet)
```
Record a declaration of interest
Member        [ S. Ali ▾ ]               (anyone on this meeting)
Item          [ 5 Term loan, Delta Textiles ▾ ]
Their interest [ Brother-in-law is a director of Delta Textiles.          ]
How it was made (•) At the meeting  ( ) In person  ( ) By email  ( ) By phone
Note          [ email of 8 Dec ]  (optional)
What they will do (•) Leave the room for this item   ( ) Ask the chair to decide
                                              [ Cancel ]  [ Record ]
```
- Shown as "Recorded by Company Secretary, by email (email of 8 Dec)". The member is told.
- **During the meeting** the panel opens with "At the meeting" chosen, so a declaration made in the room takes three choices and one sentence.
- **After the meeting is held**, the sheet says "Correcting the record of a held meeting" and asks for a reason.

**T3. The chair's decision** (the chair's notification "A member asks you to decide on a declaration of interest" opens this)
```
A declaration for 12 Dec 2026 — your decision is needed
R. Khan, item 7 Liquidity stress results (For decision)
"Holds shares in a bank in the stress set." · declared on 9 Dec
R. Khan asks whether they may take part. Until you decide, the papers are withheld.
Note (optional) [ Shareholding below 0.1%; not material.                    ]
                  [ They should leave the room ]  [ They may take part ]
```
- **The secretary** can record the chair's decision on their behalf: "Record the chair's decision… (given by email, phone or in person) [date] [note]".
- **When the chair is the declarant**, the request goes to the vice-chair, with the line "The chair declared this interest, so the vice-chair decides."

**T4. The builder for a recused secretary** (E03 O2, item 6's row)
```
 ⠿ 6  Market risk limits                    For decision 20 min
      You declared an interest. Another secretary or an administrator handles this item.
```
- The row has no ⋯ menu and no drag handle. Opening it answers with the same sentence.
- **Preview as…** shows item 6 as the secretary's own recused line, with a banner: "Items you declared an interest in are hidden in this preview."

## Flow U — The register of declarations (oversight)

**U1. Register of declarations** (`/declarations`; auditors with oversight access, administrators, and secretaries and chairs for their committees)
```
Register of declarations of interest
Committee [ All ▾ ]  From [ 01/01/2026 ]  To [ 31/12/2026 ]  Member [ Any ▾ ]  State [ Any ▾ ]
                                                                        [ Export to Excel ]
┌────────────┬──────────────┬───────────────────────────┬────────────┬──────────────────────────┬─────────────────────┐
│ Meeting    │ Item         │ Member                    │ What       │ Interest                 │ Decision            │
├────────────┼──────────────┼───────────────────────────┼────────────┼──────────────────────────┼─────────────────────┤
│ BRC 3/2026 │ 5 Term loan, │ S. Ali                    │ Left the   │ Brother-in-law is a      │ —                   │
│ 12 Dec     │ Delta Text…  │ by email, recorded by Sec │ room       │ director of Delta…       │                     │
│ BRC 3/2026 │ 7 Liquidity  │ R. Khan                   │ Took part  │ Holds shares in a bank…  │ Chair: may take part│
│ 12 Dec     │ stress…      │ in the app                │            │                          │ 10 Dec              │
│ ALCO 9/2026│ 4 Restricted │ T. Islam                  │ Left the   │ (restricted item)        │ —                   │
│ 3 Oct      │ item         │ carried over from memo    │ room       │                          │                     │
└────────────┴──────────────┴───────────────────────────┴────────────┴──────────────────────────┴─────────────────────┘
[ Load more ]
```
- **A row opens its history:** declared, intent changes, rulings and withdrawals, each with who and when.
- **Restricted items:** the title shows as "Restricted item" and the interest as "(restricted item)" when the viewer may not see the title. Ask the committee's secretary for these.
- **Export to Excel** exports the rows on screen under the filters (at most 20,000). The export is recorded in the audit log.

## Decisions taken under the delegated gate
- **Leave the room by default**, with "ask the chair" as the only alternative, and papers withheld while waiting (ADR-0062).
- **Other members see who declared and what they will do**, not the nature.
- **One confirmation per meeting**, re-asked only for new items; one reminder the working day before.
- **Carried-over declarations** from the memo and from an earlier placement of the same paper.
- **A recused secretary** sees the item's line only, and cannot change or preview it.
- **The quorum warning** before the meeting, from the committee's own rule.
- **The register** for oversight across committees, with an Excel export.
- **The recused member's minutes** (E06) show the heading, their declaration and the decision, not the discussion. This changes the overview and is reported to the owner (ADR-0062).

## Questions for the owner
None needs a real-world input. One change from the overview is reported: a recused member's copy of the minutes shows the item's decision but not its discussion (the overview said the item would be left out). Say if you want the stricter version.

## Review
| Flow | Date | Result |
|---|---|---|
| S declaring (members) | 2026-11-18 | Delegated (Phase 4 Q1); reviewed by the agent against the research and E03's principles |
| T managing declarations (secretary and chair) | 2026-11-18 | Delegated (Phase 4 Q1) |
| U the register of declarations | 2026-11-18 | Delegated (Phase 4 Q1) |

## Keyboard and screen-reader pass (T9, 2026-11-21)
Done by the agent in Chromium (Playwright 1.64, headless) against the local E2E stack. It used the seeded demo declarations: the BRC's "Quarterly risk review" as Demo Director (recused from item 7 "Term loan, Delta Textiles", waiting for the chair on item 8) and as the company secretary, and the register as the organisation administrator. It also used the journey `declarations.spec`'s own MCC meeting: a member declares and confirms, the secretary records "by email", the chair rules, and the register exports. A screen reader cannot be driven from there, so the spoken output is still for a person (below).

### What the agent checked, and found
- **axe** (WCAG 2.2 A/AA plus best practices, every impact): no rule fails anywhere it was run:
  - the meeting page with the Declarations panel, the recused and waiting lines, My declaration (S4) and the declaration sheet (S2) with its error showing, in English and Arabic;
  - the review sheet (S3);
  - the secretary's Declarations tab (T1), Record a declaration (T2) with its errors showing, and Record the chair's decision (T3);
  - the chair's decision dialog;
  - the register (U), in English and Arabic.

  The journey keeps serious and critical findings out on every run.
- **Landmarks and headings:** each page has one `main` and one H1.
  - The meeting page: H2 "Will you attend?", "Declarations of interest" (with H3 "Your declarations in this meeting") and "Agenda"; H3 per section; H4 per item, whose name carries the state ("7 Term loan, Delta Textiles — You declared an interest", "8 Operational risk incidents, third quarter — Waiting for the chair's decision"). A screen-reader user hears why an item has no papers when they reach it.
  - The Declarations tab: one named region with H3 "Item 7 Term loan, Delta Textiles" per item.
  - The register: H1 "Register of declarations of interest", the filters, and a table with a caption and column headers.
- **Tab order** follows the reading order, with a visible focus ring on every stop of ours:
  - **the meeting page** (35 stops): skip link, navigation, top bar, the reply, calendar, the panel's own "My declaration" buttons, print, revision, "Show changes", then per item its papers, "Ask to discuss" and "Declare an interest" or "My declaration". Standing items, breaks and "Restricted item" lines have no declaration button;
  - **the Declarations tab** (30 stops) and **the register** (40 stops). The one stop without our ring on the register is Chromium's own calendar button inside the date field, which draws its own box.
- **Keyboard only:**
  - **flow S:** declaring (S2) opens with focus in the sheet, and an empty Declare puts focus on the interest field with its error described (`aria-invalid`, `aria-describedby` hint and error). Escape closes S2 and S4 with focus back on the opener. After declaring, focus stays on the item's "My declaration" (after the fix below). Confirming the review puts focus on the panel's heading once "Review and declare" has gone;
  - **flow T:** an empty Record puts focus on Member, the first field with an error; Escape returns to "Record a declaration…". The chair decides from the keyboard and the row says "Taking part";
  - **flow U:** "Export to Excel" downloads the workbook and says so in words.
- **Targets:** every button on the member's meeting page and in its sheets is at least 44 × 44 px. Radio buttons are 20 px, but each sits inside its label, a row at least 44 px high, so the target is the row. On the secretary's page, "Add a guest" and "Remove …" in the Guests panel (E03/E05) are 40 px high, which passes WCAG 2.5.8 (24 px); they are not on the director's path.
- **Reflow at 400 %** (320 CSS px wide): nothing scrolls sideways on the meeting page (English and Arabic), in S2 and S4, on the Declarations tab, in T2 and T3, or on the register. The register's table scrolls inside its own box, which WCAG 1.4.10 allows for data tables.

### Defects found and fixed here
1. **Focus fell to the page after declaring** (WCAG 2.4.3). The sheet gave focus back to "Declare an interest", but the agenda and the reader's declarations are read again afterwards, and the item's button was briefly replaced while they arrived. The old 100 ms check missed it whenever the reload took longer, which it always does against a real server; the unit tests' instant answers hid it. An item's declaration button now carries `data-declaration-item`. For three seconds after a sheet closes, focus that fell to the page, or to the panel's heading in the button's place, moves to the item's new button. Focus the person moved themselves is left alone, and a newer sheet's watch replaces an older one (`declarations/focus.ts`, `focus.test.ts`). The journey checks the focus.

### For a person with a screen reader (still to do)
Run with NVDA + Firefox and VoiceOver + Safari (macOS and iPad), in English and Arabic. Use the seeded "Quarterly risk review": Demo Director for flow S, the company secretary for flow T. Use Demo Board Member, the BRC chair, for the ruling on Demo Director's item 8. Use the organisation administrator for the register.
1. **The recused line:** H4 navigation reads "7 Term loan, Delta Textiles — You declared an interest", then "You will be asked to leave the room for this item. Its papers are not shown to you." The waiting line on item 8 makes sense when spoken.
2. **Duplicate names:** the panel's "My declaration: 7 Term loan, Delta Textiles" and the item's button of the same name open the same sheet. Judge whether hearing it twice in a buttons list is confusing.
3. **Declare (S2):** the explanation is read on opening; the two choices read their consequence ("You will not receive its papers…", "Until the chair decides…") and "(recommended)"; the error is read with the field; "Thank you. The secretary and the chair have been told…" is announced after Declare.
4. **Review (S3):** the items to review are read as a list; each row's button reads the item ("I have an interest: 5 …", "Declared — leaving the room: 7 …"); "Thank you. Your review of the agenda is recorded." is announced, and focus lands on the panel's heading.
5. **My declaration (S4):** the history line ("Recorded by … by email (email of …)"), the interest in quotes and the decision read in order; the withdraw dialog's warning is read before its field.
6. **Declarations tab (T1):** each item region is announced with its name; badges ("Leaving the room", "Waiting for a decision") are read with the person. The quorum warning and "also reads the memo" read as plain sentences, not as alerts on every visit. "Remind them now" announces its result in the status line.
7. **Record (T2) and the decision (T3):** the selects read the person and "7 Term loan, Delta Textiles"; the "How it was made" group is read as a group; "Recorded. … has been told." and "… may take part. They have been told." are announced.
8. **Register (U):** the table's caption and headers are read with each cell; "Details" names the person and the item; "(restricted item)" makes sense on its own; "The workbook has been downloaded." is announced.
9. **Arabic:** the pages read right to left; Latin names and item titles keep their own direction inside Arabic sentences; dates and "item 7" read naturally.
10. **Zoom and contrast by eye:** 200 % and 400 % in a real browser window, in light and dark themes, looking at the state badges and focus rings.
