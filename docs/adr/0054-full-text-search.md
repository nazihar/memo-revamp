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
  - `ref_norm` (the reference upper-cased, separators unified) with btree and `pg_trgm` GIN.
- **`search_note`:** one row per note: tenant, memo, note, stored vector of the note's text.
- **`search_chunk`:** one row per (file version, chunk of at most 8,000 words). Columns: tenant, memo, file version, chunk number, the file version's effective classification rank, the text, and the vector computed by the indexer.
- **Indexes and access:**
  - GIN on each vector, with `fastupdate` on and the pending-list limit set from the V1 measurement.
  - RLS is ENABLED on all three tables but not FORCED. Their owner (the schema owner) bypasses it inside the function. The application role has **no SELECT** on them, so its only way in is the function.

**The function**, `search.find(...)`:
- `SECURITY DEFINER`, with `search_path` pinned; EXECUTE is granted only to `memo_app`.
- It takes the tenant from the same `app.tenant_id` setting the policies trust, and checks it against its parameter.
- **Filters:** the reader's clearance rank, the reader id, and the unit scopes of their `memo.read.unit` grants (computed by the application from the policy, exactly as `MemoReadable` does). It applies `classification_rank <= clearance`, then participant EXISTS (active, not lapsed) OR origin unit in scopes. The SQL is the same as the E03 rule, and an equivalence test checks it.
- **Ranking:**
  - takes up to 2,000 candidate memos per branch (metadata, notes, readable chunks);
  - ranks them with `ts_rank_cd` and the weights above, combining metadata + 0.4 × notes + 0.2 × chunks;
  - pages by a keyset cursor;
  - "Newest first" skips ranking.
- **Snippets:** computed with `ts_headline` only for the page's rows, on one readable chunk, note or subject, with private-use delimiters.
- **Timeout:** `SET LOCAL statement_timeout = '2s'`.

**Input:**
- `websearch_to_tsquery` per configured language, ORed together.
- Prefix matching on the last term only, with at least 3 characters.
- Input that looks like a reference also searches `ref_norm`.
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
- A major upgrade (19 changes character classification and the English stemmer) rebuilds every FTS and trigram index and recomputes stored vectors. The runbook has the steps.

### Consequences
- Good: one store, no second copy of confidential text, and the access rule in one place, which an equivalence test keeps equal to E03's.
- Good: GIN works, chunks keep long documents searchable to the end, and references are found.
- Bad: a `SECURITY DEFINER` function is a privileged path. It is reviewed like auth code, it has its own threat-model rows, and its EXECUTE grant is the only way in.
- Bad: the search tables duplicate text already stored (extracted text is derived, ADR-0045). They are encrypted at rest with the database, as the renditions' text is.
- Neutral: relevance is `ts_rank_cd`, not BM25. Good enough for memo search; trigger 4 covers the rest.

### Confirmation
- An `EXPLAIN` gate, run **as `memo_app`**: the function's plans use the GIN indexes for every reader profile, and fail the test if they fall back to a sequential scan at V1-scale statistics.
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

## More information
- Research: `docs/plan/phase-3-memo-efile/epic-05-inbox-search-dashboards/research.md` (R7).
- PostgreSQL, CREATE POLICY and leakproof: https://www.postgresql.org/docs/current/sql-createpolicy.html (accessed 2026-11-15).
- BUG #18941: https://www.postgresql.org/message-id/2692148.1748637435@sss.pgh.pa.us (accessed 2026-11-15).
- Related: ADR-0006 (roles and RLS), ADR-0045 (extracted text), ADR-0046 (attachment classification), ADR-0050 (access rule), ADR-0019 (audit store), ADR-0025 (rate limits).
