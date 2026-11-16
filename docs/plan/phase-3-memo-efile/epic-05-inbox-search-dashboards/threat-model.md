# E05 Inbox, search and dashboards — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows, with mitigations mapped to OWASP ASVS 5.0 requirement areas. Builds on E03 (the access rule) and E02 (extracted text).

## Data flows
1. **Search:** reader → `POST /search` (words in the body, T6) → rate limit → policy (clearance, unit scopes) → `search.find` (SECURITY DEFINER) → search tables → results, facets, snippets → audit.
2. **Indexing:** memo and note events → search rows in the same transaction; TEXT rendition done → chunk job → `search_chunk`.
3. **Inbox:** watch, bulk, team view → workflow tasks → notifications (immediate or digest).
4. **Dashboards:** reader → aggregates over memos, tasks and actions within scope.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | Calling `search.find` with someone else's identity, unit scopes or tenant | 1 | EXECUTE granted only to `memo_app`. The application passes the authenticated reader, their policy-computed scopes and clearance. The function checks the tenant argument against `app.tenant_id` (the same setting RLS trusts). No API exposes the function's parameters | V8.2, V8.4 |
| T1 | Search rows out of step with their source (a revoked share, a lapsed share, a reclassified attachment) | 2 | Access is never indexed: the function checks participants, lapse and scopes at query time against live tables. Chunk rank follows reclassification (job). A property test compares with the E03 rule | V8.3 |
| I1 | A result, count or facet reveals an unreadable memo | 1 | Every branch filtered by clearance and the access rule before counting. Facets counted on the permitted set. Exact counts only up to the cap. Equivalence and property tests | V8.2, V8.3 |
| I2 | A snippet reveals text above the reader's clearance (an attachment classified above the memo) | 1 | Chunks carry their file version's rank. Snippets only from chunks within clearance, else from the subject or a note. Never cached or logged | V8.2, V14.1 |
| I3 | Ranking or "found in" leaks hidden content | 1 | `ts_rank_cd` uses no corpus statistics. "Found in" is computed on permitted rows only. BM25 engines rejected (ADR-0054) | V8.2 |
| I4 | Search terms (names, account numbers) leak through logs | 1 | Terms only in the audit store (as protected as the data). Application logs carry the term count, never the terms. Any export uses a keyed HMAC | V16.3, V14.2 |
| I5 | The team view or dashboard reveals memos a head may not read | 3, 4 | Restricted memos show as "Restricted memo" (no subject or number). Aggregates are counts over the viewer's scope. Per-person figures only within scope | V8.2 |
| E1 | The SECURITY DEFINER function is abused, or `search_path` is hijacked | 1 | `search_path` pinned in the function. No dynamic SQL from input. Owner is the schema owner. Reviewed like auth code. Semgrep rule: no other SECURITY DEFINER function without an ADR | V15.3, V5.3 |
| E2 | A bulk action does more than its items allow (a decision, or reassigning outside scope) | 3 | Only non-decisions accepted. Each item checked as if acted on alone. At most 100 items. Results per item | V8.2, V2.3 |
| D1 | Expensive queries (short prefixes, huge OR lists, deep paging) exhaust the database | 1 | Prefix at least 3 characters and on the last term only. At most 200 characters and 12 terms. Candidate caps. `statement_timeout` 2 s. Per-user rate limit 30 a minute | V15.2, V2.4 |
| D2 | Enumeration through many searches | 1 | Rate limit; audit of every search; alert on many distinct queries or deep paging by one person (runbook) | V16.2, V2.4 |
| D3 | Chunk indexing of huge texts blocks the job pool | 2 | Chunks of at most 2,000 words (and 200,000 characters), at most 500 per file; "too long" handled by splitting; one job per file version; the existing text cap of 5,000,000 characters (ADR-0045) | V15.2 |
| R1 | Nobody can say who searched for what | 1 | `search.performed` with the terms, filters and page ids, in the hash-chained store | V16.2 |

## Residual risks
- **The definer function is a single privileged path.** Its correctness rests on the equivalence test and review. A change to the E03 rule must change the function, so the test fails until they agree.
- **Search tables hold derived confidential text** outside FORCE RLS. Only the function reads them, and the database's encryption at rest applies. The pen test (E07) covers them.
- **Watch emails** carry the memo's number and a link, never its subject; the daily digest carries only a count (decided in T4, stricter than planned).
