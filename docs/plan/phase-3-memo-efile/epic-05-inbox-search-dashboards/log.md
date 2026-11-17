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

## 2026-11-16 — T3: search API and saved searches
- **Built:**
  - **`GET /api/v1/search`:**
    - words, filters (type, status, unit, dates in the tenant's time zone, amount), where to search (`in`), sort and cursor;
    - a count exact up to 2,000 and `capped` above it;
    - facets over the same permitted candidate set;
    - snippets for the page only, as text parts;
    - "found in": details, number, a note and its author, the document, or an attachment by name.
  - **`GET /api/v1/search/suggest`:** the last word as a prefix over 200 candidates, at most 8.
  - **Saved searches:** `GET|POST /api/v1/saved-searches` and `PUT|DELETE /api/v1/saved-searches/{id}` (If-Match). Owner only (others 404), at most 50 per person (409 `SAVED_SEARCH_LIMIT`), names of 1–100 characters, criteria checked as a search's and at most 4 KB; table `search_saved` (`search/V2`, RLS forced).
  - **Limits:** the input caps (200 characters, 12 words, 20 values per filter) are 422; the rate limit `SEARCH_PER_USER` is 30 a minute (429); a 2-second query timeout gives 503 `SEARCH_TIMEOUT`.
  - **Audit:** `search.performed` (mode, terms, filters, sort, count, capped, the page's memo ids), and `search.saved`, `search.updated` and `search.deleted`.
  - **Contract:** `contracts/openapi.json` and the web client are regenerated (the `in` parameter is named so).
- **Decisions taken while building:** see design §3 "Decided in T3".
- **Tests:**
  - **`SearchIT`:**
    - words, a phrase, OR and minus, with snippets, facets and audit, and nothing for a stranger;
    - a note found with its author, and `in` filters;
    - a number found first in three spellings;
    - keyset paging without repeats, and newest first;
    - "2,000+" with facet totals of 2,000 (4,000 generated memos);
    - the input caps, the 31st search refused, and suggestions while typing.
  - **`SavedSearchIT`:** CRUD with audit, a stale version 412, owner only, name and criteria checks, the limit of 50.
  - **`SearchEquivalenceIT`:** 4 seeds × 40 random readers and queries over 120 memos each, with ranks, units, active, revoked and lapsed participants, notes, and chunks above their memo. `search_find` returns exactly what the E03 rule allows and the query matches, and no snippet comes from an unreadable memo or a chunk above clearance.
  - **Other:** `SearchIndexIT` checks an attachment's "found in" through the API; the matrix (`p3-e05-search.yaml`), `EndpointCoverageTest` and `AuditActionCoverageTest` pass.
- **Not tested:** the 503 on a real timeout (a query of 2 s cannot be made reliably in CI). The mapping of PostgreSQL's 57014 is in `SearchFinder`. The Gatling run at V1 (T8) shows how often searches near the limit.

## 2026-11-16 — T2 in CI: stalls
- **What happened:** the T2 backend job ran into the 30-minute CI limit twice, while T1 and T3 finished in about 12 minutes. JUnit runs classes and methods in random order (`junit-platform.properties`), so the stall depends on order.
- **Changes:**
  - The search backfill sweep is off in integration tests (`memo.search.backfill-sweep`).
  - The spike's `fastupdate` measurement, an `ALTER INDEX` on a table every memo test now writes, is gone.
  - The search tests' owner connections set `lock_timeout` to 30 s, so a lock wait fails with its statement instead of stalling the suite.
- **Carried:** a default JUnit timeout with a thread dump would name any stall, but it needs the separate-thread mode, which the thread-bound test context rules out without care.

## 2026-11-16 — T4: following, due sort, bulk
- **Built:**
  - **`memo/V18`:** `memo_watch`, `memo_watch_event` and `memo_watch_setting`, all with RLS forced.
  - **Following by hand:** `GET|PUT|DELETE /api/v1/memos/{id}/watch` (readers only, audited `memo.watch.added` and `memo.watch.removed`).
  - **Watching list and settings:** `GET /api/v1/inbox/watching` (filtered by the read rule), and `GET|PUT /api/v1/me/watch-settings` (audited `memo.watch.settings_changed`).
  - **Automatic following:** the initiator on create, those who decide a step, and those mentioned in a note, unless they turned it off.
  - **News:** a step decided, a note posted, the memo closed, the final record ready; never to the person who acted.
  - **Jobs:**
    - a flush every minute: one in-app `MEMO_WATCHED` per memo after 3 quiet minutes, and `WATCH_UPDATE` for those who chose "at once";
    - the digest at 08:00 in the tenant's time zone (`WATCH_DIGEST`);
    - the nightly access sweep, run as each follower.
  - **Due sort:** `GET /api/v1/inbox/tasks?sort=due`: overdue first, then soonest, then no due time, with keyset paging.
  - **Bulk:** `POST /api/v1/inbox/bulk` with results per item (`OK`, `FORBIDDEN`, `CHANGED`, `NOT_FOUND`), each item in its own transaction, at most 100, no decisions (422), reassignment with the permission and step-up, and one audit entry `inbox.bulk.performed`.
  - **Web:** strings for the new notification kind, category and templates (English and Arabic); contract and client regenerated.
- **Decisions taken while building:** see design §4 "Decided in T4".
- **Tests:**
  - **`MemoWatchIT`:**
    - by hand, readers only;
    - automatic following unless off;
    - news to others only, in-app after the quiet spell, at once by email or in the digest, the digest once;
    - a person mentioned follows; a watch is hidden and swept after access is lost;
    - due sort with paging.
  - **`InboxBulkIT`:** watch and unwatch per item; claims reported one by one; reassignment needs the permission, and a decided task reports `CHANGED`; no decisions, at most 100, a holder for reassignment.
  - **Matrix and coverage:** `p3-e05-inbox.yaml`, `EndpointCoverageTest`, `AuditActionCoverageTest`, `MailTemplatesTest` and `RlsMetaTest`.
- **Carried:** a bulk claim of steps shared by several people (queue or position steps) is covered by `QueueClaimIT` at the workflow level; a bulk test with a shared memo step comes with T5's team data.
