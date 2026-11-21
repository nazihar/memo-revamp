---
status: accepted
date: 2026-11-21
deciders: Claude (agent) under the delegated gate (Phase 4 Q1); product owner aradnazihar (reported: two measures added to the overview's list, paper-reading statistics left out, tables shown by default)
---

# 0070 — Meeting analytics: five defined measures, computed live per viewer from what they may see, tables first

## Context and problem statement
The master plan asks for "basic meeting analytics: attendance rates, actions overdue, time from submission to decision"; the overview adds items deferred and "scoped by the viewer's rights and recusals". Counts can leak: a deferral count that includes an item a director is recused from tells them something about it (E04: no count of a hidden item). Directors are often older, non-technical readers (AGENTS §3.7). Research: `phase-4-forums-meetings/epic-08-register-analytics-close/research.md §4–§5`.

## Decision drivers
- Each measure changes what someone does next (a secretary chases, a chair re-plans), or is a disclosure duty.
- No count includes a row the viewer may not see.
- One definition per measure, shared by screen, export and tests.
- Readable by older users: words and tables first.
- No new store or job unless the budgets force it.

## Considered options
**A. Which measures**
1. **Five: attendance (per member and committee), actions overdue with actions completed on time, time from submission to decision, items deferred, papers on time** (chosen).
2. The overview's four.
3. Add paper opens and reading time, meetings held, items per agenda.

**B. How they are computed**
1. **Live per request, set-based, over the rows the viewer may see** (chosen).
2. A nightly summary per committee and month.
3. A summary for meeting-level measures, live for item-level ones.

**C. How they are shown**
1. **A sentence first, the table visible, horizontal bars with value labels beside it** (chosen).
2. Phase 3's approvals dashboard as it is: chart first, the table behind "Show as table".

## Decision outcome
**A1, B1, C1.**
- **Definitions** (design §2.1):
  - **attendance** = meetings attended in person or remotely ÷ held meetings during the person's seat, per seat holder and per committee; alternates shown as "represented by alternate", never counted for the member; no ranking;
  - **actions** = OPEN or IN_PROGRESS past due today, per committee and owner with ageing bands, paired with the share of actions closed in the period by their original and by their agreed date;
  - **time to decision** = calendar days (working days alongside) from the chain's first submission to the final decision, median and 90th percentile, by committee and quarter and by memo type;
  - **items deferred** = share of submission decisions that are DEFERRED, and items deferred twice or more;
  - **papers on time** = share of held meetings whose first agenda revision was published by the papers deadline, and late items and papers per meeting.
- **Computed** from the published read APIs of meeting (`AttendanceStats`, `AgendaTimeliness`), actions (`ActionStats` on `ActionAccess`) and search (`search_decision_stats` on FULL rows, ADR-0069), in the request's transaction. Held meetings above the viewer's clearance are left out everywhere. Each figure states its basis ("based on 42 decisions you can see in full").
- **Visibility:** officers of the committee, `forums.manage` and `forum.read` see everything above; members in office see their committees' totals and their own attendance row; nobody else has analytics.
- **Attendance export** (XLSX) for officers and oversight, for the annual report's disclosure; 5 an hour; audited.
- **Shown** with the table visible by default and bars beside it; no line, pie, donut or radial charts; every colour has a word; every figure links to the list behind it.

**Why not A2.** "Completed on time" pairs with "overdue" so late closures cannot look good; "papers on time" answers the complaint directors make most often and needs no new data.
**Why not A3.** Paper opens measure the tool, not the work, and read as surveillance of directors; counts of meetings, items and pages guide no action (research §4.3). Opens stay an audit record.
**Why not B2.** A summary cannot subtract what one viewer may not see, so it would either leak counts or be wrong for recused and restricted viewers. At the design volumes the live queries meet the budgets (design §8).
**Why not B3.** Two mechanisms for one page; only attendance would qualify, and it is cheap live.
**Why not C2.** Older readers are more accurate with tables and least accurate with line charts (CHI 2025, research §5); hiding the table behind a button serves them worst.

### Consequences
- Good: no new table, job or permission; the leak rule is the same as the register's and the tracker's.
- Good: the attendance table is exactly what disclosure asks for.
- Bad: every analytics request runs five aggregates; if a tenant outgrows the budget, a per-viewer cache (seconds) is the next step, never a shared summary of item-level measures.
- Neutral: the approvals dashboard keeps its present layout; aligning it to tables-first is a follow-up for Phase 3's owner review.

### Confirmation
- Unit tests of each definition on hand-built cases (term starting mid-period, alternate attending, voided arrival, deferral chain through a referral, an action re-dated twice, a meeting published late).
- The differential leak test: for a recused or restricted viewer, every measure equals the measure computed on the data without the hidden items; counts never include them.
- `AnalyticsVolumeIT` within the budgets; axe and the manual screen-reader pass over flows AU–AW.

## More information
- Design: `phase-4-forums-meetings/epic-08-register-analytics-close/design.md §2`.
- UK Corporate Governance Code 2024, Provision 14: <https://www.frc.org.uk/docs/6709/html/> (accessed 2026-11-21).
- While and Sarvghad, CHI 2025: <https://arxiv.org/abs/2503.02699> (accessed 2026-11-21).
- W3C WAI complex images: <https://www.w3.org/WAI/tutorials/images/complex/> (accessed 2026-11-21).
- Related: ADR-0064 (attendance), ADR-0065 (decisions), ADR-0067 (actions), ADR-0069 (register), ADR-0060 (agenda deadlines).
