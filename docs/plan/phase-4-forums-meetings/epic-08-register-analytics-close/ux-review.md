# E08 Decision register, analytics and close-out — UX review

> `AGENTS.md §5` Step 3. **The owner delegated these screens.** Phase 4 Q1 sends only the E01, E02/E03 and E06 flows to the owner before build. E08's screens are reviewed under the delegated gate, so the review table below records "delegated", and the web tasks (T6, T7) do not wait.
>
> The wireframes are structural; the look is the existing Ledger direction and its components. "Committee" is the default English word for a forum. Flows continue from E07's AR:
> - **AS** the decision register;
> - **AT** exporting for auditors;
> - **AU** committee analytics overview;
> - **AV** attendance per member;
> - **AW** a committee's detail.
>
> The phase's moderated usability walkthrough and the owner's walk through the committee cycle (T9) are recorded at the end.

## Design principles taken from the research
1. **The register answers an auditor's first question in one step:** "every decision of this committee in this period", with the number check beside it (research §3.1).
2. **A restricted row says so in words.** "Resolution BRC/2026/014 — Restricted item: you declared an interest" — never a blank or a missing number (E06's register rule).
3. **Exceptions are one click:** with dissent, with conditions, to be ratified, corrected, validity passed.
4. **A sentence before any chart.** "Board Risk Committee: 92 % attendance over the last 12 months, 3 actions overdue, decisions took a median of 18 days" (GOV.UK: convey the main message).
5. **The table is the figure for older readers;** bars sit beside it, with value labels. No line, pie, donut or radial charts (research §5).
6. **Colour is never alone;** every status has its word.
7. **Every number leads to its list** (design §2.6).
8. **Nothing about individuals that disclosure does not ask for:** attendance per member, not reading time; members see their own row.

## Flow AS — The decision register (secretary, auditor, director)

**AS1. Committees → Decisions**
```
Decisions                                                              [ Export… ]
Search decisions and resolutions of the committees you can see.
┌────────────────────────────────────────────────────────────────┐  [ Search ]
│ Words, a resolution number or a memo number                     │
└────────────────────────────────────────────────────────────────┘
Committee [ All my committees ▾ ]  Type [ Any ▾ ]  From [ 01/01/2026 ]  To [ 31/12/2026 ]
Outcome  [ Any ▾ ]   [ ] Resolutions only   More filters ▾   (With dissent · With conditions ·
                                                             To be ratified · Corrected · Validity passed ·
                                                             Memo type · State)
Showing 214 decisions · Newest first ▾                                   [ Reset ]
By committee: Board Risk 61 · Credit 120 · ALCO 33      By outcome: Approved 140 · Noted 52 · Deferred 9 …
────────────────────────────────────────────────────────────────────────────────────────
12 Dec 2026  BRC 3/2026, item 5     Resolution BRC/2026/014        Restricted item
                                    In force                         You declared an interest in this item.
────────────────────────────────────────────────────────────────────────────────────────
12 Dec 2026  BRC 3/2026, item 4     Resolution BRC/2026/013        APPROVED WITH CONDITIONS
             Liquidity contingency plan, annual review              2 conditions · with dissent
             Minutes approved 9 Jan 2027 · Memo POL-2026-00031     [ Open ]  [ Meeting ]  [ Minutes ]
────────────────────────────────────────────────────────────────────────────────────────
                                                           [ Show more decisions ]
```
- **States in words:** "Recorded at the meeting", "Numbered", "Sent to the memo", "Minutes approved"; VOID as "Void — {date}".
- **Number lookup:** typing `BRC/2026/014`, `brc-2026-14` or `14` with one committee chosen finds it; a memo number finds the decisions on that memo.
- **Restricted lines** never show a title, an outcome or a memo, and an outcome filter leaves them out ("Some restricted items are not shown when you filter by outcome" under the count, for viewers who have any).
- **"Number order"** appears in the sort menu when one committee is chosen, and the page offers "Check the numbers" (E06's per-committee register) for officers and auditors.
- **Empty states:** "No decisions match. Try a wider date range or fewer filters." / "You are not a member or officer of any committee." (no scope: the menu item is hidden; a direct link explains it).
- Filters are chips that can be removed one by one; Reset clears all (BoardPro). The page keeps its words in the address fragment only (Phase 3 E05).

**AS2. Opening a row** goes to the resolution page (E06 flow AC) or, for a decision without a number, the meeting page at the item. The register keeps its place when the person comes back.

## Flow AT — Exporting for auditors (secretary, auditor)
```
Export decisions
──────────────────────────────────────────────────────────────────────
214 decisions match your filters:
  Board Risk Committee, Credit Committee, ALCO · 1 January – 31 December 2026
Format   (•) Spreadsheet (.xlsx) — for sorting and checking, up to 20,000 rows
         ( ) PDF — for filing, up to 2,000 rows
The export includes a page "About this export" with these filters, your name and the time,
and a number check for each committee and year.
Restricted items show their number only.
                                                   [ Cancel ]  [ Download ]
```
- More rows than the format allows: "This is 2,412 decisions; a PDF holds up to 2,000. Narrow the dates or choose a spreadsheet." The Download button stays disabled.
- After 5 exports in an hour: "You have exported 5 times in the last hour. Please try again at 14:20."
- **The spreadsheet:** sheet "Decisions"; sheet "About this export"; sheet "Number check":
```
Committee             Year   First  Last  In force  Void  Missing
Board Risk Committee  2026   001    014   13        1     none
  Void: BRC/2026/009, 3 Sep 2026 — "Recorded against the wrong item; replaced by BRC/2026/010"
```
- **The PDF:** landscape, the about block first, a table per committee, the number check last, "Page n of m" and "Produced by {name} on {date}" on every page.

## Flow AU — Committee analytics overview (secretary, chair, auditor, members)
```
Committee analytics                                Period [ Last 12 months ▾ ]
These figures count only the meetings, decisions and actions you can see.

Board Risk Committee
  92 % attendance at 12 meetings · 3 actions overdue · decisions took a median of 18 days
  ┌────────────────────────────┬──────────┬───────────────────────────────────┐
  │ Measure                    │ Value    │                                   │
  ├────────────────────────────┼──────────┼───────────────────────────────────┤
  │ Attendance                 │ 92 %     │ ████████████████████████▌  92 %   │
  │ Actions overdue now        │ 3        │ ███  3   (1 over 90 days)         │
  │ Actions closed on time     │ 12 of 18 │ by the original date; 16 by the agreed date
  │ Time to decision           │ 18 days  │ median; 90 % within 41 days (24 decisions)
  │ Items deferred             │ 3 of 27  │ ███  11 %                         │
  │ Papers on time             │ 10 of 12 │ meetings published by the deadline│
  └────────────────────────────┴──────────┴───────────────────────────────────┘
  [ Attendance ]  [ Actions overdue ]  [ Decisions ]  [ Committee detail ]

Management Credit Committee
  …
```
- One block per committee in scope, alphabetical; a select narrows to one.
- Every number is a link to its list (design §2.6). The bars are decorative beside the table (`aria-hidden`), because the table carries the data.
- A member sees the same block without per-member rows (AV) and without late-paper counts.
- No committee in scope: "Analytics are for committee members, officers and oversight."

## Flow AV — Attendance per member (officers, oversight; members see their own row)
```
Board Risk Committee — attendance                     Period [ 1 Jan – 31 Dec 2026 ]
12 meetings held. Overall attendance 92 %.                    [ Export for the annual report ]
┌──────────────────────┬─────────────┬──────────┬──────────┬──────────┬───────────┬──────────┬────────────┐
│ Member               │ Role        │ Expected │ Attended │ Remote   │ Apologies │ Absent   │ Rate       │
├──────────────────────┼─────────────┼──────────┼──────────┼──────────┼───────────┼──────────┼────────────┤
│ Dr F. Karim          │ Chair       │ 12       │ 12       │ 1        │ 0         │ 0        │ 100 % ████ │
│ Ms N. Haque          │ Member      │ 12       │ 10       │ 3        │ 1 (alt.)  │ 1        │  83 % ███▍ │
│ Mr R. Ahmed          │ Member      │ 6 (from 1 Jul) │ 6  │ 0        │ 0         │ 0        │ 100 % ████ │
└──────────────────────┴─────────────┴──────────┴──────────┴──────────┴───────────┴──────────┴────────────┘
"(alt.)": an alternate attended in their place; this is not counted as the member's attendance.
```
- Sorted by name; the column headers sort (Rate, then name). No ranks, badges or colours of judgement.
- A name opens the list of the period's meetings with that person's attendance and links to each meeting.
- **A member's view:** "Your attendance: 10 of 12 meetings (83 %). The committee's overall attendance: 92 %."
- The export button only for officers and oversight; the file is the same table with a header block.

## Flow AW — A committee's detail (officers, oversight, members)
```
Board Risk Committee                                   Period [ Last 2 years ▾ ]
Time from submission to decision
Decisions took a median of 18 days; 9 in 10 within 41 days. Based on 46 decisions you can see in full.
┌──────────┬──────────┬────────┬───────────────┐
│ Quarter  │ Decisions│ Median │ 90 % within   │   ▏Q1 2026  ████████ 21
├──────────┼──────────┼────────┼───────────────┤   ▏Q2 2026  ██████ 16
│ Q1 2026  │ 11       │ 21 days│ 44 days       │   ▏Q3 2026  ███████ 18
│ …        │          │        │               │
└──────────┴──────────┴────────┴───────────────┘
By memo type: Credit proposal 22 days (19) · Policy approval 15 days (8) · …   [ Open these decisions ]

Items deferred: 5 of 46 (11 %); 1 item deferred twice.                         [ Open deferred items ]
Actions overdue by owner
┌───────────────────────────────┬─────────┬────────────┬────────────┬──────────┐
│ Owner                         │ Overdue │ 1–30 days  │ 31–90 days │ Over 90  │
│ Head of Treasury (A. Karim)   │ 2       │ 1          │ 1          │ 0        │
└───────────────────────────────┴─────────┴────────────┴────────────┴──────────┘ [ Open the tracker ]
Papers on time: 10 of 12 meetings; 4 late papers in the year (officers).       [ Open meetings ]
```
- Quarters run in bars (horizontal, labelled), never as a line.
- Below a basis of 5 decisions the median and 90 % lines say "Too few decisions to show a typical time" and the table lists the decisions instead.
- "Working days" is a toggle on the timing table (calendar days by default).

## Decisions taken under the delegated gate
- **A restricted line, not a missing row,** for recused and restricted items (ADR-0069), matching E06's register.
- **Members search their own committees' decisions;** only officers and oversight export.
- **Two export formats:** spreadsheet for checking, PDF for filing, each with "About this export" and the number check.
- **Five measures**, with "actions closed on time" and "papers on time" added to the overview's list; no paper-reading statistics, meeting counts or scores (ADR-0070).
- **Tables visible by default** with bars beside them; no line, pie or donut charts.
- **Members see their own attendance and the committee's total**, not colleagues' rows.

### Taken while building AS and AT (T6, 2026-11-22)
1. **The address is `/decisions`**, its menu entry "Decisions" right after Committees. Not `/committees/decisions`: that would load the four committee catalogue parts on every register page and sit beside `/committees/$forumId`.
2. **The words and every filter apply together with Search** (one form), rather than each select searching on change. A date typed digit by digit would otherwise search four times and spend the 30-a-minute limit; a select that searches by itself also moves the page under a screen-reader user. The order (a select of finished values) and each chip apply at once.
3. **Single choices** for committee, type, outcome, memo type and state, as drawn (`Committee [ All my committees ▾ ]`); the exception flags are checkboxes and must all hold (T2's rule). The API takes lists, so facets or links can narrow further later.
4. **Facets are links** ("Board Risk Committee (61)") that narrow the register to that committee, outcome or year; the year sets From and To to the calendar year. A facet already filtered on is not repeated. Under them: "Outcome counts include only decisions you can see in full."
5. **The restricted-items note** ("Restricted items are not matched by words, an outcome, a memo type or an exception. A resolution number still finds them.") shows whenever a content filter or words are set, for everyone: the page cannot know whether the reader would have had a line without asking the server a second time.
6. **A row in full** reads: title (a link), "Resolution BRC/2026/013 In force" or "No resolution number", the outcome as the committee labels it, the exceptions in words ("2 conditions · with dissent · to be ratified · corrected · valid until 10 Mar 2027" / "validity passed on …"), then "date · meeting, item · committee · state" with the state in words ("Minutes approved 9 Jan 2027"), then "Memo POL-2026-00031". The decision's text is not shown in the list (the wireframe does not; it is on the decision's own page). **A restricted line** has a lock icon, "Restricted item", the number and its state, the reason in a sentence ("You declared an interest in this item." / "This item is restricted to named members."), the meeting, item and state, and no link.
7. **Where a row opens (AS2):** the title opens the meeting page, because E06 T10's resolution page and T11's minutes page are not merged; so the wireframe's separate [Open] [Meeting] [Minutes] buttons are one link for now, plus the memo. One function (`RegisterRow`) changes when they arrive. "The register keeps its place": the loaded pages stay cached for 30 minutes and focus returns to the row opened when the reader comes back.
8. **Focus:** after Search, a chip, Reset or the order, focus moves to the count ("Showing 214 decisions"), which is also a polite live region; after "Show more decisions" focus moves to the first new row (a restricted line is focusable for this). Every control and link is at least 44 px high.
9. **Reset** clears the words, the filters and the order (the wireframe's [ Reset ]); a chip removes its own filter.
10. **"Check the numbers"** is a button under the filters when one committee is chosen, for officers and oversight: it calls E06's check for the committee's latest period and says it in a sentence ("2026: resolutions 1 to 16 are all accounted for (15 in force, 1 void).", or which are missing). A member of that committee who is an officer elsewhere is told who can check.
11. **The export dialog (AT)** shows the search's count and the filters in words (committee names, from the facets when several; dates; other chips; the words), the two formats as radio buttons with their limits as descriptions, and "Restricted items show their number only. Each export is recorded." More rows than a PDF holds disables Download with the sentence drawn; for a spreadsheet past 10,000 (the count is capped there) the server decides and its `TOO_MANY_ROWS` is said the same way. The five-an-hour refusal names the time from `Retry-After` ("Please try again at 14:20."). A secretary exporting a committee where they are only a member is told to choose one of theirs.
12. **No "Void — {date}" on a row:** the register row has the resolution's state but not the void's date (T2's row), so a void number reads "Void"; the date is in the export's "Void numbers" and on E06's page.

## Questions for the owner
None about the screens. Reported (ADR-0069, ADR-0070):
- the register shows recused and restricted items as a numbered "Restricted item" line rather than leaving them out (E04's placeholder said "excluded"), so auditors can check every number;
- the viewer's recusals are checked at the moment of the search rather than copied into the register;
- two measures added (actions closed on time, papers on time), paper-reading statistics left out on purpose;
- tables shown first, for older readers, unlike Phase 3's approvals dashboard.

**No real-world input is needed for E08's screens.** The phase close needs the owner for the items in `tasks.md` "Carried".

## Review
| Flow | Date | Result |
|---|---|---|
| AS the decision register | 2026-11-21 | Delegated (Phase 4 Q1); reviewed by the agent against the research and E03's principles |
| AT exporting for auditors | 2026-11-21 | Delegated (Phase 4 Q1) |
| AU committee analytics overview | 2026-11-21 | Delegated (Phase 4 Q1) |
| AV attendance per member | 2026-11-21 | Delegated (Phase 4 Q1) |
| AW a committee's detail | 2026-11-21 | Delegated (Phase 4 Q1) |
| Manual keyboard and screen-reader pass over AS–AW | 2026-11-22 (AU–AW) | AU–AW: the agent's keyboard and structure pass done (T7, below), two defects fixed; the screen-reader pass by a person is carried (checklist below). AS–AT (T6): structure, focus order, live count and axe in English and Arabic checked in the component tests and the journey (`register.test.tsx`, `meeting-analytics.spec`); a browser keyboard pass against seeded decisions is carried to T8 with the demo history, and the screen-reader pass by a person with AU–AW's |
| The owner's walk through the representative committee cycle on dev (exit criterion 3, A1) | — | T9 |
| Moderated usability walkthrough of the committee cycle (overview §5) | — | T9 |

## Keyboard and screen-reader pass over AU–AW (T7, 2026-11-22)
Done by the agent in Chromium (Playwright, headless) against the production build (`vite build --mode e2e`, `vite preview`, the real CSP) with the analytics API answered by fixed data: a Board Risk Committee with every measure filled, three seat holders (one with an alternate standing in, one with a long name), four quarters, an owner who is a position, three held meetings (on time, late, no deadline), in English and Arabic, and at 320 CSS px wide. The demo stack has too few held meetings to fill every figure until T8 seeds a year of history; the journey `meeting-analytics.spec` walks the same pages against it on every PR. A screen reader cannot be driven from there, so the spoken output is for a person (below).

### What the agent checked, and found
- **axe** (WCAG 2.2 A/AA): nothing on the overview, a committee's detail, attendance per member and a member's meetings, in English and Arabic. The component tests (`analytics.test.tsx`) keep this, and the journey checks the overview, the detail and attendance in both languages.
- **Structure, AU:** H1 "Committee analytics", the period, "Showing …", then one `figure` per committee:
  - named by its H2 (the committee) and described by its sentence ("92% attendance at 12 meetings · 3 actions overdue · decisions took a median of 18 days");
  - then "Based on 27 decisions you can see in full." and the table: caption (the committee), column headers Measure, Value and Detail, and the measure as row header;
  - the bars sit in the Detail cells and are `aria-hidden`; the words beside them start with the share the bar shows, so no number lives only in a bar;
  - every value is a link to its list, named by its number and read with its row header ("Attendance, 92%, link").
- **Structure, AW:** H1 the committee, then four figures with H2s ("Time from submission to decision", "Items deferred", "Actions overdue by owner", "Papers on time"). Each reads sentence → basis → table. The bars beside a table are one `aria-hidden` block. "Count working days" is a toggle button (`aria-pressed`) described by its hint. Papers on time per meeting reads "On time", "Late" or "No deadline" in a pill with an icon: never colour alone.
- **Structure, AV:**
  - H1 "Board Risk Committee — attendance", then "12 meetings held. Overall attendance 92%.";
  - one table with a caption, column headers and the member as row header;
  - Member and Rate are buttons inside their headers, and the header carries `aria-sort` (ascending by name, descending by rate);
  - "(1 by alternate)" is in words in the Apologies cell, explained under the table;
  - a member reads "Your attendance: 10 of 12 meetings (83%). The committee's overall attendance: 92%." before their row, with no export button.
- **Tab order** follows the reading order, with a visible focus ring on every stop.
  - AU: period → each value link of the table, row by row → "Attendance per member" → "Committee detail" → the next committee.
  - AW: "All committee analytics" → period → "Attendance per member" → "Count working days" → each meeting → "Meetings of this committee".
  - AV: "Export for the annual report" → period → Member, Rate → each name → "Back to the committee detail".
- **Targets:** buttons 44 px high (export, period, working days), sort buttons 40 px; links 40 px high after the fix below.
- **Reflow at 400 %** (320 CSS px): nothing scrolls sideways on the four pages in either language. The overview's table and the attendance table scroll inside their own boxes (`TableScroll`), which WCAG 1.4.10 allows for data tables.
- **Right to left:** bars grow from the inline start (logical width and radius), so they grow from the right in Arabic; arrows turn round; numbers, dates and percentages follow the locale.

### Defects found and fixed here
1. **Figure links were 24 px high, and the overview's "3" was 8 px wide.** WCAG 2.5.8 passes on spacing, but AGENTS §3.7 asks for large targets for older readers. The figure links are now at least 40 × 40 px (`LINK` in `analytics/words.ts`), and the value links keep their numbers aligned to the end of the cell.
2. **A figure link from the overview did not reach its section** (`/analytics/committees/{id}#actions`). The sections exist only once the detail has loaded, so the browser had nothing to scroll to. The detail now scrolls to the section and moves focus to its figure (`tabIndex=-1`) once the data has come, so a screen reader reads on from there.

### Observed, not changed
- **Figure links into the action tracker and the decision register** (design §2.6) wait for those screens (E07 T10, E08 T6). Until then the numbers open their lists in the committee's detail (design §2.6 "As built").
- **Below 5 decisions** AW says "Too few decisions to show a typical time" in the cells. Listing those decisions instead (flow AW) needs the register's rows (T6).
- **Visual baselines** for the analytics pages are not in the nightly `visual` project yet: they are rendered in the Linux Playwright image (`e2e/update-visual-baselines.sh`). Carried to T8, with the demo history.

### For a person with a screen reader (still to do)
Run with NVDA + Firefox and VoiceOver + Safari (macOS and iPad), in English and Arabic, as the company secretary (every committee in full), `auditor@` (`forum.read`) and Demo Head of Finance (a member of the Management Credit Committee), once T8 has seeded a year of BRC and MCC history.
1. **The overview:** each committee is announced as a figure with its name, and its sentence is read as the figure's description before the table; "Based on n decisions you can see in full." follows.
2. **The table:** with table navigation (Ctrl+Alt+arrows, VO+arrows) each cell is read with its column header and its measure ("Items deferred, Value, 3 of 27, link"); the bars are never announced; the Detail cell's words make sense alone ("11%; 1 item deferred twice").
3. **Links:** the links list (NVDA+F7, the VoiceOver rotor) names each link by its number. Check that, read in its row, each says where it goes, and that following one lands in the detail at its section with focus there.
4. **The period:** the select and its label are read; "Choose dates" shows From, To and Show, each labelled; a refused period ("A period can be at most 10 years …") is announced.
5. **AW:** "Count working days" is read as a toggle button with its hint, pressed or not; the quarters table reads its new values after a press; "On time", "Late" and "No deadline" are read as words.
6. **AV:** the Member and Rate headers are read as buttons with the column's sort order; after a press the new order can be found; "(1 by alternate)" and the note under the table make the alternate rule clear; the export button is read and, after a download, "The attendance workbook has been downloaded." is announced.
7. **A member's view:** "Your attendance: …" is read before the row, with no export; another member's address (`…/attendance/{someone else}`) says "This person held no seat at a meeting in this period."
8. **A member's meetings:** each meeting link and its attendance in words ("Apologies (represented by their alternate)") are read in order.
