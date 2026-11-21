# E08 Decision register, analytics and close-out — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`.
> - **Phase-4 special tests (§12):** E08's surfaces in "recusal enforced across API, search, notifications and exports", and the phase's other special tests confirmed green at close (quorum properties, iCalendar validation, resolution numbering under concurrency).
> - **The exit criterion's chain** end to end through the UI (`committee-cycle.spec`) and both ways in an IT with the audit chain verified.
> - **Equivalence** of the register's SQL rule with E06's rule (as ADR-0054's search with E03's).
>
> CI stages: PR (unit, property, integration, matrix, leak suite, smoke E2E), nightly (PIT, browsers, visual, pseudo-locale, fuzzing), weekly (load). E08 adds no state machine: decisions' and actions' machines are E06's and E07's.

## 1. Scope and risks
**Risks covered:**
- a recused or restricted item found through the register's text, filters, facets, ranking, exports or analytics counts (overview risk R1);
- a declaration not yet applied (a lag between declaring and searching);
- the register disagreeing with E06's meeting page or per-committee register;
- a stale or wrong row after a correction, a void or a new revision;
- a measure defined one way on screen and another in the export;
- a slow privileged function, or a sequential scan at volume;
- formula injection in exports;
- the phase closing with a nightly or weekly stage red, or a journey that passes only locally.

**Seed** (the E06 and E07 seeds, plus):
- BRC (7 voting members, `secretary@`, `secretary2@`, chair `director3@`) with 12 held meetings over the last year, attendance with apologies, an alternate standing in, late arrivals and one absence without apologies; MCC with 6 held meetings;
- `director@` recused from item 5 of BRC 3/2026 (decided BRC/2026/014 "Approved with conditions", one named dissent); item 6 restricted to two members with a hidden title; item 8 deferred then approved at BRC 4/2026; item 9 in a members-only section; a decision above `auditor@`'s clearance;
- `auditor@` with `forum.read`; `former@`, a member whose term ended; `guest@`, a guest of BRC 3/2026; `exec@`, an action owner outside the committee; `memoreader@`, a reader of the credit memo only;
- a voided resolution (BRC/2026/009) with its reason;
- **canaries:** `CANARY-DEC-…` in item 5's and item 6's decision text and conditions, and in their titles; checked in every register response, facet, export (both formats' text), analytics response and audit detail for people who may not see them.

## 2. Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Unit | `DecisionRowTest` (the row from an entry: state derivation, flags, `ref_norm`/`ref_tail` of resolution and memo numbers, `body` capped at 16,000, the column list has no notes, discussion, natures, names or reasons) |
| T1 | Property | `DecisionRegisterEquivalenceProperties` (jqwik): random committees, roles in office and ended, `forum.read`, `forums.manage`, clearances, items with restrictions, hidden titles, members-only sections and parents, declarations (self, on a parent, by an alternate's member, awaiting a ruling), decisions with and without numbers; for every viewer the function's FULL/LINE/NONE equals `MinutesAccess` + the line rule; no facet or total counts a NONE row; content filters never match a LINE row |
| T1 | Integration | `DecisionIndexingIT`: a row per recorded decision in the same transaction (rolled back with it); correction, release, numbering, void, renumbering after a void, minutes approval, a new agenda revision changing the title, level and restriction; `MeetingChanged` |
| T1 | Integration | `DecisionBackfillIT`: decisions recorded with the listener off are filled by the task in chunks, resumed after a forced stop, idempotent on a second run; `search_decision_unindexed()` empty after |
| T1 | Persistence/RLS | `DecisionSearchCrossTenantIT` (each function with tenant B's argument raises; tenant B's rows never returned or counted); `SearchPrivilegesIT` extended (`memo_app` has no SELECT, INSERT, UPDATE or DELETE on `search_decision`; EXECUTE only on the functions); `RlsMetaTest` lists the table under the search exception |
| T1 | Performance gate | `DecisionSearchExplainIT` (as the function's owner): the plans of newest-first, committee, number order, text, number lookup, facets and stats use the indexes for the five profiles, custom and generic plans; fails on a sequential scan of `search_decision` |
| T1 | Architecture | `DecisionRegisterAccess` is the only caller of `search_find_decisions` and `search_decision_stats`; the definer functions live in `db/migration/search/` only |
| T2 | Integration | `DecisionRegisterIT`: every filter alone and combined; the rows matched by FULL only vs both (design §1.4 table); sorts (relevance, newest, oldest, number with one committee; `SORT_NEEDS_ONE_FORUM`); keyset paging stable under inserts; totals and `capped`; facets; `FORUM_OUT_OF_SCOPE`; number lookup in three spellings; memo-number lookup (FULL only) |
| T2 | Integration | `DecisionRegisterRecusalIT`: `director@` declares on item 7 and searches in the same second → line; withdraws → full; an alternate inherits the member's recusal; an awaiting ruling is a line; a recused officer's own committee rows otherwise full |
| T2 | Security | Text caps (`TEXT_TOO_LONG`, `TOO_MANY_TERMS`), the 2 s timeout, `DECISION_SEARCH_PER_USER` 429; search audited with terms, filters and the page's ids; POST only (a GET with a query string is 405) |
| T2 | Matrix | `p4-e08-register-analytics.yaml`: every endpoint × Sec, Chair, Mgr, Member, Read, Former, Guest/Att, Memo, Owner, Recused, Other (design §4); `AuthzMatrixCoverageTest` |
| T2 | Contract | OpenAPI for the register (rows with `level`), generated client updated, drift check |
| T3 | Integration | `DecisionExportIT`: XLSX sheets and columns, string cells, the formula prefix (`=`, `+`, `-`, `@`, tab, CR, LF, full-width forms), LINE rows as "Restricted item", the number check (1..n, voids, reasons only for viewers who see the item); PDF text (PDFBox) with the about block, tables per committee, page footer; 20,000 and 2,000 row limits (`TOO_MANY_ROWS`); `REGISTER_EXPORT_PER_USER` 429 counted after the 403; audit before the first byte with `rowsSha256` equal to a recomputation |
| T4 | Unit | `AttendanceMeasureTest` (term starting and ending mid-period, an alternate attending, a voided arrival, joined late and left early still attended, apologies vs absent, a seat with no held meetings); `ActionMeasureTest` (ageing bands, re-dated twice, closed by the original vs the agreed date, DONE_PENDING past date not overdue); `DecisionTimingTest` (median and nearest-rank P90, calendar and working days, a referral chain, a deferral chain, DEFERRED and REFERRED excluded as final); `TimelinessTest` (first revision at, before and after the deadline; LATE and TABLED counts) |
| T4 | Integration | `AnalyticsIT`: the summary and the detail for each viewer kind; members' own row only; clearance excludes a meeting from attendance; periods (default 12 months, `RANGE` above 10 years, 5 for per-member); the attendance export (columns, formula prefix, audit, `ANALYTICS_EXPORT_PER_USER`) |
| T4 | Persistence/RLS | `AnalyticsCrossTenantIT` (each endpoint and published stats API with another tenant's committee) |
| T4 | Matrix | the analytics rows of `p4-e08-register-analytics.yaml` |
| T5 | Performance | `RegisterVolumeIT` (54,000 and 108,000 decisions) and `AnalyticsVolumeIT` against design §8, with every read `EXPLAIN`ed and the plans kept in the log; weekly `load` |
| T5 | Load | Gatling `CommitteeLoadSimulation` (register search by five profiles, `/me/actions`, the tracker, analytics) at 50 users, p95 within NFR P1; run by hand on `master` once before T12 |
| T6 | Component | `register.test.tsx` (filters as chips and Reset, restricted lines with their words, number lookup, empty and no-scope states, the export dialog and too many rows; keyboard order) |
| T6 | E2E | `decision-register.spec` (secretary, auditor, recused director; export of both formats downloaded and opened); axe in English and Arabic; visual baselines |
| T7 | Component | `analytics.test.tsx` (summary sentence, table visible by default, bars with value labels, words with every colour, each figure's link, the member's own row, "based on n decisions you can see in full") |
| T7 | E2E | `meeting-analytics.spec` (officer and member); axe; visual baselines; pseudo-locale and RTL (Arabic) layout of tables and bars |
| T7 | Manual | keyboard and NVDA pass over AU–AW (tables read with headers; bars hidden from the accessibility tree when the table carries the data; links named) |
| T8 | E2E (exit) | `committee-cycle.spec`: the whole chain through the UI (tasks T8), in CI on the full stack and against dev |
| T8 | Integration (exit) | `CommitteeCycleIT` (E07 T12, extended): memo ↔ submission ↔ item ↔ decision ↔ resolution ↔ action ↔ follow-up memo walked both ways through published APIs; each step's audit entry present with its actor; `AuditChainVerifier` over the tenant's chain after the cycle; the register row and the analytics counts reflect the cycle |

## 3. Phase-4 special test covered here
| Special test (TESTING_STRATEGY §12) | E08's part |
|---|---|
| Recusal enforced across API, search, notifications and exports | The REGISTER, EXPORTS (register files) and ANALYTICS surfaces of the leak suite (§4); the equivalence property |
| Quorum rules (property tests); iCalendar validation and golden files; resolution numbering under concurrency | Confirmed green in the nightly at T12 (owned by E05 and E06) |

## 4. The leak-suite surfaces added by E08
`PaperSurfaceRegistry`'s E08 placeholder is replaced; each surface has a recused-member column, an awaiting-ruling column and a restricted-item (not named) column, with the canaries checked in every response.

| Surface | Probe | Expected for the recused or restricted viewer |
|---|---|---|
| `POST /decision-register/search` without text | `director@`, BRC 2026 | item 5 a LINE with number and "Restricted item"; no title, outcome, text, conditions, memo, flags |
| … with words of item 5's decision text, its title, its memo number | `director@` | no row; totals and facets equal to the search without item 5 |
| … with an outcome, "with dissent" or "with conditions" filter | `director@` | item 5 not returned, not counted |
| … with BRC/2026/014 | `director@` | the LINE only |
| … ranking | `director@` | the order of the other rows equals the order without item 5 |
| `GET /decision-register/scope` | `director@`, `former@`, `guest@`, `memoreader@`, `exec@` | committees for `director@`; 403 for the others |
| `POST /decision-register/export` (XLSX, PDF) | `secretary2@` recused from item 5; `auditor@` for item 6 | "Restricted item" only; the number check without the void's reason for an item the viewer may not see; no canary in either file's text |
| `GET /analytics/committees` and `/{forumId}` | `director@`, `auditor@` | **differential:** every measure equals the measure computed on the seed without the items the viewer may not see in full (M3, M4, M5's late counts) and without the actions `ActionAccess` hides (M2); attendance unchanged (meeting level) |
| Attendance export | `secretary2@` | no item content at all |
| Audit viewer and export | `audit.read` | `search.decisions.performed` carries terms only in the audit store; no decision text in any detail |
| Meta-test | — | every controller method of `meeting`, `forum`, `minutes`, `actions`, `search` and `analytics` is registered; no `pending` placeholder left at T12 |

## 5. Phase close checks (T11, T12)
- Every `test-plan.md` of E01–E08: each row has its test merged or a recorded reason (the epic's log) — a checklist in the retrospective.
- `AuditActionCoverageTest`, `EndpointCoverageTest`, `AuthzMatrixCoverageTest` and the leak suite's meta-test green with no placeholder.
- Nightly green on `master`, including PIT (numbering, quorum, the measures), Jazzer (quorum-rule parser, iCalendar writer input), browsers, visual, pseudo-locale; the weekly `load` green after a manual run.
- Migrations apply on an empty and on a seeded database (CI gate) with every Phase 4 migration.
- The journeys of every Phase 4 epic pass against dev after the demo seed (E2E against dev, as Phase 2's close).

## 6. CI stages
| Stage | E08 tests |
|---|---|
| PR | unit, property (100 tries), integration, matrix, leak suite, contract, component, smoke E2E (`decision-register.spec`, `meeting-analytics.spec`, `committee-cycle.spec`) |
| Nightly | property (1,000 tries), PIT on the measures and `DecisionRow`, browsers, visual, pseudo-locale |
| Weekly `load` | `RegisterVolumeIT`, `AnalyticsVolumeIT`, `CommitteeLoadSimulation` |
| Against dev (T8, T12) | `committee-cycle.spec` and every Phase 4 journey |
