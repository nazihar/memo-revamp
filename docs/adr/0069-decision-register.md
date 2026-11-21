---
status: accepted
date: 2026-11-21
deciders: Claude (agent) under the delegated gate (Phase 4 Q1: E08's screens are not reviewed by the owner before build); product owner aradnazihar (two refinements of the overview reported: restricted lines instead of excluded rows, and recusals evaluated at query time)
---

# 0069 — Decision register: decisions in a search table behind the search functions, one visibility rule with restricted lines, recusals at query time, exports for auditors

## Context and problem statement
Phase 4 E08 adds the cross-committee decision register the master plan asks for: "cross-forum search and filters (date, forum, type, outcome), exportable for auditors". Decisions and resolutions are recorded per item by E06 (ADR-0065) and read under `MinutesAccess`, which builds on the item rule (ADR-0061) and recusal (ADR-0062). A tenant holds about 54,000 decisions over ten years (30 committees meeting monthly).

Three questions:
1. **Where the register's search runs.** Under forced RLS PostgreSQL does not use a GIN index for `@@` (ADR-0054).
2. **What a viewer sees of a decision they may not see in full**: recused, restricted to others, title hidden, or in a members-only section. E04's placeholder said such rows are "excluded"; E06's per-committee register shows a recused officer "number, meeting and 'restricted'".
3. **How recusal reaches the register.** `DeclarationChanged` is published after commit (E04 design §2).

Auditors read the minutes of every meeting in the period (ASA 560 ¶7(c), Australia's conformed text of ISA 560), test completeness of numbered resolutions, and look for dissent, conditions and decisions taken without a quorum (Basel 2015 ¶60; Bangladesh Bank's 2025 dissent notice). Research: `phase-4-forums-meetings/epic-08-register-analytics-close/research.md §3`.

## Decision drivers
- No leak of a recused or restricted item through rows, counts, facets, ranking or text matches (E04, TESTING_STRATEGY §12).
- The same answer as E06's per-committee register and meeting page.
- Completeness can be shown: every number is accounted for.
- p95 under 300 ms with text at 54,000 decisions (NFR P1), checked at twice that.
- One privileged database path, already reviewed (ADR-0054).

## Considered options
**A. The search**
1. **A `search_decision` table in the search module behind new `SECURITY DEFINER` functions, as ADR-0054** (chosen).
2. A `tsvector` on `minutes_decision_entry` under forced RLS: no GIN use (ADR-0054's measurement).
3. No full text: filters only, text matched with `ILIKE` within a committee.

**B. Rows the viewer may not see in full**
1. **A restricted line: committee, meeting, date, item number, resolution number and state, "Restricted item"; never matched by text or content filters, never counted in content facets** (chosen).
2. Excluded entirely (the E04 placeholder).
3. The meeting page's recused line (outcome and text, no vote or names).

**C. Recusal**
1. **The viewer's recused items computed in the request's transaction and passed to the function** (chosen).
2. Rows carrying the item's recused user ids, refreshed by `DeclarationChanged` (the E04 placeholder).
3. The function joining the meeting module's declaration tables.

**D. Placement**
1. **Table, functions, indexing and the register's API in `search`; analytics in a new `analytics` module (ADR-0070)** (chosen).
2. A new `decision` module owning the table and its functions (the name E04's meta-test guessed).

## Decision outcome
**A1, B1, C1, D1.**
- **The table** (`search/V4`): one row per decision with its current entry, the item's columns for the item rule (level, restricted-to, title hidden, members-only, parent), outcome, resolution number with `ref_norm`/`ref_tail`, state, flags (conditions, dissent, to be ratified, corrected, validity), the source memo's number and type, the submission chain's first `submitted_at`, and a generated `tsv` over the item title (A), numbers (B) and outcome label, text and conditions (C). **Never** the secretary's notes, minutes discussion, papers, natures of interests, dissenters' names or override reasons.
- **Indexing** in the change's transaction from `DecisionRecorded` (with corrections), `DecisionReleased`, `ResolutionChanged` (asked of E06 T4), `MinutesApproved`, `AgendaPublished` and `MeetingChanged`; a resumable backfill and a nightly unindexed check.
- **The rule** (`DecisionRegisterAccess`): scope = committees where the viewer is an officer or a member in office today, all for `forums.manage` and `forum.read`; above clearance nothing; recused → LINE; officers FULL; members FULL unless restricted to others; `forum.read` FULL unless restricted, title-hidden or members-only. LINE otherwise. Former members, guests, item attendees and memo readers have no register.
- **Text, outcome, memo type and exception filters match FULL rows only.** Committee, type, date, state and "resolutions only" match both. A number lookup matches both (the number is on the line). Outcome facets count FULL rows only.
- **Exports:** XLSX (20,000 rows) with "About this export" and a per-committee-and-period number check (E06's `Resolutions.check`), and a PDF through `ReportPages` (2,000 rows); officers, `forums.manage` and `forum.read`; 5 an hour; audited before streaming with the filters, the count and a SHA-256 over the rows' `decision_id:entry_seq`.

**Why not B2.** Excluding the row hides a number from an auditor's completeness check and disagrees with E06's per-committee register, while the line reveals nothing E06 does not already show: the item's line is on the agenda for these viewers.
**Why not B3.** The register is for searching and exporting; a recused officer's outcome and text would then be matchable by filters and exported in a spreadsheet, which the meeting page does not do.
**Why not C2.** A row refreshed after commit lags a new declaration by the listener's delay, a window in which a just-recused director could still find the item's text. Computing the set in the request has no window, and the set is small (items a person has declared on).
**Why not C3.** A definer function reading another module's tables couples the privileged path to the meeting schema; parameters keep the function's SQL self-contained, as ADR-0054's unit scopes are.
**Why not D2.** The definer functions belong together in `db/migration/search/` (the ASVS row "one privileged database path"); a module just for a table adds a boundary without a reason.

### Consequences
- Good: one privileged path, one rule tested for equivalence with E06; recusal applies the instant it is committed; auditors get completeness and exceptions.
- Good: the register answers "which resolution approved memo CP-2026-00042" in one lookup.
- Bad: the search module now depends on minutes, meeting and forum. Nothing depends on search except `demo` and `analytics`, so there is no cycle.
- Bad: a second derived copy of decision text, encrypted at rest with the database as the search tables are (ADR-0054).
- Neutral: the per-committee register of E06 stays for number order and its check; the cross-committee register links to it.

### Confirmation
- An equivalence property test: random committees, members, officers, readers, clearances, restrictions, hidden titles, members-only sections, declarations (on items, parents, by alternates, awaiting rulings) and decisions; the function's FULL, LINE and NONE equal `MinutesAccess` plus §B's line rule for every viewer, and no count or facet comes from a row the viewer may not see.
- The `EXPLAIN` gate for five reader profiles, failing on a sequential scan of `search_decision`.
- The recusal leak suite's register, export and stats surfaces with canaries in decision text and conditions.
- `RegisterVolumeIT` at 54,000 and 108,000 decisions within the budgets.

## More information
- Design: `phase-4-forums-meetings/epic-08-register-analytics-close/design.md §1`.
- Related: ADR-0054 (search), ADR-0061, ADR-0062, ADR-0064, ADR-0065, ADR-0066, ADR-0052 (generated pages), ADR-0025 (rate limits).
- ASA 560 ¶7(c): <https://www.legislation.gov.au/F2009L04094/2011-07-01/2011-07-01/text/original/epub/OEBPS/document_1/document_1.html> (accessed 2026-11-21).
- BoardPro decision register: <https://help.boardpro.io/en/articles/10297276-decision-register> (accessed 2026-11-21).
