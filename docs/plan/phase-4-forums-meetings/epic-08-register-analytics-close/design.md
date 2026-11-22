# E08 Decision register, analytics and close-out — design

> Step 3 of `AGENTS.md §5`. Decisions:
> - **ADR-0069:** the decision register: `search_decision` behind the search module's definer functions, one visibility rule with restricted lines, the viewer's recusals evaluated at query time, exports for auditors.
> - **ADR-0070:** meeting analytics: five measures with fixed definitions, computed live per viewer from the rows they may see, tables first.
>
> Builds on ADR-0054 (search), ADR-0056 (committees), ADR-0059 (submissions), ADR-0060 and ADR-0061 (agenda, the item rule), ADR-0062 (recusal), ADR-0064 (attendance), ADR-0065 and ADR-0066 (decisions, resolutions, minutes), ADR-0067 (actions), ADR-0052 (generated pages), ADR-0025 (rate limits).
>
> Research: `research.md`. Screens: `ux-review.md`, flows AS–AW, **delegated** (Phase 4 Q1). The close-out (exit journey, volumes, demo, runbooks, ASVS, threat models, retrospective) is in `tasks.md` T8–T12.

## 1. The decision register (ADR-0069)

### 1.1 What a row is
One row per **decision** (E06's `minutes_decision`, its current entry), for every committee of the tenant. A resolution is a decision with a number; a decision without one ("noted", "deferred", a procedural item) is in the register too, so the register is the committee's whole record of what it decided.

| Column of `search_decision` | Source | Use |
|---|---|---|
| `tenant_id`, `decision_id` (PK) | E06 | |
| `forum_id`, `forum_type_id` | the meeting's committee and its type | filters, scope |
| `meeting_id`, `meeting_label` ("BRC 3/2026"), `meeting_date` (local date in the meeting's zone) | E03 | display, date filter |
| `item_id`, `item_parent_id`, `item_number` ("5", "5.1") | the latest published revision carrying the item | display; recusal on the parent |
| `classification_rank` | the higher of the item's and the meeting's level, as a rank | clearance |
| `item_restricted_to uuid[]`, `item_title_hidden`, `item_members_only` | the revision item (as E07 copies them, design E07 §1.1) | the item rule on the row |
| `title` | the item's title | shown and searched for FULL rows only |
| `outcome_kind`, `outcome_label` | the current entry | filter, display |
| `is_resolution`, `resolution_id`, `resolution_number`, `resolution_state` (IN_FORCE, VOID, null), `number_period`, `number_sequence` | E06 resolution | number lookup, number order |
| `ref_norm`, `ref_tail` | the resolution number normalised as ADR-0054 does for memos (`BRC-2026-014`, `14`) | lookup in any spelling |
| `state` | derived: RECORDED, NUMBERED, RELEASED, FINAL (E06 §1.1) | filter, display |
| `decided_at`, `final_at`, `corrected` (any entry after the first), `entry_seq` | E06 | filters, export hash |
| `has_conditions`, `valid_until`, `has_dissent`, `vote_mode`, `ratification_required`, `ratified_by_decision_id` | the current entry | exception filters |
| `submission_id`, `memo_id`, `memo_type_id`, `memo_number`, `memo_ref_norm` | the item's submission (E02) | "which resolution approved CP-2026-00042"; memo-type filter |
| `chain_submitted_at` | the first submission of the chain through `previous_id` (a deferral re-queued or a referral) | time to decision (§2) |
| `deferred` (outcome DEFERRED), `prior_deferrals` (DEFERRED decisions earlier in the same chain) | E06 + E02 | deferral measure (§2) |
| `lang`, `body` (outcome label, decision text, conditions; at most 16,000 characters), `tsv` (generated: title A, resolution and memo numbers B, body C) | | full text |
| `updated_at` | | backfill, keyset |

**Not in the row, on purpose:** the secretary's notes, the minutes' discussion text, papers and their extracted text, declarations' natures, vote names (only `has_dissent`), quorum override reasons. The register never holds or searches discussion (ADR-0062, E04 §1.7), so no search can find a recused item by what was said about it.

### 1.2 Indexing (in the change's transaction)
The search module listens, synchronously in the publisher's tenant transaction, as it does for memos (ADR-0054 amendment of E05 T2):

| Event (publisher) | Effect |
|---|---|
| `DecisionRecorded(tenantId, meetingId, itemId, decisionId)` (E06; corrections included, as E07 asked) | `search_put_decision` with the current entry |
| `DecisionReleased(tenantId, decisionId)` (E06) | state RELEASED |
| `ResolutionChanged(tenantId, decisionId)` (E06 T4, **asked of E06**: numbered, voided, renumbered after a void) | number columns |
| `MinutesApproved(tenantId, meetingId)` (E06) | `final_at`, state FINAL for the meeting's decisions (one set-based write) |
| `AgendaPublished(tenantId, meetingId, revision)` (E03) | item number, title, level and item columns of the meeting's decided items |
| `MeetingChanged` (E03: label or classification changed) | meeting columns |

- **Recusal is not indexed.** `DeclarationChanged` is published after commit (E04 §2), so a row carrying recused ids would lag. The viewer's recused items are evaluated at query time (§1.3).
- **Backfill:** the one-time task `search-decisions-backfill` (per tenant, chunks of 500 decisions by id, resumable, `scheduleIfNotExists`) fills the table for decisions recorded before T1 is deployed, and `search_decision_unindexed()` lists any still missing (the nightly check, as `search_unindexed` for memos).
- **Rebuild:** after a PostgreSQL major upgrade or a change to `body`'s make-up, the same task rebuilds every row (runbook).

### 1.3 Who sees what (`DecisionRegisterAccess`, the one rule)
`DecisionRegisterAccess` (search module) is the only code that computes the viewer's parameters, and the SQL in `search_find_decisions` is the only place the rule is applied to rows. An equivalence property test (test plan T1) checks the SQL against E06's `MinutesAccess` for every viewer kind.

**The viewer's parameters, computed per request from live state:**
- `clearance_rank` (ADR-0027);
- `officer_forums`: committees where the viewer is a secretary, the chair or the vice-chair in office today; every committee for a `forums.manage` holder;
- `member_forums`: committees where the viewer holds a seat in office today (member, alternate seat holders not included);
- `reader`: holds `forum.read`;
- `recused_items`: the items the viewer may not see in full because of a declaration, from `Recusals.itemsOf(viewer)` (meeting module, published by E08): effective declarations on the item or its parent, declarations of a member the viewer stands in for as an alternate (ADR-0064), and declarations awaiting the chair's ruling (fail closed, ADR-0062). Computed in the request's transaction, so a declaration committed a moment ago applies at once.

**Three outcomes per row:**
```
inScope(v, r)   := r.forum ∈ v.officer_forums ∪ v.member_forums ∨ v.reader
none(v, r)      := ¬inScope(v, r) ∨ r.classification_rank > v.clearance_rank
recused(v, r)   := r.item ∈ v.recused_items ∨ r.item_parent ∈ v.recused_items
full(v, r)      := ¬none ∧ ¬recused ∧
                   ( r.forum ∈ v.officer_forums
                   ∨ r.forum ∈ v.member_forums ∧ (r.item_restricted_to = ∅ ∨ v ∈ r.item_restricted_to)
                   ∨ v.reader ∧ r.item_restricted_to = ∅ ∧ ¬r.item_title_hidden ∧ ¬r.item_members_only )
line(v, r)      := ¬none ∧ ¬full
```
- **FULL:** every column the viewer may see: title, outcome, text, conditions, validity, state, numbers, memo link, "with dissent"; the vote counts and dissenters' names on the decision's own page follow E06 (not recused, which FULL implies).
- **LINE (restricted line):** committee, meeting label and date, item number, the resolution number and its state (IN_FORCE or VOID), and the words "Restricted item" with the reason in words for the viewer ("You declared an interest in this item" when recused; otherwise "Restricted to named members"). No title, outcome, text, conditions, memo, dates beyond the meeting's, or exception flags. This is E06's per-committee register rule ("number, meeting and 'restricted'"), so the two registers agree and numbering stays checkable.
- **NONE:** not returned and not counted.
- **Former members, guests, item attendees, memo readers** have no scope: the register is for committee people and oversight. Memo readers see the resolution on the memo (E06 §1.4). Former members lose access (E03's rule).

**Matching and counting:**
- Text, outcome, memo type, "with conditions", "with dissent", "to be ratified", "corrected", "validity passed" and memo-number lookups match **FULL rows only**. A LINE row's content is unknown to the viewer, so filtering on it would reveal it.
- Committee, committee type, date range, state and "resolutions only" match FULL and LINE rows (the viewer sees those columns on the line).
- A **resolution-number lookup** (input that looks like a number, ADR-0054's rule) matches FULL and LINE rows: the number is on the line.
- Counts and facets (per committee, per outcome, per year) are computed in the same query over the rows returned under the same rule: outcome facets over FULL rows only; committee and year facets over FULL and LINE rows. Nothing hidden is counted.
- Ranking by `ts_rank_cd` (title A, numbers B, body C) only among FULL rows; "Newest first" (default without text) and "Number order" (per committee and period) skip ranking.

### 1.4 Filters, sort and paging
| Filter | Values | Rows matched |
|---|---|---|
| `text` | 1–200 characters, at most 12 terms; `websearch_to_tsquery`, prefix on the last term (≥ 3 characters), number lookup as ADR-0054 | FULL (numbers: FULL and LINE) |
| `forumIds` | committees in the viewer's scope (others 422 `FORUM_OUT_OF_SCOPE`, never "not found", so the scope list is the only source of committee names) | both |
| `forumTypeIds` | | both |
| `from`, `to` | dates in the tenant's zone on `meeting_date`; at most 25 years apart | both |
| `outcomes` | outcome kinds (APPROVED, APPROVED_WITH_CONDITIONS, REJECTED, DEFERRED, REFERRED, RETURNED, WITHDRAWN, NOTED) | FULL |
| `resolutionsOnly` | boolean | both (a LINE shows whether it has a number) |
| `states` | RECORDED, NUMBERED, RELEASED, FINAL, VOID | both |
| `memoTypeIds` | | FULL |
| `flags` | WITH_CONDITIONS, WITH_DISSENT, TO_BE_RATIFIED, CORRECTED, VALIDITY_PASSED | FULL |
| `sort` | RELEVANCE (with text), NEWEST (default), OLDEST, NUMBER (requires one committee) | |
| `cursor` | keyset on (`meeting_date`, `decided_at`, `decision_id`) or (`number_period`, `number_sequence`) | |

Page size 50. Totals up to 10,000 exactly, beyond that "more than 10,000" (`capped`). Text searches take at most 2,000 FULL candidates before ranking (ADR-0054's cap).

### 1.5 Exports for auditors
- **Who:** a committee's officers (their committees), `forums.manage` and `forum.read` holders. Members in office do not export (they read; auditors and the secretariat export), following E06's matrix where the register is for officers and `forum.read`.
- **What:** the rows of the current filter, as the viewer sees them (FULL and LINE), in the chosen order.
  - **XLSX** (`format=xlsx`, at most 20,000 rows): sheet "Decisions" with one row per decision (committee, meeting, date, item, title or "Restricted item", outcome, resolution number and state, text, conditions, valid until, state, memo number, decided at, minutes approved at, flags, link); sheet "About this export" (who, when in UTC and the tenant's zone, the filters in words, the row count, the rule "Restricted items show their number only"); sheet "Number check" for each committee and period in the result: first and last number, count in force, count void, gaps (none expected), and each void with its date and reason when the viewer may see it (E06's `Resolutions.check`). String cells only, the formula prefix for values starting with `=`, `+`, `-`, `@`, tab, CR or LF (`SheetCells`), streamed through SXSSF with encrypted temporary data.
  - **PDF** (`format=pdf`, at most 2,000 rows): "Register of committee decisions", landscape, through `ReportPages` (ADR-0052): the about block on the first page, a table per committee in date order, the number check at the end, "Page n of m" and "Produced by {name} on {date} — {filters}" on every page. Built on demand, streamed, not stored. Not a PDF/A record: the records are the approved minutes and extracts (E06).
- **Audit before streaming** (the E04 export's pattern): `search.decisions.exported` with the format, filters, row count, and `rowsSha256` (SHA-256 over the sorted `decision_id:entry_seq` pairs), so the export can later be matched to what the register held.
- **Limits:** `REGISTER_EXPORT_PER_USER` 5 an hour (429 after the 403 check); more rows than the format's maximum → 422 `TOO_MANY_ROWS` with the count and "narrow the filters".

### 1.6 The register's entry points
- **"Decisions"** under Committees in the navigation, for anyone with a scope (§1.3).
- From a committee page: "Decisions of this committee" (the register filtered to it; E06's per-committee resolution register stays for number order and the check).
- From a resolution or decision on the meeting page, "In the register" opens the register at that row.
- From analytics, each figure's link (§2.6).

## 2. Meeting analytics (ADR-0070)

### 2.1 The measures
Every measure has one definition, used by the screen, the export and the tests. "Held meetings" are meetings of the committee with status HELD, MINUTES_APPROVED or CLOSED and a local date in the period. Periods are given as `from` and `to` dates in the tenant's zone; the default is the last 12 months; at most 10 years (5 years for per-member attendance).

**M1. Attendance**
- **Per member and committee:** for each person who held a **seat** (chair, vice-chair, member; not invitees, observers or secretaries without a seat) on the date of a held meeting (`ForumDirectory.membersOn`):
  - `expected` = held meetings during their term;
  - `attended` = those where their attendance row reached PRESENT (in person or remote) at any time (`first_arrived_at` set, after voids);
  - also: `inPerson`, `remote`, `joinedLate`, `leftEarly`, `apologies`, `absent` (no apologies), `byAlternate` (apologies with their alternate attending);
  - **rate = attended / expected**, shown "9 of 10 (90 %)". An alternate's attendance is shown as "represented by alternate", never counted as the member's (Provision 14 asks for "individual attendance by directors").
- **Per committee:** the sum of `attended` over the sum of `expected` for all seats, and the number of held meetings.
- No rank or league table; rows sort by name, and the viewer may sort by rate.

**M2. Actions**
- **Overdue now:** issued actions that are OPEN or IN_PROGRESS with `today > dueDate` (E07 §1.4), per committee and per owner (the owner's title first, then the holder's name), with ageing bands 1–30, 31–90 and over 90 days, and how many have had their date revised.
- **Completed on time** (for actions closed in the period): the share whose `doneAt` was on or before the end of their **original** due date, and the share on or before their **current** due date. Shown together: "18 closed: 12 by the original date, 16 by the agreed date".
- From `ActionAccess` for the viewer (E07 §1.14): an action the viewer may not see is not counted.

**M3. Time from submission to decision**
- For decisions in the period on SUBMISSION items whose outcome is final (not DEFERRED or REFERRED): days from `chain_submitted_at` (the first submission of the chain, so a deferral or a referral keeps counting) to `decided_at`, in **calendar days** (the industry's measure, research §4.2), with working days alongside (the tenant's business calendar, ADR-0041).
- Shown as the **median** and the **90th percentile** (nearest rank, as Phase 3's cycle time), with the count, by committee and quarter, and by memo type for one committee.
- Attributed to the committee that decided.

**M4. Items deferred**
- Of the decisions on SUBMISSION items in the period: the count and share with outcome DEFERRED, and the number of items with two or more deferrals in their chain (`prior_deferrals ≥ 1` on a DEFERRED row).

**M5. Papers on time**
- Per held meeting: whether the agenda's **first** published revision was at or before the papers deadline (`AgendaDeadlines.of(meetingId)`, the committee type's agenda policy, published by E07 T7 or E08 T4); and, in the latest published revision, the number of items and papers flagged LATE or TABLED.
- Per committee: the share of held meetings published on time, and late items and papers per meeting.
- Late items and papers are counted only on items the viewer sees in full (the item rule with live recusals); members see only the meeting-level share (§2.3).

### 2.2 How they are computed (live, per viewer)
- **No summary tables.** Each request runs set-based queries over the rows the viewer may see. A pre-computed per-committee total cannot subtract what one viewer may not see, and a count that includes a hidden item is a leak (E04: no count). At the design volumes the live queries meet the budgets (§8).
- **M1 and M5's meeting-level share** come from the meeting module's new published `AttendanceStats` and `AgendaTimeliness` (§11), over held meetings at a classification within the viewer's clearance, in the viewer's committees.
- **M2** comes from the actions module's new published `ActionStats.overdue(viewer, forumIds, today)` and `ActionStats.closedOnTime(viewer, forumIds, from, to)`, built on `ActionAccess` in one query each (§11).
- **M3 and M4** come from `search_decision_stats(...)`, a definer function next to `search_find_decisions`, aggregating **FULL rows only** under the same parameters (§1.3): a LINE row's outcome is unknown to the viewer. The screen says "Based on 42 decisions you can see in full" under each figure.
- **M5's late items and papers** come from `AgendaTimeliness.lateCounts(viewer, meetingIds)`, which applies `PaperAccess` with the viewer's recusals.
- Results are not cached.

### 2.3 Who sees what
| Viewer | Committees | Per-member attendance | Overdue by owner | M3, M4 | M5 |
|---|---|---|---|---|---|
| Secretary, chair or vice-chair in office | their committees | all members | rows by `ActionAccess` | FULL rows | meeting share and late counts (not their recused items) |
| `forums.manage` | all | all members | rows by `ActionAccess` | FULL rows | both |
| `forum.read` (cleared) | all | all members | rows by `ActionAccess` | FULL rows | both |
| Member in office | their committees | **their own row** and the committee's total | rows by `ActionAccess` (as the tracker shows members) | FULL rows | meeting share only |
| Anyone else (former members, guests, owners outside the committee) | — (403) | — | — | — | — |

- Held meetings above the viewer's clearance are left out of every measure, including attendance: their existence is not shown (E04's register rule).
- A person's own action counts are already in "My actions" (E07); analytics adds nothing for them.

### 2.4 The attendance export (annual report)
- `GET /analytics/committees/{forumId}/attendance/export` (XLSX): for officers, `forums.manage` and `forum.read`. One row per seat holder: name, role, term within the period, meetings expected, attended (in person, remote), apologies, absent, represented by alternate, rate; a header block with the committee, period, held meetings, who produced it and when. String cells with the formula prefix; streamed.
- `ANALYTICS_EXPORT_PER_USER` 5 an hour; audited `analytics.attendance.exported` (committee, period, rows).
- This is the table annual reports disclose (Provision 14; BSEC), and Diligent's report (research §4.2).

### 2.5 Not measured
Paper opens and reading time, meeting length, number of items or pages, composite scores (research §4.3).

### 2.6 Each figure leads somewhere
| Figure | Its link |
|---|---|
| Attendance per committee | the committee's attendance table (flow AV) |
| A member's row (officers) | the held meetings of the period with that person's attendance (meeting pages) |
| Overdue now | the committee's action tracker filtered "Overdue" (E07 flow AN) |
| Completed on time | the tracker filtered "Closed" in the period |
| Time to decision | the register filtered to the committee, the period and SUBMISSION decisions, newest first |
| Items deferred | the register filtered to outcome DEFERRED |
| Papers on time | the committee's meetings list for the period |

## 3. API
Conventions as E03–E07: `/api/v1`, RFC 9457 errors, cursor paging, `Idempotency-Key` on mutating POSTs (none here mutate except exports' audit). Search inputs are POST bodies so that words stay out of URLs and logs (ADR-0054, Phase 3 threat model I4).

| Method and path | Access (§4) | Notes |
|---|---|---|
| `GET /decision-register/scope` | Sec, Chair, Mgr, Member, Read | The committees, committee types, outcome kinds and memo types the viewer may filter by; whether they may export and see the number check. Others 403 |
| `POST /decision-register/search` | as scope | §1.4 body → `{rows, total, capped, facets: {forums, outcomes, years}, nextCursor}`; each row `{level: FULL\|LINE, …}`. Rate-limited `DECISION_SEARCH_PER_USER` 30/min; audited |
| `POST /decision-register/export` | Sec, Chair, Mgr, Read | `{format: xlsx\|pdf, filters…}` → streamed file with `Content-Disposition: attachment`; `REGISTER_EXPORT_PER_USER`; audited before streaming |
| `GET /analytics/committees?from=&to=&forumIds=` | Sec, Chair, Mgr, Member, Read | One summary per committee in scope: M1 committee rate, M2 overdue and on-time, M3 median and P90, M4 share, M5 share; each with its count basis |
| `GET /analytics/committees/{forumId}?from=&to=` | as above for that committee (404 outside scope) | The committee's detail: M1 per member (all, or the viewer's own row for a member), M2 by owner and ageing, M3 by quarter and by memo type, M4 by quarter, M5 per meeting |
| `GET /analytics/committees/{forumId}/attendance/export?from=&to=` | Sec, Chair, Mgr, Read | §2.4 |
| `GET /resolutions/{rid}`, `GET /meetings/{id}/decisions` (E06) | E06 | The register's rows link to these |

**Problems:** 403 (no scope; export by a member); 404 (a committee outside scope in a path); 422 `FORUM_OUT_OF_SCOPE` (in a body filter), `TEXT_TOO_LONG`, `TOO_MANY_TERMS`, `RANGE` (period), `SORT_NEEDS_ONE_FORUM`, `TOO_MANY_ROWS`, `cursor INVALID`; 429 on the limits.

**As built (T2):**
- The search body: `text`, `forumIds`, `forumTypeIds`, `from`, `to`, `outcomes` (E06's outcome kinds), `resolutionsOnly`, `states` (RECORDED, NUMBERED, RELEASED, FINAL, VOID), `memoTypeIds`, `flags`, `sort`, `cursor`, `limit` (1–50, default 50). Unknown enum values are 400 (an unreadable body); the rules' problems are 422 `validation` with `errors: [{field, code}]`: `text:TEXT_TOO_LONG`, `text:TOO_MANY_TERMS`, `forumIds:FORUM_OUT_OF_SCOPE` (also a committee that does not exist), `to:RANGE` (before `from`, or more than 25 years), `sort:SORT_NEEDS_ONE_FORUM`, `limit:RANGE`, `<filter>:TOO_MANY` (more than 50 values), `cursor:INVALID`.
- The order defaults to RELEVANCE with words and NEWEST without; RELEVANCE without words is NEWEST. A number-like text is looked up whole (resolution numbers on FULL and LINE rows, memo numbers on FULL rows); otherwise the last word, when it is a plain word of 3+ letters or digits outside a phrase, matches as a prefix.
- Rows: `level`, `reason` (on a LINE), `decisionId`, `forumId`, `forumName`, `meetingId`, `meetingLabel`, `meetingDate`, `itemId`, `itemNumber`, `state`, `resolutionId`, `resolutionNumber`, `resolutionState`; FULL rows add the title, outcome, `resolution`, text, conditions, validity, dissent, vote mode, ratification, `corrected`, `decidedAt`, `finalAt`, the submission and the memo (id, type and its name, number). A LINE never carries `decidedAt` or the entry number, which T1 returns for the cursor and the export's hash only.
- **Cursors are sealed:** AES-256-GCM under the installation's data key (`DataKeys`), with the tenant, the viewer and a SHA-256 fingerprint of the search (normalised words, sorted filters, the order) as associated data. A client cannot read one (it holds a line's decision time), craft or alter one, or use it for another search, person or tenant: 422 `cursor INVALID`.
- The scope: `committees` (id, names, type, `officer`, `member`, `dissolved`), `committeeTypes`, `outcomes`, `states`, `flags`, `memoTypes` (every memo type of the tenant), `canExport` and `canCheckNumbers` (officers, `forums.manage`, `forum.read`).
- The rate limit is checked before the transaction (so a person without a scope who floods the endpoint is 429 too); its key is the tenant and the person.

## 4. Authorisation matrix
Columns: **Sec** (secretary of the committee in office), **Chair** (chair or vice-chair in office), **Mgr** (`forums.manage`), **Member** (seat in office today), **Read** (`forum.read`), **Former** (former member), **Guest/Att** (guest or item attendee of a past meeting), **Memo** (reader of a decided memo only), **Owner** (an action owner outside the committee), **Recused** (a person in any of the first five columns who is recused from the row's item), **Other** (signed in, none of these).

| Action | Sec | Chair | Mgr | Member | Read | Former | Guest/Att | Memo | Owner | Recused | Other |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Register scope and search | own committees | own committees | all | own committees | all | 403 | 403 | 403 | 403 | as their column; the row is a LINE | 403 |
| See a row in full | ✓ (cleared) | ✓ (cleared) | ✓ (cleared) | unrestricted or named (cleared) | unrestricted, title shown, not members-only (cleared) | – | – | – | – | LINE | – |
| Text and content filters match the row | FULL rows | FULL rows | FULL rows | FULL rows | FULL rows | – | – | – | – | never | – |
| Export the register (XLSX, PDF) | ✓ | ✓ | ✓ | 403 | ✓ | 403 | 403 | 403 | 403 | as their column; rows as LINE | 403 |
| The number check in the export | ✓ | ✓ | ✓ | – | ✓ | – | – | – | – | voids' reasons only where not recused | – |
| Analytics summary and committee detail | own committees | own committees | all | own committees | all | 403 | 403 | 403 | 403 | as their column; the item is not counted | 403 |
| Per-member attendance | ✓ | ✓ | ✓ | own row | ✓ | – | – | – | – | as their column | – |
| Late items and papers (M5) | ✓ | ✓ | ✓ | – (meeting share only) | ✓ | – | – | – | – | not their recused items | – |
| Attendance export | ✓ | ✓ | ✓ | 403 | ✓ | 403 | 403 | 403 | 403 | as their column | 403 |

- "Cleared" means the row's or meeting's level is within the viewer's clearance; above it the row or meeting is NONE (not returned, not counted).
- A committee in the path outside the viewer's scope answers 404; in a body filter 422.
- `AuthzMatrixCoverageTest` requires a row for every endpoint; the matrix file is `p4-e08-register-analytics.yaml`.

## 5. Audit events
In the request's transaction (AGENTS §3.3). Free text is kept only in the audit store as Phase 3's search does (search terms), never in logs.
- `search.decisions.performed`: terms, filters, sort, total or capped, the decision ids on the returned page (Phase 3's `search.performed` shape). **As built (T2):** the terms as `textSha256` (SHA-256 of the trimmed, lower-case words with single spaces), `terms` (their number) and `numberLookup`, not the words: what a secretary types can name a borrower or an item that the audit's readers may not see; the hash still lets an investigator check a stated search. Also `nextPage` (a cursor was given).
- `search.decisions.exported`: format, filters, row count, `rowsSha256`; written before the first byte is streamed.
- `search.decisions.backfilled`: system actor, tenant, rows written (once per backfill run).
- `analytics.attendance.exported`: committee, period, row count.
- Analytics reads and the scope read are not audited (lists of counts; ADR-0019 audits sensitive reads; Phase 3's dashboards are not audited).
- `AuditActionCoverageTest` lists every event.

## 6. Tenancy and RLS
- **`search_decision`** follows ADR-0054 exactly: `tenant_id` first in every index; RLS ENABLED, **not FORCED**; owned by the schema owner; **no privilege for `memo_app`**; reached only through the definer functions `search_put_decision`, `search_put_decisions_final`, `search_put_decision_items`, `search_find_decisions`, `search_decision_stats` and `search_decision_unindexed`, each `SECURITY DEFINER` with `search_path` pinned, EXECUTE granted to `memo_app` only, the tenant argument checked against `app.tenant_id` (a mismatch raises an error, so the request fails closed).
- The writers take ids and values from the indexer; the readers take the viewer's parameters (§1.3). No function reads another module's table: everything they need is on the row or in the parameters.
- **Foreign keys:** `(tenant_id, decision_id)` is not a foreign key to `minutes_decision` (another module's table, ADR-0007); the backfill check finds orphans.
- **Jobs:** `search-decisions-backfill` carries `tenantId` and fails closed without it (ADR-0017). The listeners run in the publisher's tenant transaction.
- **Analytics** has no table. Its queries go through the published APIs of meeting, actions and search, each in the caller's tenant transaction under forced RLS (search through its function).
- **Tests:** `DecisionSearchCrossTenantIT` (every function with another tenant's argument, rows of tenant B never returned or counted, the backfill per tenant); `AnalyticsCrossTenantIT` (each endpoint); the ADR-0054 `memo_app` privilege check extended to `search_decision`; `RlsMetaTest` lists `search_decision` with the search tables' exception (enabled, not forced, no app grant).

## 7. Migrations
**Numbering, checked 2026-11-21 on `master` (766740f) and every open branch:** `search/V3` is the last search migration; no open branch holds one. E06 plans `minutes/V1`–`V4`, E07 `forum/V8` and `actions/V1`–`V4`.
- **`search/V4__decisions.sql`** (T1): `search_decision` with:
  - PK `(tenant_id, decision_id)`;
  - GIN on `tsv` (`fastupdate` on, 2 MB pending list, as `search_memo`);
  - `(tenant_id, meeting_date, decided_at, decision_id)` for the cross-committee list (newest first is its backward scan; as built in T1);
  - `(tenant_id, forum_id, meeting_date, decided_at, decision_id)` for one committee;
  - `(tenant_id, forum_id, number_period, number_sequence) WHERE resolution_id IS NOT NULL` for number order;
  - `(tenant_id, ref_norm)`, `(tenant_id, ref_tail)`, `(tenant_id, memo_ref_norm)`;
  - `(tenant_id, meeting_id)` and `(tenant_id, item_id)` for the listeners' set-based updates;
  - the functions of §6.
- If another PR takes `search/V4` first, E08 takes the next search number.
- **No other migration.** Analytics has no table; the meeting, actions and forum additions (§11) are read-only code over existing indexes (`meeting (tenant_id, forum_id, starts_at)`, `meeting_attendance` PK, `forum_submission (tenant_id, subject_type, subject_id)`, E07's `action_item (tenant_id, forum_id, status, due_date, id)`). T5's `EXPLAIN` review decides whether one more index is needed; if so it is a new expand-only migration in that module's next number.
- **Expand-only:** a new table and new functions. Old app versions never call them.

## 8. Performance budget
| Endpoint or job | Target p95 | Volume | How |
|---|---|---|---|
| `POST /decision-register/search`, no text, newest first | < 150 ms | 54,000 decisions per tenant (30 committees × 10 years × 12 meetings × 15 items); checked again at 108,000 | Keyset on `(tenant_id, meeting_date, decided_at, id)` or the committee index; the rule on the row's columns; `recused_items` as an array parameter; totals up to 10,000 in the same scan |
| … with text | < 300 ms (NFR P1) | as above | GIN on `tsv`, at most 2,000 FULL candidates, planned per call (`EXECUTE … USING`, ADR-0054 amendment) |
| … number lookup | < 50 ms | | `ref_norm`, `ref_tail` B-trees |
| Facets | inside the search budget | | `GROUP BY` over the same candidate set |
| Indexing in `DecisionRecorded` | < 20 ms added | — | One upsert through `search_put_decision` |
| `AgendaPublished` refresh | < 50 ms added | 60 items | One set-based update by `(tenant_id, meeting_id)` |
| Backfill | < 10 minutes per tenant | 54,000 decisions | Chunks of 500 by id |
| Export XLSX | < 10 s | 20,000 rows | Pages of 1,000 in one read-only transaction, SXSSF |
| Export PDF | < 15 s | 2,000 rows | `ReportPages`, streamed |
| `GET /analytics/committees` | < 800 ms (Phase 3's dashboard budget) | 30 committees, 12 months: 360 meetings, 14,000 attendance rows, 5,400 decisions, 3,000 open actions | Five set-based queries (attendance, timeliness, overdue, on time, decision stats), no N+1 |
| `GET /analytics/committees/{id}` | < 500 ms | one committee, 5 years: 60 meetings, 2,400 attendance rows, 900 decisions, 500 actions | As above, one committee |
| Attendance export | < 3 s | 50 seat holders, 5 years | |
| Seeded load (Gatling, T5) | register search, `/me/actions`, the tracker, analytics: p95 within the above and NFR P1 at 50 concurrent users | E03, E06, E07 volumes plus the register | Weekly `load` |

- `EXPLAIN` is reviewed (as the definer function's owner, ADR-0054's gate) for the search's three orders, text, number lookup, facets and stats, for five reader profiles: a secretary of three committees, a director member of two, an auditor with `forum.read`, a recused director, a `forums.manage` holder.
- The generator: E03 T5's `MeetingVolume`, extended by E06 T9 (decisions, resolutions) and E07 T9 (actions), gains in T5 the register's backfill and attendance histories with apologies, alternates and late arrivals at realistic rates (members attend 85–95 %).

## 9. Background work
- **`search-decisions-backfill`** (one-time task per tenant, instance id = tenant + run, resumable by the last id, `scheduleIfNotExists` at start-up when `search_decision_unindexed()` is not empty, and by an operator through the runbook for a rebuild). Idempotent: each chunk upserts.
- **The nightly check** (the existing search nightly): `search_decision_unindexed()` count reported as a metric; above zero after the backfill it schedules the task again.
- **Everything else is synchronous:** indexing in the change's transaction; searches, analytics and exports in the request (exports are streamed, bounded and rate-limited, so they are not jobs; this follows E04's declarations export and E07's tracker export).

## 10. UX flows (`ux-review.md`, delegated)
- **AS, The decision register (secretary, auditor, director):** search and filters, restricted lines, number lookup, exception filters, empty states.
- **AT, Exporting for auditors:** the export dialog (spreadsheet or PDF), the about sheet and number check, too many rows.
- **AU, Committee analytics overview:** one sentence and a row per committee: attendance, actions overdue and on time, time to decision, deferrals, papers on time; each with its table and bars.
- **AV, Attendance per member:** the table with words and data bars; the member's own view; the export for the annual report.
- **AW, A committee's detail:** time to decision by quarter and memo type, deferrals, overdue by owner with ageing, papers on time per meeting; "based on n decisions you can see in full".

## 11. What E08 needs from other modules
| Need | From | Status | If not delivered by E08's task |
|---|---|---|---|
| `DecisionRecorded` for corrections too, with `seq`; `Decisions.find`, `Decisions.of(meetingId)` | E06 T3 | Asked by E07 | E08 T1 waits for E06 T3 |
| `ResolutionChanged(tenantId, decisionId)` on numbering, voiding and a new number after a void | E06 T4 | **New ask** | E08 T1 adds the event in the minutes module (one publish per write) |
| `Resolutions.check(forumId, period)` published (E06 has the endpoint) | E06 T4 | Planned as an endpoint | E08 T3 publishes the existing service method |
| `MinutesApproved`, `DecisionReleased` | E06 T4, T6 | Planned | Rows stay RECORDED/NUMBERED until then (state shown as is) |
| `Recusals.itemsOf(userId)`: items (with sub-items) the person may not see in full, including alternates' inheritance and awaiting rulings | meeting (E04's `RecusalStore`) | **New, E08 T1** | — |
| `AttendanceStats.forMembers(forumIds, from, to, clearance)` and `.forCommittees(…)` | meeting | **New, E08 T4** (read-only over `meeting_attendance`, `meeting`, `ForumDirectory.membersOn`) | — |
| `AgendaTimeliness.share(forumIds, from, to, clearance)` and `.lateCounts(viewer, meetingIds)` | meeting | **New, E08 T4**; uses `AgendaDeadlines.of` (E07 T7 publishes it, or E08 T4 if first) | — |
| `SubmissionChains.chainStart(submissionId)` and `.chain(submissionId)`: the first `submitted_at` along `previous_id`, and the chain (named `SubmissionChains`: the forum module's internal `ForumSubmissions` service has the planned name) | forum | **Built, E08 T1** | — |
| `ActionStats.overdue(viewer, forumIds, today)` and `.closedOnTime(viewer, forumIds, from, to)` on `ActionAccess`, replacing E07's viewer-less `ActionCounts.overdue(forumId)` | actions | **Built in E07 T8:** `ActionStats.overdue(forumIds, today)` and `.closedOnTime(forumIds, from, to)` for the signed-in viewer | — |
| `ReportPages` landscape tables | memo (ADR-0052) | Built (used by E07's report PDF) | — |
| `PaperSurfaceRegistry`: replace the E08 placeholder with the surfaces, and its `modules` list's `com.memo.decision` with `com.memo.search` (register endpoints; the memo search endpoints listed with their reason) and `com.memo.analytics` | test | E08 T2, T4 | — |

**Module boundaries** (`ApplicationModules.verify()`):
- `search` → memo (as today), minutes, meeting, forum, identity, authz, audit, common. Nothing depends on search except `demo` and `analytics`.
- **`analytics`** (new, `com.memo.analytics`, packages `api`, `measures`, `export`) → search (`DecisionStats`), meeting (`AttendanceStats`, `AgendaTimeliness`), actions (`ActionStats`), forum (`ForumDirectory`), identity, authz, audit, common. Nothing depends on analytics.
