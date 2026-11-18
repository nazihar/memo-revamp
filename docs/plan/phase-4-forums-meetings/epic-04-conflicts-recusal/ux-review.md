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
