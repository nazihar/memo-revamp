# E05 Inbox, search and dashboards — research

> Step 1 of `AGENTS.md §5`. Overview research items **R7** (permission-filtered full-text search at 1,000,000 memos) and **R10** (team views, dashboards, watching, saved searches and bulk actions in e-office and approval products), and R1 for everything E05 adds. Gathered 2026-11-15 by two research agents and checked by the lead. All sources accessed **2026-11-15**. "Unconfirmed" marks anything not verified against a primary source. The search spikes ran in a throwaway `postgres:18.6` container with 200,000 synthetic rows; their numbers are directional, and the V1 run decides.

## Lead's checks and conclusions
- **The finding that shapes the design: row-level security blocks the full-text index.**
  - PostgreSQL evaluates RLS policy quals before user quals unless the user's operators are `LEAKPROOF`. The match operator `@@` (`ts_match_vq`) is not, and neither are `pg_trgm`'s operators.
  - So on our tables (RLS enabled and forced), a GIN index is not used for `tsv @@ query`, and the planner scans the whole table.
  - The spike reproduced it on 18.6 with our exact policy shape: sequential scan, 28 ms on 200,000 rows. The same query through a `SECURITY DEFINER` function with an explicit tenant predicate used the GIN index: 0.3 ms.
  - Tom Lane confirmed the behaviour on BUG #18941 ("TS search operators don't qualify", 2025).
  - **Decision (ADR-0054):** search tables of their own, without `FORCE`, reached only through one audited `SECURITY DEFINER` function. It applies the tenant, the clearance and the E03 access rule explicitly. Every other path keeps RLS.
  - The alternative, marking `ts_match_vq` LEAKPROOF, is a cluster-wide catalogue change against upstream advice; it is recorded as rejected.
- **Identifiers are not words.** The default parser splits `M-2026-00042` into `m`, `-2026`, `-00042`, so searching `00042` finds nothing. It reads `CR/2026/0001` as one "file" token. References get a normalised column with btree and `pg_trgm`.
- **Long documents must be chunked.** A tsvector keeps at most 256 positions per lexeme and clamps positions at 16,383, and it must stay under 1 MB. In the spike, a phrase after word 16,383 of a 1.1 MB text did not match the whole-text vector, but did match a chunk. Extracted text is stored in chunks of at most about 8,000 words, each carrying its file version's classification.
- **Snippets are the leak risk.** `ts_headline` re-parses the original text: about 110 ms on 1.1 MB, about 5 ms on 2 KB. It runs only on the ≤20 rows of a page, only on a chunk the reader is cleared for, with private-use markers escaped into `<mark>` as text. Snippets are never stored or logged.
- **Versions:**
  - PostgreSQL **18.7** (2026-11-12) fixes GIN corruption that could make queries miss rows or return unrelated ones. It is younger than two weeks, so dev and CI move to it on or after 2026-11-26, before the V1 measurement that counts.
  - PostgreSQL 19 (GA planned 2026-11-19) changes character classification and the English stemmer. After that upgrade, every FTS and trigram index must be rebuilt and every stored vector recomputed (recorded in ADR-0054).
- **Bulk approvals stay out.** No regulator text forbids them outright. But the banking products that batch authorisation do so only for uniform, limit-checked transactions approved by someone other than the poster (Oracle FLEXCUBE), and our per-decision step-up (Phase 2) rules it out anyway. Bulk actions are the non-decisions: watch or unwatch, claim, release, and reassign for those allowed to. Each is checked per item, with results per item (Laserfiche, Jira).
- **Charts: Recharts 3.10.1.**
  - SVG through React; nothing injects `<style>`, `cssText` or `innerHTML`, so it suits our `style-src 'self'` CSP;
  - React 19; keyboard layer on by default.
  - Every chart has a real data table beside it, a colour-blind-safe palette, and never colour alone.
  - ECharts is rejected (its tooltip writes `style=` through `innerHTML`), as is Observable Plot (it injects `<style>`).
  - The first web task checks it under the E2E CSP-violation fixture.
- **Watching:** two tiers.
  - Anything that needs the person (assigned, returned, mentioned) is sent at once and cannot be turned off.
  - Watched memos notify in the app at once, and by email as a daily digest by default (M-Files, GitHub, Jira batching).

---

## R1: Components and versions

| Component | Version (date) | Licence | Use in E05 |
|---|---|---|---|
| PostgreSQL | 18.6 now; **18.7 (2026-11-12, under two weeks)** from 2026-11-26; 19 GA 2026-11-19 (not adopted in Phase 3) | PostgreSQL | FTS, GIN, `pg_trgm` (contrib) |
| Gatling | 3.16.0 (2026-09-29), already pinned | Apache-2.0 | Search and inbox load tests at V1 |
| Recharts | 3.10.1 (2026-07-25); needs `react-is` 19 | MIT | Dashboard charts |
| OpenSearch | 3.9.0 (2026-09-29) | Apache-2.0 | Not adopted; threshold recorded |
| ParadeDB pg_search | 0.26.6 (2026-11-08, under two weeks) | AGPL-3.0 | Rejected (licence; BM25 statistics) |
| pg_textsearch (Timescale) | 1.5.1 (2026-10-02) | PostgreSQL | Rejected: its BM25 statistics include rows hidden by RLS, a leak channel |
| PGroonga | 4.1.0 (2026-10-09) | PostgreSQL | Not adopted; possible path for languages |
| ZomboDB | archived 2025-09-01 | — | Excluded |
| k6 | v2.4.0 / v1.8.3 (under two weeks) | AGPL-3.0 | Rejected already (dependency register) |

Sources:
- PostgreSQL: https://www.postgresql.org/support/versioning/, https://www.postgresql.org/message-id/attachment/208544/20261112updaterelease.md, https://www.postgresql.org/docs/19/release-19.html
- OpenSearch: https://opensearch.org/releases/
- ParadeDB: https://github.com/paradedb/paradedb
- pg_textsearch: https://github.com/timescale/pg_textsearch
- PGroonga: https://pgroonga.github.io/news/
- ZomboDB: https://github.com/zombodb/zombodb
- k6: https://github.com/grafana/k6/releases
- Recharts: npm registry, and https://newreleases.io/project/npm/recharts/release/3.0.0

## R7: Permission-filtered full-text search

**Index design**
- **Stored vectors in separate tables:**
  - A stored `tsvector` beats an expression index, because rechecks don't recompute it. PostgreSQL 18 makes generated columns virtual by default, so we write `STORED`. https://www.postgresql.org/docs/current/textsearch-tables.html, https://www.postgresql.org/docs/18/ddl-generated-columns.html
  - We use three tables: memo metadata, notes, and document chunks.
- **GIN, not GiST** (GiST is lossy). https://www.postgresql.org/docs/current/textsearch-indexes.html
- **`fastupdate`:** on by default, and a large pending list slows searches. Start with a per-index `gin_pending_list_limit` of 1–2 MB and aggressive autovacuum on the note and chunk tables, compared with `fastupdate=off` in V1. For bulk loads, drop the index and rebuild it with ample `maintenance_work_mem`. https://www.postgresql.org/docs/current/gin.html, https://www.postgresql.org/docs/current/sql-createindex.html
- **Weights** (D 0.1, C 0.2, B 0.4, A 1.0): subject A, reference tokens B, type and field values C. Scores combine across tables in SQL: metadata + 0.4 × note + 0.2 × document, tuned on the demo corpus. https://www.postgresql.org/docs/current/textsearch-controls.html
- **Input:** `websearch_to_tsquery` never raises errors, so raw input is safe; it supports `"phrases"`, `OR` and `-`. Prefix matching applies to the last term only, with at least 3 characters.
- **Ranking cost:** ranking reads every match's vector. A public PostgreSQL 18 vs Elasticsearch 8.11 run at 1M + 1M rows blamed ranking for most of the gap (averages on a laptop): https://github.com/inevolin/Postgres-FTS-TOASTed-vs-ElasticSearch
  - We rank only capped candidates: about 2,000 per branch, then the top 20.
  - "Newest first" needs no ranking.
  - When the cap is hit, the page says "best matches among the first N".
- **Limits:** lexemes under 2 KB, at most 256 positions per lexeme, positions up to 16,383, vectors under 1 MB. Hence chunks of at most about 8,000 words. "string is too long for tsvector" is caught and the chunk split further, never truncated silently. https://www.postgresql.org/docs/current/textsearch-limitations.html
- **References:** `pg_trgm` covers `LIKE`/`ILIKE`, and a pattern with no trigrams degrades to a full index scan. https://www.postgresql.org/docs/current/pgtrgm.html

**RLS and the access rule**
- Policy quals run before non-leakproof user quals: https://www.postgresql.org/docs/current/sql-createpolicy.html
- Tom Lane on BUG #18941: https://www.postgresql.org/message-id/2692148.1748637435@sss.pgh.pa.us
- An earlier report (2019): https://postgresql.org/message-id/CAGrP7a2kQ5vVUE%3D46xPU1LLJJ2GuYSREWsTY49WbfPV6R0sM3g%40mail.gmail.com

| Spike (200,000 rows, 18.6) | Plan | Time |
|---|---|---|
| RLS as is | Parallel seq scan, `@@` as a filter | 28 ms |
| `ts_match_vq` marked LEAKPROOF | GIN bitmap scan | 0.1 ms |
| `SECURITY DEFINER` function, explicit tenant predicate | GIN bitmap scan | 0.3 ms |

- The E03 access predicate is an ordinary qual, so the leakproof rule doesn't touch it. **`EXPLAIN` gates must run as the application role:** a superuser hides the problem.
- **Two reader profiles plan differently:**
  - narrow readers (participants on a few thousand memos) are cheapest filtered first by readable memo ids;
  - unit readers and auditors are cheapest index-first.
  - V1 measures both.
- **Classification:** a chunk matches only when the reader is cleared for its rank. Totals and facets are counted after every filter. `ts_rank` uses no corpus statistics, so hidden rows can't move scores; BM25 engines can, which is why pg_textsearch is rejected.

**Snippets**
- `ts_headline` uses the original text and is not HTML-safe. Rules:
  - the page's rows only;
  - one readable matching chunk, falling back to the subject or a note;
  - `MaxFragments=2, MaxWords≈20`;
  - private-use delimiters escaped and turned into `<mark>` as React text;
  - never cached or logged.

**Languages**
- PostgreSQL 18's Snowball stemmers include Arabic, Hindi, Nepali and Tamil, but **not Bengali** (`src/backend/snowball/snowball_create.pl`).
- Bengali would use `simple` or a custom configuration. In the spike, `ts_debug('simple')` kept Bengali words whole; this depends on the locale, so it is re-tested in the production locale (unconfirmed). Dictionaries: https://www.postgresql.org/docs/current/textsearch-dictionaries.html
- **Never a `regconfig` column:** `pg_upgrade` refuses it. https://www.postgresql.org/docs/current/pgupgrade.html
  - The language is a `text` column mapped with `CASE` in the stored expression (tested).
  - The chunk indexer computes vectors with a bound configuration.
  - Queries OR together the per-language `tsquery`s of the languages a tenant has.

**Alternatives and the OpenSearch threshold**
- OpenSearch has Bengali and Arabic analysers (https://docs.opensearch.org/latest/analyzers/language-analyzers/index/) and document-level security (https://docs.opensearch.org/latest/security/access-control/document-level-security/). Its costs:
  - a second confidential store of extracted text (encryption, backup, pen-test scope);
  - a 3-node cluster;
  - access lists copied into every document and reindexed on every share or grant;
  - stale results.
- **Triggers, any one after tuning (ours, recorded in ADR-0054):**
  1. Search p95 over 300 ms at V1 volume, or over 500 ms at twice V1, at the agreed concurrency.
  2. More than about 30 million chunks or about 200 GB of search tables per tenant.
  3. Search load lifts OLTP p95, with CPU or IO above about 60% during the search test.
  4. The product needs BM25 tuning, fuzzy matching and synonyms, Bengali stemming beyond `simple`, or facet counts over large result sets.

**Audit and limits**
- **Audit:** every search is audited in the tamper-evident store: actor, acting-for, normalised terms, filters, sort, the count (or "capped"), and the memo ids on the returned page.
  - Terms stay inside the audit store. Anything leaving it carries a keyed HMAC, never a plain hash, because search terms are low-entropy. https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- **Limits:**
  - per-user rate limit (30 a minute, burst 10);
  - `SET LOCAL statement_timeout` of about 2 s;
  - caps on query length and term count;
  - alerts on enumeration patterns.
  - https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/

**Load testing**
- Gatling 3.16.0 in `perf/`, asserting p95 < 300 ms (https://docs.gatling.io/concepts/assertions/), plus `pg_stat_statements` and `auto_explain` run as the application role.
- **The V1 generator:**
  - a skewed vocabulary (`words[1+floor(power(random(),3)*n)]`) from a 5,000–20,000-word corpus;
  - needle terms at frequencies of 1e-5, 1e-3 and 1e-1;
  - file sizes with a long tail up to 1 MB;
  - realistic note counts;
  - four reader profiles: a narrow participant, a unit head, a unit reader or auditor, and a low clearance;
  - loaded without GIN indexes, then rebuilt and `ANALYZE`d.

## R10: Industry practice

- **Watching:**
  - SharePoint "Follow" lists followed documents and their activity: https://support.microsoft.com/en-us/sharepoint/libraries/follow-documents-to-track-them
  - SharePoint's immediate/daily/weekly Alerts are being retired in 2026: https://support.microsoft.com/en-au/sharepoint/admin/sharepoint-alerts-retirement
  - M-Files follow rules offer a digest; the digest wording is unconfirmed: https://userguide.m-files.com/user-guide/latest/eng/editing_notification_settings.html
  - Jira bulk Watch, and auto-watch of one's own work: https://confluence.atlassian.com/jirasoftwarecloud/editing-multiple-issues-at-the-same-time-1442910568.html
  - Jira batches updates per issue (3 minutes idle, at most 10): https://support.atlassian.com/jira/kb/batching-notifications-in-jira-cloud/
  - GitHub separates participating from watching and shows a reason: https://docs.github.com/en/subscriptions-and-notifications/get-started/configuring-notifications
- **Team view:**
  - Laserfiche Team Tasks (Assigned, Unassigned, Completed). Managers reassign within the team, and each item of a multi-select reassign is checked, with refusals listed: https://doc.laserfiche.com/laserfiche.documentation/12/userguide/en-us/content/task-forms-inbox.htm, https://doc.laserfiche.com/laserfiche/en-us/content/pa-bp-tasks-in-the-inbox.htm
  - NIC eOffice alerts on files "lying with self and subordinates", with Pendency and time-bound MIS reports (training material, unconfirmed at page level): https://mcrhrdi.gov.in/army/week12/eoffice-Presentation_mchrd.pdf
  - e-Nothi: no primary source found (unconfirmed).
- **Bulk approval:**
  - Power Automate approvals answer one request at a time: https://learn.microsoft.com/en-us/power-automate/approvals-howto
  - DocuWare 7.9 lets one decision cover several tasks with the same decisions: https://support.docuware.com/knowledgebase/article/KBA-37347
  - Oracle FLEXCUBE summary authorisation is by another user, limit-checked: https://docs.oracle.com/cd/E64763_01/html/DE/DE08_Operations.htm
  - BCBS operational-risk principles require segregation of duties: https://www.bis.org/fsi/fsisummaries/psmor.htm
- **Saved searches:**
  - Jira filters and subscriptions run per recipient: https://support.atlassian.com/jira-software-cloud/docs/manage-filters/
  - ServiceNow saves filters for oneself, a group or everyone: https://www.servicenow.com/docs/r/platform-user-interface/t_SavingFilters.html
- **Search UX:**
  - MoJ "filter a list" (a panel beside the results, removable tags, Clear filters, an explicit Apply): https://design-patterns.service.justice.gov.uk/patterns/filter-a-list
  - Scottish Government search results (a count with `aria-live`, `<mark>`, sort above the results): https://designsystem.gov.scot/patterns/search-results
  - WCAG 2.2 SC 4.1.3 (status messages): https://www.w3.org/TR/WCAG22/#status-messages
  - The APG combobox for suggestions: https://www.w3.org/WAI/ARIA/apg/patterns/combobox/
- **Dashboards:**
  - Jira Average Age, Resolution Time, Created vs Resolved: https://confluence.atlassian.com/display/JIRAKB/Reporting+in+JIRA
  - Accessible complex images (a figure, a summary and a data table): https://www.w3.org/WAI/tutorials/images/complex/
  - UK Analysis Function guidance on chart colours: https://analysisfunction.civilservice.gov.uk/policy-store/data-visualisation-colours-in-charts
- **Charts under our CSP:** the style attribute is blocked through markup and `innerHTML`, while CSSOM property writes are allowed: https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/style-src
  - Whether `cssText` is blocked is unconfirmed; our E2E fixture settles it.
  - Chart.js is canvas and has no keyboard support: https://www.chartjs.org/docs/latest/general/accessibility.html

**Adopt:**
- the filter panel with removable tags and filters kept in the URL;
- a count announced politely;
- saved searches that keep criteria, never results;
- a team view of "self and subordinates" with due-time order and per-item bulk results;
- two-tier notifications showing the reason and offering one-click unwatch;
- dashboards with ageing buckets, median and P90 cycle time, and created vs completed, where every figure links to the list behind it.

**Do better than the products studied:**
- no bulk decisions; instead, a fast "next item" after each decision;
- chips summarising the filters in the results heading;
- scope stated on every dashboard ("Showing: Credit Division and its units").
