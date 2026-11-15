# E05 Inbox, search and dashboards — tasks

> Step 5 of `AGENTS.md §5`. Vertical slices; each task is its own PR, stacked until the owner merges. The web tasks for search (T6) and the team view (part of T7) start only after the owner's review of `ux-review.md` flows H and I.

| Task | Goal | Touches | Size | Depends |
|---|---|---|---|---|
| T1 | **V1 volume and the search spike:** the V1 generator (1,000,000 memos in one tenant, with history, notes, extracted text with a skewed vocabulary and needle terms, four reader profiles); a prototype of the search tables and `search.find`; `EXPLAIN` gates as `memo_app`; first p95s; `fastupdate` comparison. Decides the caps and the pending-list limit | perf, search (new module), db | L | — |
| T2 | **Search index:** module `search` with `search/V1` (tables, `pg_trgm`, the function); listeners for memo and note events; the chunk job on TEXT renditions; reclassification; backfill; `RlsMetaTest` exceptions; Semgrep rule for SECURITY DEFINER | search, memo (events), files (event) | L | T1 |
| T3 | **Search API and saved searches:** `GET /search` (filters, relevance and newest, cursor, facets, capped count, snippets), input caps, rate limit, timeout, audit; saved searches CRUD; the equivalence and property tests; matrix | search, memo (access scopes), authz | L | T2 |
| T4 | **Inbox: watching, due sort, bulk:** `memo/V18` (`memo_watch`, indexes); watch endpoints and auto-watch; Watching list; two-tier notifications with the digest job and preferences; sort by due; `POST /inbox/bulk` with results per item | memo, notification, identity (preferences), workflow (claim, release, reassign) | L | — |
| T5 | **Team view and dashboards backend:** head-of-unit scope and `inbox.team.read`; `GET /inbox/team`; `memo.dashboard.read`; `GET /dashboards/approvals` (ageing by working days, overdue, cycle time median and P90, throughput); matrix | memo, workflow (queries), org (heads, seats), authz | M | T4 |
| T6 | **Web: search** (after owner review H): the top-bar box with recent and saved; the Search page with the filter panel, chips, URL state, snippets with `<mark>`, polite count, empty, limit and timeout states; save and manage saved searches; i18n; visual | web | L | T3, owner review H |
| T7 | **Web: inbox and dashboards** (team view after owner review I): Follow toggle and Watching tab; checkboxes and bulk bar with results per item; due sort; Team tab; notification preferences; Approvals dashboard with Recharts 3.10.1 (figures, summaries, data tables), CSP check of the charts; i18n; visual | web | L | T4, T5, owner review I |
| T8 | **Close:** Gatling at V1 (search for four reader profiles, inbox, team view, dashboard; p95 asserted); E2E (`search.spec`, `inbox-team.spec`); demo (saved search, watches, a head's team view); ASVS rows; runbook (search: indexing, backfill, upgrades, OpenSearch triggers; watching and digests); STATUS and log | all | M | all |

**Order:** T1 first (it may change ADR-0054's numbers). T2 → T3. T4 can run beside T2 and T3, and T5 follows T4. T6 and T7 come after the owner's review. T8 is last.

## Acceptance criteria (selection; the rest in `test-plan.md`)
- **T1:** given V1 and the four reader profiles, the `EXPLAIN` plans of the prototype (as `memo_app`) use the GIN indexes, and search p95 is recorded. If any profile exceeds 300 ms after tuning, the log says so and ADR-0054's fallback order is followed.
- **T2:**
  - given a note posted, its row exists in the same transaction;
  - given a 1 MB extracted text, it is split into chunks of at most 8,000 words, and a phrase near the end is found;
  - given an attachment reclassified above a reader's clearance, its chunks stop matching for that reader.
- **T3:**
  - given a reader without access to a memo, no result, facet, count or snippet reveals it;
  - given `00042` or `CR/2026/0001`, the memo is found;
  - given 31 searches in a minute, the 31st is refused with 429;
  - every search is audited with its terms.
- **T4:**
  - given a bulk claim of 5 tasks where 1 was decided meanwhile, 4 succeed and 1 is reported `CHANGED`;
  - given a watched memo's step decided, the watcher sees it in the app at once and in the next digest;
  - no bulk decision endpoint exists.
- **T5:**
  - given a head of the Credit Division, the team view lists open tasks of people seated in its subtree, by due time with overdue first, and a memo they cannot read shows as "Restricted memo";
  - given a person without a head seat or grant, 403.
