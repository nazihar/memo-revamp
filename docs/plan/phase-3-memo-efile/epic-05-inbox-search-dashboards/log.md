# E05 Inbox, search and dashboards — log

## 2026-11-15 — Plan written
- **Research:** R7 (search) and R10 (inbox, team views, dashboards, watching, bulk actions, charts), by two research agents and checked by the lead (`research.md`). The R7 agent ran spikes on `postgres:18.6` with 200,000 rows.
- **Main finding:** under forced RLS, PostgreSQL does not use a GIN index for `@@`, because the operator is not leakproof (reproduced: 28 ms sequential scan vs 0.3 ms through a SECURITY DEFINER function). Hence ADR-0054: search tables of their own behind one audited definer function that applies the E03 rule, with an equivalence test.
- **ADR-0054** (full-text search) is accepted under the delegated gate. The search screen flows still go to the owner.
- **Design, threat model, tasks T1–T8 and test plan written.** Flows H (search) and I (team view) and six questions are in `ux-review.md` for the owner. T1–T5 (backend) can start; T6 and T7 wait for the review.
- **Versions to watch:**
  - PostgreSQL 18.7 (GIN fixes) is adopted on or after 2026-11-26;
  - PostgreSQL 19 (GA 2026-11-19) needs a search rebuild when adopted;
  - Recharts 3.10.1 is pinned (3.11 canaries skipped).

## 2026-11-15 — Owner's review
- The owner answered "approved as recommended. keep working". Flows H and I and the six recommended answers stand (`ux-review.md`). The web tasks T6 and T7 are unblocked.

## 2026-11-15 — T1: V1 volume and the search spike
- **Built:**
  - `search/V1`: the three search tables, their indexes and the definer functions (`search_find`, `search_snippets` and the writers).
  - `ReaderScopes`, `SearchFinder`, `SearchIndex` and `SearchReferences` in the search module, and `MemoSearchSource` in the memo module.
  - The V1 generator (`SearchVolume`) and `SearchPerformanceIT`.
  - `RlsMetaTest` exceptions for the three tables and the six functions, with new checks: no privileges for `memo_app` and a pinned search path.
- **Found and changed during the spike** (ADR-0054 amendment):
  - **No `pg_trgm`:** `memo_migrator` cannot create extensions. Numbers are found by B-tree on `ref_norm` and `ref_tail` instead.
  - **Generic plans:** PL/pgSQL's cached generic plan made a narrow reader's common-word search take 1.3 s. The query now runs with `EXECUTE … USING` and is planned per call: 11 ms.
  - **One table per part:** an OR of the participant and unit routes, and joins from notes and chunks to the memo row, kept parts from stopping at the cap. Each source is now searched in two single-table parts, and notes and chunks carry their memo's access and filter columns.
  - **Readers with many memos:** participant lookups are per memo up to 5,000 memos; above that the planner chooses.
  - **Writes:** go through definer functions too, because default privileges would otherwise have granted `memo_app` access to the tables.
- **V1 results** (1,000,000 memos, 2,000,000 notes, 200,000 chunks of 200 words, one tenant):
  - **Run conditions:** generated in 292 s, on a laptop (14 CPUs and 16 GB for Docker). PostgreSQL 18.6 ran with `shared_buffers=3GB effective_cache_size=8GB work_mem=32MB random_page_cost=1.1`. Single client, p95 of 20 runs per query.

    | Profile | Common word | Mid | Rare | Two words | Phrase | Prefix | Number |
    |---|---|---|---|---|---|---|---|
    | Narrow participant (300 copies) | 14 | 12 | 10 | 11 | 11 | 11 | 9 |
    | Unit head (5 of 20 units, 2,000 memos acted on) | 77 | 107 | 248 | 169 | 245 | **307** | 48 |
    | Auditor (tenant-wide) | 50 | 73 | 75 | 98 | 100 | 91 | 4 |
    | Low clearance (tenant-wide, rank 0) | 39 | 80 | 83 | 100 | 104 | 81 | 4 |
    | Heavy participant (20,000 memos) | 101 | 160 | 175 | 222 | 201 | 222 | 18 |

    (ms, p95)
  - **Cap** (auditor, common word): 1,000 / 2,000 / 5,000 candidates take 19 / 34 / 87 ms. The 2,000 cap stands.
  - **GIN `fastupdate`:** 5,000 note rows insert in 310 ms with it on and 340 ms with it off. It stays on, with the 2 MB pending list.
  - **Stock settings:** the same volume took 230–897 ms for the unit head (phrase 897 ms) and 685 ms on average for a heavy participant with 200,000 memos. The deploy Compose file gives PostgreSQL 1 GB and default settings; **database sizing for V1 is carried to T8** (Compose settings, runbook).
- **Over budget:** the unit head's search-as-you-type (prefix) is 307 ms against 300 ms. **Decision:** search-as-you-type asks for 200 candidates, not 2,000 (the box shows at most 8 suggestions); the full search keeps the 2,000 cap. Gatling at V1 in T8 measures it under concurrency. If the full search misses the budget there, the next step is a bitmap AND of the word index with the unit index before ADR-0054's fallbacks.
- **Plan gate:** `SearchPerformanceIT` prepares `search_find_sql()` as the owner (the role the definer function runs as) and fails on a sequential scan of a search table for rare words, for every profile. `memo_app` is shown to have no direct access, and the functions refuse another tenant.
- **Default run:** CI runs `SearchPerformanceIT` at 100,000 memos (about 4 minutes) with 2x budgets. V1 runs with `SEARCH_VOLUME=1000000`, `MEMO_TEST_PG_SETTINGS` and `--rerun`.
