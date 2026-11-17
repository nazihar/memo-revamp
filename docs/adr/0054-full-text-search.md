---
status: accepted
date: 2026-11-15
deciders: Product owner (delegated gate, Phase 3 Q1; search screen flows reviewed separately), agent
---

# 0054 — Full-text search: PostgreSQL search tables behind one audited function, OpenSearch only on measured triggers

## Context and problem statement
E05 adds full-text search over a memo's metadata, its note sheet and the extracted text of its document and attachments (ADR-0045). The target is one large tenant with 1,000,000 memos, at p95 under 300 ms. Search must never reveal a memo, a count, a snippet or a facet that the reader cannot open (overview §6). An attachment can be classified above its memo (ADR-0046).

Every table has row-level security, enabled and forced (ADR-0006). The E03 access rule (ADR-0050) decides who reads a memo.

## Decision drivers
- No leak through results, counts, facets, snippets or ranking.
- p95 under 300 ms at V1 volume, with the reader profiles that stress the access rule.
- No second store of confidential text unless the numbers force it.
- English now, with a clear way to add Arabic or Bengali.
- Licences on our allowed list (AGENTS §3).

## Considered options
1. **Generated `tsvector` columns with GIN indexes on the existing tables, under RLS.**
2. **Search tables of their own, reached only through a `SECURITY DEFINER` function** that applies tenant, clearance and the access rule explicitly.
3. **Mark `ts_match_vq` (`@@`) LEAKPROOF** cluster-wide, and keep option 1.
4. **A BM25 extension** (ParadeDB `pg_search`, Timescale `pg_textsearch`).
5. **OpenSearch** now.

## Decision outcome
**Option 2.** Options 3 and 5 are the recorded fallbacks: option 3 if option 2 cannot meet the budget, option 5 when a trigger fires.

**Why not option 1.** PostgreSQL runs RLS quals before non-leakproof operators, and `@@` is not leakproof. Under RLS the GIN index is therefore not used: our spike measured a sequential scan, 28 ms on 200,000 rows against 0.3 ms through the function (research R7).

**Tables** (search module, migration `search/V1`):
- **`search_memo`:** one row per memo. Columns:
  - tenant, memo, classification rank, origin unit, status, type, dates, amount, initiator;
  - `lang text`;
  - a stored `tsvector` (subject A, reference tokens B, type and field values C);
  - `ref_norm` (the reference upper-cased, each run of separators one `-`) and `ref_tail` (its last digit group without leading zeros), each with a B-tree index. *(Amended by E05 T1: no `pg_trgm`.)*
- **`search_note`:** one row per note: tenant, memo, note, stored vector of the note's text.
- **`search_chunk`:** one row per (file version, chunk of at most 2,000 words; amended by E05 T2 from 8,000). Columns: tenant, memo, file version, chunk number, the file version's effective classification rank, the text, and the vector computed by the indexer.
- **Indexes and access:**
  - GIN on each vector, with `fastupdate` on and the pending-list limit set from the V1 measurement.
  - RLS is ENABLED on all three tables but not FORCED. Their owner (the schema owner) bypasses it inside the function. The application role has **no SELECT** on them, so its only way in is the function.

**The functions**, `search_find(...)` and `search_snippets(...)`, with writers `search_put_memo`, `search_put_note`, `search_put_chunks` and `search_set_chunk_rank`:
- `SECURITY DEFINER`, with `search_path` pinned; EXECUTE is granted only to `memo_app`. Writes go through the writer functions too: the application role holds no privilege on the tables.
- It takes the tenant from the same `app.tenant_id` setting the policies trust, and checks it against its parameter.
- **Filters:** the reader's clearance rank, the reader id, and the unit scopes of their `memo.read.unit` grants (computed by the application from the policy, exactly as `MemoReadable` does). It applies `classification_rank <= clearance`, then participant EXISTS (active, not lapsed) OR origin unit in scopes. The SQL is the same as the E03 rule, and an equivalence test checks it.
- **Ranking:**
  - takes up to 2,000 candidate memos per branch (metadata, notes, readable chunks);
  - ranks them with `ts_rank_cd` and the weights above, combining metadata + 0.4 × notes + 0.2 × chunks;
  - pages by a keyset cursor;
  - "Newest first" skips ranking.
- **Snippets:** computed with `ts_headline` only for the page's rows, on one readable chunk, note or subject, with private-use delimiters.
- **Timeout:** 2 s, set by the caller as the query timeout. *(Amended by E05 T1: a `STABLE` function cannot run `SET`.)*

**Input:**
- `websearch_to_tsquery` per configured language, ORed together.
- Prefix matching on the last term only, with at least 3 characters.
- Input that looks like a reference also looks up `ref_norm`, and input of digits alone also `ref_tail` (so `00042` finds `M-2026-00042`). An exact number match ranks first.
- At most 200 characters and 12 terms.

**Indexing:**
- Metadata and notes are written in the same transaction as the change, through listeners in the search module on memo and note events.
- Chunks are written by a job when a TEXT rendition is done (ADR-0045). Classification changes rewrite the chunk rows' rank.
- A backfill job fills the tables for existing data.
- The language is a `text` column mapped with `CASE` (never a `regconfig` column: `pg_upgrade` refuses it). English only now. Arabic would use the built-in configuration; Bengali `simple` or a custom one, after a tokenising test in the production locale.

**Audit and limits:**
- Every search is audited (`search.performed`): terms, filters, sort, the count or "capped", and the ids of the memos on the returned page. Terms stay inside the audit store.
- Per-user rate limit of 30 a minute with a burst of 10 (Bucket4j, ADR-0025).

**Not BM25.** Engines with corpus statistics count rows hidden by RLS, which leaks. `pg_textsearch` documents this. `pg_search` is AGPL.

**OpenSearch triggers** (any one, after tuning):
1. p95 over 300 ms at V1, or over 500 ms at twice V1, at the agreed concurrency.
2. More than about 30 million chunks, or about 200 GB of search tables, in one tenant.
3. The search test lifts OLTP p95, with CPU or IO above about 60%.
4. The product needs BM25 tuning, fuzzy matching and synonyms, Bengali stemming beyond `simple`, or facets over large result sets.

**Upgrades.**
- PostgreSQL 18.7 or later, which fixes GIN corruption, from 2026-11-26, when it is two weeks old.
- A major upgrade (19 changes character classification and the English stemmer) rebuilds every FTS index and recomputes stored vectors. The runbook has the steps.

### Consequences
- Good: one store, no second copy of confidential text, and the access rule in one place, which an equivalence test keeps equal to E03's.
- Good: GIN works, chunks keep long documents searchable to the end, and references are found.
- Bad: a `SECURITY DEFINER` function is a privileged path. It is reviewed like auth code, it has its own threat-model rows, and its EXECUTE grant is the only way in.
- Bad: the search tables duplicate text already stored (extracted text is derived, ADR-0045). They are encrypted at rest with the database, as the renditions' text is.
- Neutral: relevance is `ts_rank_cd`, not BM25. Good enough for memo search; trigger 4 covers the rest.

### Confirmation
- An `EXPLAIN` gate: the plans of the function's query, as the owner the definer function runs as, use the indexes for every reader profile (custom and generic plans) and fail the test on a sequential scan of a search table. `memo_app` is checked to have no direct access. *(Amended by E05 T1: plans inside a function are not visible to `EXPLAIN` of the call.)*
- A property test: on random memos, participants, shares, unit scopes, classifications and chunks, the function returns exactly the memos the E03 rule allows and that match the terms. No count, facet or snippet comes from a hidden row or chunk.
- Gatling at V1 (1,000,000 memos): search p95 under 300 ms for the four reader profiles.
- The audit test (every search audited) and the rate-limit test.

## Pros and cons of the options
### Option 1 — vectors under RLS
- Good: simplest.
- Bad: no index use under RLS; sequential scans at a million rows.
### Option 2 — search tables and a definer function
- Good: index use; the access rule explicit and tested.
- Bad: a privileged function to keep correct.
### Option 3 — LEAKPROOF `@@`
- Good: keeps plain RLS.
- Bad: a superuser change to the catalogue, cluster-wide, against upstream advice; its survival across `pg_upgrade` is unconfirmed.
### Option 4 — BM25 extensions
- Good: better ranking.
- Bad: statistics that count hidden rows (a leak); AGPL (`pg_search`).
### Option 5 — OpenSearch now
- Good: analysers for Bengali and Arabic, BM25, facets.
- Bad: a second confidential store, a 3-node cluster, access lists reindexed on every grant, and stale results.

## Amendment — 2026-11-15 (E05 T1)
- **No `pg_trgm`.** The migration role (`memo_migrator`) has no CREATE on the database, so a migration cannot create an extension (`deploy/db/init/01-roles.sh`), and an extension would add an install step for every customer. Numbers are found by B-tree lookups instead: `ref_norm` for a whole number in any spelling (`M-2026-00042`, `m/2026/00042`), `ref_tail` for its last digits (`00042`, `42`). Partial numbers inside a word (`2026-000`) are not supported; the words of a number still match through the vector.
- **Two parts per source.** Each source (details, notes, documents) is searched once through the reader's active participant rows (one lookup per memo) and once through their unit scope, the second skipped when they have none. With an OR of the two, a reader with few memos scanned every match of a common word. Each part takes at most the cap before ranking.
- **One table per part.** Note and chunk rows carry copies of their memo's rank, origin unit, status, type, creation date and amount, kept equal by `search_put_memo`. A part that had to join the memo row could not stop at the cap, so a common word read every chunk. A chunk keeps its file's own rank and the effective one (the higher of file and memo).
- **Planned per call.** `search_find` runs its query with `EXECUTE … USING`, so it is planned with each call's values. A cached generic plan cannot tell a reader of 300 memos from a reader of the tenant, or a word in most memos from a rare one; in the spike it took a narrow reader's common-word search from 13 ms to 1.3 s. The query text is one function (`search_find_sql`), which the `EXPLAIN` gate prepares.
- **Chunks of 2,000 words (E05 T2).** A vector keeps at most 256 positions per word. In an 8,000-word chunk a word used more than 256 times loses its later positions, and a phrase with it stops matching (found when "line 18000" was not found in a long text). At 2,000 words a word must fill an eighth of the text first. A file has one searchable version: a new version's chunks replace the file's (`search_chunk.file_id`).
- **Indexing in the change's transaction (E05 T2).** The memo module collects the memos, posted notes and files it changed in a transaction and publishes one event per item just before commit (`MemoChanged`, `MemoNotePosted`, `MemoFileChanged`). The search module writes their rows in that transaction. A text rendition publishes `RenditionDone`, and its chunks are written by the `search-chunks` job. `search_unindexed` lists the memos the backfill still has to index.
- **Spike numbers** (E05 log, T1): at 1,000,000 memos with the database sized for a 16 GB VM, the p95 of one search is 9–14 ms for a narrow reader, 4–104 ms tenant-wide, 18–222 ms for a reader of 20,000 memos, and 48–248 ms for a unit head, except search-as-you-type at 307 ms. Search-as-you-type therefore takes 200 candidates, and the full search keeps 2,000. `fastupdate` stays on with a 2 MB pending list.

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-05-inbox-search-dashboards/research.md` (R7).
- PostgreSQL, CREATE POLICY and leakproof: https://www.postgresql.org/docs/current/sql-createpolicy.html (accessed 2026-11-15).
- BUG #18941: https://www.postgresql.org/message-id/2692148.1748637435@sss.pgh.pa.us (accessed 2026-11-15).
- Related: ADR-0006 (roles and RLS), ADR-0045 (extracted text), ADR-0046 (attachment classification), ADR-0050 (access rule), ADR-0019 (audit store), ADR-0025 (rate limits).
