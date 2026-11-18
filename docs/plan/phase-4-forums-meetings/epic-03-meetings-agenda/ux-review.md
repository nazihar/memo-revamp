# E03 Meetings and the agenda builder — UX review

> `AGENTS.md §5` Step 3 and Phase 4 Q1: the agenda builder and the meeting page go to the owner **before build** (T6–T8), together with E02's flow M (the secretary's queue). Wireframes are structural; the look is the existing Ledger direction and components. "Committee" is the default English word for a forum (terminology term `forum`). Flows continue from E02's M: N meetings, O the agenda builder, P the meeting page, Q the chair, R the agenda template.

## Design principles taken from the research
1. **Members read only what is published.** The secretary works on a draft; members see revision 1, 2, … and what changed (Boardable, BoardPro; research §3.5).
2. **One screen to build an agenda.** Ready submissions sit beside the agenda, standing items come from the template, and there is no "build the book" step (complaints about Diligent and BoardEffect, §3.9).
3. **Every move is a button first.** Each row has a Move menu; dragging is an extra (WCAG 2.2 SC 2.5.7; Diligent's "Move to"; §3.8).
4. **Members never set or see permissions.** Membership gives access. A restriction is "Only these people", set on an item or a paper, and survives a replaced paper (§3.6).
5. **For the director: big, plain, calm.** 44 px targets, purposes in words, times in the meeting's zone and their own, no dragging, no hidden gestures, one primary action per screen.
6. **Lateness and changes are said in words:** "Late paper", "Tabled at the meeting", "Moved (was item 5)", "Paper replaced".

## Flow N — Meetings

**N1. Committee page → Meetings tab** (everyone who sees the committee; drafts only for officers)
```
Board Risk Committee (BRC)        Members │ Submissions (3) │ Meetings │ History │ Policy
──────────────────────────────────────────────────────────────────────────────────────
[ Upcoming ] [ Past ] [ Cancelled ]                                 [ Schedule a meeting ]
┌───────────────────────────┬──────────────────────────────┬──────────┬────────────────────┐
│ Date                      │ Meeting                      │ Mode     │ Agenda             │
├───────────────────────────┼──────────────────────────────┼──────────┼────────────────────┤
│ Thu 12 Dec 2026           │ BRC 3/2026                   │ Hybrid   │ Published (rev 2)  │
│ 10:00–13:00 Dhaka time    │                              │          │ Papers due 5 Dec ✓ │
│ Tue 14 Jan 2027           │ Board Risk Committee (draft) │ In person│ Not started        │
│ 10:00–12:00 Dhaka time    │                              │          │ Papers due 7 Jan   │
└───────────────────────────┴──────────────────────────────┴──────────┴────────────────────┘
```
"Schedule a meeting" is shown to secretaries and administrators only.

**N2. Schedule a meeting** (side sheet; full screen on a phone)
```
New meeting of Board Risk Committee
Kind        (•) Ordinary   ( ) Special
Title       [                                   ]  (optional, e.g. "Strategy day")
Date        [ 12/12/2026 ]  From [ 10:00 ]  To [ 13:00 ]   ( ) ends on another day [ date ]
Time zone   [ Dhaka (GMT+6) ▾ ]      Search by city. Default: your organisation's zone
How         ( ) In person   ( ) Online   (•) Both (hybrid)
Where       [ Board Room, Level 12, Head Office          ]
Join link   [ https://                                    ]  Members see it on the meeting page
Joining instructions [ Meeting ID 123 456, passcode on the page ]  (optional)
Classification [ Confidential ▾ ]  (not below the committee's)
Guests      [ Search people… ]  In attendance for the whole meeting, e.g. the CFO
Papers due  5 Dec 2026 (5 working days before; set by the committee type)
                                  [ Cancel ]  [ Save as draft ]  [ Schedule ]
```
- **Save as draft:** only the secretaries and chair see it.
- **Schedule:** members see the date, place and link; E05 later sends the calendar invite.
- **Reschedule** (on the meeting's ⋯ menu) asks for the new date and time and a reason ("Chair unavailable"). **Cancel** asks for a reason and says what happens: "Members will be told. Items from the queue go back to Ready for an agenda."

**N3. My meetings** (home page card and `/me/meetings`)
```
My meetings
┌────────────────────────────────────────────────────────────────┐
│ Thu 12 Dec · 10:00–13:00 Dhaka time (09:00–12:00 your time)    │
│ Board Risk Committee — BRC 3/2026 · Hybrid                     │
│ Agenda published · 2 changes since you last looked             │
│ [ Open meeting ]                                               │
└────────────────────────────────────────────────────────────────┘
```
"Your time" appears only when the viewer's zone differs from the meeting's.

## Flow O — The agenda builder (secretaries)

**O1. First open:** "How would you like to start?"
```
Agenda for BRC — 12 Dec 2026
( ) The committee's standard agenda   Welcome and apologies · Declarations of interest ·
                                      Minutes of the last meeting · Matters arising ·
                                      For decision · Consent items · For noting · Any other business
( ) Copy an earlier meeting           [ BRC 2/2026 — 10 Oct 2026 ▾ ]
                                      Copies sections, items, times and presenters. Never papers or queue items.
                                                                        [ Start ]
```

**O2. The builder** (`/meetings/$id/agenda`; the tray on the right, below the agenda on a phone)
```
BRC — Thu 12 Dec 2026, 10:00–13:00 Dhaka time            Draft · last published: revision 1
Papers due 5 Dec ✓ published on time    Chair's agreement ✓ (by email, 4 Dec)
[ Preview as… ▾ ]                             3 unpublished changes   [ Publish revision 2 ]
──────────────────────────────────────────────────────────────┬────────────────────────────
                                                    Time      │ Ready for an agenda (1)
Opening                                             10:00     │ ┌──────────────────────────┐
 ⠿ 1  Welcome and apologies            For noting   5 min  ⋯  │ │ CR/HO-CR/2026-27/0051    │
 ⠿ 2  Declarations of interest         For noting   5 min  ⋯  │ │ Working capital, Orion   │
 ⠿ 3  Minutes of the last meeting      For decision 5 min  ⋯  │ │ For decision · R. Khan   │
 ⠿ 4  Matters arising and actions      For noting  10 min  ⋯  │ │ Accepted 18 Nov          │
For decision                     ⓘ new items land here 10:25  │ │ [ Add to agenda ]        │
 ⠿ 5  Term loan, Delta Textiles        For decision 30 min ⋯  │ └──────────────────────────┘
      Presenter: Head of Credit · 2 papers (14 pages) · Late paper (chair agreed)
 ⠿ 6  Market risk limits               For decision 20 min ⋯
      🔒 Only 4 people can see this · title hidden from others
    ⠿ 6.1  VaR back-testing            For noting              ⋯
 ⠿ –  Liquidity stress results         For decision 15 min ⋯
      RSK/2026/0009 · Late paper · waiting for the chair (not published or timed until agreed)
 ⠿ ☕  Break                                          10 min ⋯
Consent items                                       11:25
 ⠿ 7  Quarterly compliance report      For noting          ⋯
 ⠿ 8  Committee terms of reference     For decision        ⋯
For noting · Any other business                     11:25
 ⠿ 9  Any other business (notified in advance)        5 min ⋯
[ + Add item ] [ + Add section ]
Total 1 h 30 min · ends 11:30 · planned end 13:00 ✓
```
- **Row menu ⋯:**
  - Edit… · Papers… · Who can see this…
  - Move up · Move down · Move to section… · Make a sub-item of the item above · Make an item again · Move to position…
  - Remove from agenda
- **⠿ handle:** drag with the mouse, or focus it and press Space, then the arrows, then Space. The screen reader says "Item 5, Term loan, Delta Textiles, moved to position 3 in For decision".
- **Add to agenda** puts the paper in the section marked "new items land here", at the end. Dragging it from the tray also works.
- **When the paper deadline has passed**, Add to agenda explains the committee's rule before acting:
  - "Late: this goes to the chair for agreement first";
  - or, with BLOCK, "Late papers are not accepted for this committee. Choose a later meeting."
- **Overrun:** when the total runs past the planned end, the total line turns into a warning: "Ends 13:20, 20 minutes after the planned end."

**O3. Item side sheet** (Edit…, Papers…, Who can see this… open tabs of one sheet)
```
Item 5 · Term loan, Delta Textiles                         From the queue: CR/HO-CR/2026-27/0042
Details │ Papers │ Who can see this
─────────────────────────────────────────────────────────────────────────
Title on the agenda  [ Term loan, Delta Textiles                    ]
Purpose              (•) For decision  ( ) For discussion  ( ) For noting
Resolution sought    [ To approve a term loan of BDT 60 million subject to… ]
Time                 [ 30 ] minutes
Presenters           [ Head of Credit ✕ ] [ + person or name ]
In attendance        [ R. Khan ✕ ]   (for this item only; they see this item's papers)
─────────────────────────────────────────────────────────────────────────
Papers (in the order members see them)                         [ + Upload papers ]
 ⠿ Paper: CR/HO-CR/2026-27/0042 (memo document)   PDF · 9 pages   ✓ in the pack   ⋯
 ⠿ Security valuation report (attachment)         PDF · 5 pages   ✓ in the pack   ⋯
   Customer KYC file (attachment)                  Restricted      ☐ in the pack   ⋯
─────────────────────────────────────────────────────────────────────────
Who can see this item
  (•) Everyone attending the meeting        ( ) Only these people: [ Search the members… ]
  [ ] Hide the title from people who cannot see the item  (they see "Item 5 — restricted")
  Classification [ Confidential ▾ ]
  ⓘ A member who declares a conflict will not see the papers (from the next release: conflicts of interest).
```
- Paper ⋯: Rename · Replace with a new version… · Only these people… · Remove from the pack.
- **Replacing a paper** says: "Members will see 'Paper replaced' when you publish. The earlier version stays in revision 1."

**O4. Preview as a member:** "Preview as… [ Demo Director ▾ ]" opens the meeting page (flow P) in a framed preview, with a banner: "This is what Demo Director would see if you published now."

**O5. Publish**
```
Publish revision 2 of the agenda
What changed since revision 1 (members see only what they are allowed to see):
  • Added      Item 6.1 VaR back-testing
  • Moved      Term loan, Delta Textiles — now item 5 (was 6)
  • Replaced   Paper: CR/HO-CR/2026-27/0042
  • Late       Item 5 (chair agreed 6 Dec)
Note to members (required)  [ Updated credit paper after the valuation   ]
[x] Tell the members (turn off for a minor correction)
1 item is waiting for the chair and will not be published yet: Liquidity stress results (RSK/2026/0009).
                                                     [ Cancel ]  [ Publish ]
```
- **Revision 1:** the dialog shows the meeting number it will take ("This meeting becomes BRC 3/2026") and needs the chair's agreement when the committee type asks for it.
- **The secretary records the chair's agreement** with: "Record the chair's agreement… (given by email or in person) [date] [note]".

**O6. Flow M addition (E02 queue):** the Accept dialog gains "Place on the agenda of [ Next meeting: 12 Dec ▾ ] (optional)". It runs Add to agenda with the same late-paper text.

## Flow P — The meeting page (members, guests, attendees)

**P1. The meeting page** (`/meetings/$id`; one column, large type, 44 px targets)
```
Board Risk Committee — BRC 3/2026
Thursday 12 December 2026 · 10:00–13:00 Dhaka time
                            (09:00–12:00 your time, London)
Board Room, Level 12, Head Office · or join online  [ Join the meeting ]  Meeting ID 123 456
──────────────────────────────────────────────────────────────────────────────
ⓘ The agenda changed on 10 Dec (revision 2): "Updated credit paper after the valuation".
   2 changes since you last looked  [ Show changes ]
──────────────────────────────────────────────────────────────────────────────
Opening
 1  Welcome and apologies                                 10:00   For noting
 2  Declarations of interest                              10:05   For noting
 3  Minutes of the last meeting                           10:10   For decision
     [ 📄 Minutes of BRC 2/2026 · 6 pages ]  ✓ opened
 4  Matters arising and actions                           10:15   For noting
For decision
 5  Term loan, Delta Textiles                             10:25   For decision
     Presenter: Head of Credit · Late paper (chair agreed)   Changed: paper replaced
     To approve a term loan of BDT 60 million subject to…
     [ 📄 Paper: CR/HO-CR/2026-27/0042 · 9 pages ]
     [ 📄 Security valuation report · 5 pages ]           ✓ opened
 6  Restricted item                                       10:55
Consent items — taken together without discussion
 7  Quarterly compliance report                           11:25   For noting
     [ 📄 Compliance report Q3 · 12 pages ]   [ Ask to discuss this item ]
 8  Committee terms of reference                          11:25   For decision
     Lifted for discussion at S. Ali's request — taken after the consent items
     [ 📄 Terms of reference (marked up) · 8 pages ]     [ Withdraw my request ]  (S. Ali only)
Any other business
 9  Any other business (notified in advance)              11:25
```
- **Papers** open in the PDF viewer inside the app, with "Back to the agenda" always visible. There is no download button for members; the PDF can be printed from the viewer.
- **"Ask to discuss this item"** asks once: "Ask for item 7 to be discussed separately? The secretary and the chair will be told. You can withdraw this until the meeting starts." [Cancel] [Ask to discuss]
- **Restricted items, as others see them:**
  - with the title shown: "6  Market risk limits — papers restricted";
  - with the title hidden: "6  Restricted item";
  - a restricted paper: not listed at all.
- **Guests** do not see members-only sections at all. Item attendees see the agenda's titles and only their own item's papers.
- **Places kept for later epics** (absent until then):
  - E05: "Will you attend?" (RSVP) under the time;
  - E04: "Declare an interest" per item;
  - E06: the decision per item after the meeting.
- **Print** gives a plain agenda with numbers, times and purposes, and no papers.

**P2. Show changes**
```
Changes since you last looked (revision 1 → 2, 10 Dec, by Company Secretary)
"Updated credit paper after the valuation"
  • Item 5 Term loan, Delta Textiles: paper replaced
  • Item 6.1 VaR back-testing: added
                                                                   [ Close ]
```

## Flow Q — The chair

**Q1. Agree the agenda** (the chair's meeting page while the agenda is a draft; a notification "The agenda for 12 Dec is ready for your agreement")
```
Draft agenda for 12 Dec 2026 — waiting for your agreement
[ Read the draft agenda ]                          (the builder, read-only)
Comment to the secretary (optional) [                                  ]
                              [ Send comments ]  [ Agree the agenda ]
```

**Q2. A late paper**
```
A late paper for 12 Dec 2026 — your agreement is needed
Item: Liquidity stress results (RSK/2026/0009) · For decision · 6 pages
Placed by Company Secretary on 9 Dec; papers were due on 5 Dec.
                               [ Not this meeting ]  [ Agree to include it ]
```
- **Not this meeting:** the paper goes back to Ready for an agenda.
- **Agree:** it is published in the next revision with "Late paper".
- **The secretary can record either answer** on the chair's behalf, with the date and how it was given.

## Flow R — The agenda template on the committee type (flow J gains an "Agenda" tab)

**R1. Committee type → Agenda tab** (draft versions only; reviewed with the rest of the type)
```
Board committee (BC)   Version v3 · Draft
 Policy │ Quorum │ Outcomes │ Terms │ Submissions │ Agenda │ Changes
──────────────────────────────────────────────────────────────────────────────
[x] The chair agrees the agenda before it is first published
Standard agenda (new meetings start from this; meetings already started keep theirs)
  Opening
    Welcome and apologies           Apologies        For noting     5 min   ⋯
    Declarations of interest        Declarations     For noting     5 min   ⋯
    Minutes of the last meeting     Previous minutes For decision   5 min   ⋯
    Matters arising and actions     Matters arising  For noting    10 min   ⋯
  For decision                      ⓘ new items land here
  Consent items                     (consent section)
  For noting
  Any other business
    Any other business (notified in advance)  AOB   For noting     5 min   ⋯
[ + Add section ] [ + Add standing item ]
```
- **Section ⋯:** Rename · Consent section on/off · Members only on/off · New items land here · Move (the same menu as the builder).
- **The Changes tab** lists template changes in words, for example "Agenda: added standing item 'Any other business'", "Chair's agreement: on → off".

## Decisions taken under the delegated gate
- **Paper access:** members read the papers placed before the committee, not the whole memo file. This changes the overview's assumption that members would become memo readers; see ADR-0061, reported to the owner.
- **Agenda structure:** three levels (sections, items, one level of sub-items), items numbered continuously, sections unnumbered. Numbers change only at publication.
- **Chair agreement:** the chair agrees the first publication by default (a setting on the committee type), and the secretary may record it on the chair's behalf.
- **Late papers:** they follow the committee's E02 late mode. Additions during the meeting are "tabled".
- **Consent items:** any member can lift a consent item without a reason or approval, until the meeting starts.
- **What members can do with papers:** they read PDFs in the app and do not download originals. Watermarking and offline reading are Phase 5.
- **Meeting numbers** are taken when the agenda is first published ("BRC 3/2026", per calendar year).
- **Meeting search:** no new search in E03. A "find on this page" is the browser's; full-text search of papers comes with the book (Phase 5) or the register (E08).

## Questions for the owner
None needs a real-world input. The flows above carry the recommendations; say if any should change:
1. Should committee meeting numbers follow the **calendar year** ("BRC 3/2026") or the **financial year**? Recommendation: calendar year now. E06 makes resolution numbering per year or financial year configurable, and meeting numbers can follow it then.
2. Should **members download** papers? Recommendation: no, read in the app only, until Phase 5 adds watermarked copies.

## Owner review
| Flow | Date | Result |
|---|---|---|
| M the secretary's queue (E02) | | |
| N meetings | | |
| O the agenda builder | | |
| P the meeting page | | |
| Q the chair | | |
| R the agenda template | | |
