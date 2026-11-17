# E05 Inbox, search and dashboards — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, property, integration, matrix, smoke E2E), nightly (browsers, visual, pseudo-locale), weekly (load at V1).

## Scope and risks
- **Risks covered:**
  - search leaking an unreadable memo (results, counts, facets, snippets, ranking);
  - the definer function drifting from the E03 rule;
  - slow search at volume under RLS;
  - bulk actions doing more than allowed;
  - notification fatigue.
- **Seed data:**
  - the demo tenant (memos, notes, attachments with text);
  - an attachment classified above its memo;
  - a reader without access;
  - a head of the Credit Division;
  - a holder of `inbox.team.read`.
- **V1** (weekly and on demand): 1,000,000 memos in one tenant with the four reader profiles (a narrow participant, a unit head, a unit reader or auditor, a low clearance).

## Tests by task
| Task | Layer | Test |
|---|---|---|
| T1 | Spike (recorded) | V1 generation time; plans as `memo_app` for each profile; p95 per profile; `fastupdate` on vs off; ranking cap 1,000 / 2,000 / 5,000 |
| T2 | Integration | `SearchIndexIT`: metadata and note rows in the source's transaction; chunking of a 1 MB text (a phrase near the end found); "too long" splits further; reclassification updates chunk rank; backfill fills a tenant once |
| T2 | Persistence/RLS | The three tables have no grants to `memo_app`; `search_saved` has RLS forced; `RlsMetaTest` exceptions are exactly the three tables |
| T3 | Property | Random memos, participants (active, revoked, lapsed), shares, unit scopes, classifications and chunks: `search.find` returns exactly the readable memos matching the terms; counts and facets equal those of that set; no snippet from a chunk above clearance |
| T3 | Integration | `SearchIT`: identifiers (`M-…`, `00042`, `CR/2026/0001`); phrases, OR and `-`; prefix on the last term at 3+ characters; newest sort; cursor paging without repeats; capped count "2,000+"; input caps (422); rate limit (429); timeout (503); audit of every search |
| T3 | Architecture | Semgrep: no SECURITY DEFINER function outside `search/`; `EXPLAIN` gate test runs as `memo_app` and fails on a sequential scan of a search table |
| T3 | Matrix | `p3-e05-search.yaml`: search, saved searches × subjects (other owner 404) |
| T4 | Integration | `MemoWatchIT`: watch and unwatch (readers only); auto-watch on initiate and act; watch dropped on lost access (sweep); two-tier notifications (needs-you immediate; watching in-app and digest); no self-notification; due sort (overdue first, no due last) |
| T4 | Integration | `InboxBulkIT`: claim, release, watch, unwatch, reassign with per-item results; at most 100; a decided task reports `CHANGED`; reassign outside scope `FORBIDDEN`; no decision action accepted (422) |
| T5 | Integration | `TeamViewIT`: head scope by seat (subtree), grant scope, restricted memos, sort by due, reassign within scope; 403 for others. `DashboardIT`: ageing buckets in working days (business calendar), overdue, cycle-time median and P90, throughput, scope rules |
| T5 | Matrix | `p3-e05-inbox.yaml`: watch, bulk, team, dashboards × subjects |
| T6 | Unit (web) | Search page: filters to URL and back; chips remove filters; polite count; `<mark>` from delimiters as text (no HTML); empty, rate-limit and timeout states; save dialog; saved list; axe in English and Arabic |
| T7 | Unit (web) | Follow toggle; Watching tab; bulk bar with per-item results; Team tab with restricted rows; dashboard figures with summaries and tables; preferences; axe |
| T7 | E2E (smoke) | Charts render with **no CSP violation** (fixture) |
| T6, T7 | Visual (nightly) | Search page (results, filters, empty), team view, dashboard: light and dark, LTR and RTL, phone |
| T8 | E2E (smoke) | `search.spec`: a note's words and an attachment's words find the memo, a reader without access does not find it, save and reopen a search. `inbox-team.spec`: a head sees the team's tasks by due time and reassigns one; bulk claim with per-item results |
| T8 | Load (weekly) | Gatling at V1: search p95 < 300 ms per reader profile; inbox by due < 150 ms; team view < 300 ms; dashboard < 800 ms |

## Exit checklist
- [x] Every test above green in its stage (#129 CI, 2026-11-16: backend, e2e with `search.spec` and `inbox-team.spec`; the weekly load jobs on the branch; the attachment-words journey runs nightly)
- [x] Matrix and audit coverage green with the new endpoints and actions (`EndpointCoverageTest`, `AuditActionCoverageTest`, #129 CI)
- [x] T1 spike recorded; ADR-0054 numbers confirmed or amended (log, 2026-11-15)
- [x] V1 load results recorded in the log with hardware and concurrency (T8, 2026-11-16)
- [x] ASVS rows (T8, 2026-11-16: V8 search access, V2 bulk actions and search limits, V15 the definer path, V14 words out of URLs and email, V16 audit; V5 is covered by the definer row's parameters and the snippets as text)
- [x] Dependency register: Recharts and `react-is` (T7, 2026-11-16; no `pg_trgm`: ADR-0054 amendment)
- [x] Owner's review of flows H and I recorded in `ux-review.md` (2026-11-15)
