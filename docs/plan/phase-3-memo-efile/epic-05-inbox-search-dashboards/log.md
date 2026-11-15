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

## 2026-11-15 — T2: search index
- **Built:**
  - **Memo module:** `MemoChangeLog` collects the memos, posted notes and files changed in a transaction and publishes `MemoChanged`, `MemoNotePosted` and `MemoFileChanged` once each, just before commit. It is hooked into memo create, edit, pin, status, workflow, closing and numbering; note posting; and attachment reclassification and removal. `MemoSearchSource` gives the facts, posted notes and searchable files.
  - **Files module:** `RenditionDone`, published in the transaction that records a DONE rendition.
  - **Search module:**
    - `SearchIndexing` writes memo and note rows in the change's transaction and follows a file's level or removal.
    - It schedules the `search-chunks` job on a TEXT rendition. The job indexes only the file's current version, splits the text with `SearchChunker`, and splits again when PostgreSQL finds a chunk too long.
    - The `search-backfill` job runs per tenant, swept hourly, in batches of 200 until done.
  - **Migration:** `search_chunk.file_id` (a file's new version replaces its chunks), `search_set_chunk_rank` by file, and `search_unindexed` (a seventh definer function, in `RlsMetaTest`).
  - **Semgrep rule** `memo-security-definer-only-in-search`: SECURITY DEFINER outside `db/migration/search/` fails the scan. The fixture is `.semgrep/rules.sql`, checked by a scan of a copy, because `semgrep --test` finds no tests in `.semgrep/` (also for the existing rules: carried).
- **Found:**
  - **256 positions per word:** a vector keeps at most 256 positions per word, so in 8,000-word chunks a phrase with a frequent word stops matching. Chunks are now at most 2,000 words (ADR-0054 amendment).
  - **File names:** the parser reads `otter-notes.docx` as one file-name token, so words inside hyphenated file names are not found on their own (as in PostgreSQL generally).
- **Tests:**
  - `SearchIndexIT` (details and notes with the change, edits replace words, chunking of about 1 MB with the last line found, a new document version replacing the old text, a raised attachment hidden below its level and gone when removed, the backfill once);
  - `SearchChunkerTest`;
  - `RlsMetaTest`, `EventPayloadRulesTest`, `ModularityTest`, `ArchitectureRulesTest` and the memo attachment, note and lifecycle ITs.
- **Fixed on the way:** the E04 journey failed in CI on #120–#122 because the Earlier versions list showed each version's own reason. It now shows the reason of the version that replaced it (E04 T7, merged up the stack). The T1 spike's Semgrep findings (session-level `SET`, MD5 in Kotlin) are fixed on T1.
