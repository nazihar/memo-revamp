# E08 Decision register, analytics and close-out — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows. ASVS 5.0 IDs as in `docs/security/asvs-checklist.md`, chapter-level where the checklist groups them. Builds on Phase 3 E05's search threat model (the definer function, terms out of URLs), E03 and E04's (the item rule, recusal), E06's (decisions, resolutions) and E07's (actions). The phase-close review of every Phase 4 threat model is task T11 (§ "Phase-close review" below).

## Data flows
1. A decision is recorded, corrected, numbered, voided, released or made final; an agenda revision is published. The search module writes the decision's row through a definer function in the same transaction. A backfill fills older decisions.
2. A viewer searches the register: the application computes their scope, clearance and recused items, and the definer function returns FULL rows, restricted lines and counts.
3. An officer or auditor exports the register as XLSX or PDF, with the number check.
4. A viewer opens analytics: attendance, actions, decision timing and deferrals, papers on time, for their committees; an officer exports attendance.
5. At phase close, the exit journey runs against dev and the demo cycle is seeded there.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | A former member, guest, item attendee or memo reader uses the register or analytics to read a committee's decisions | 2, 4 | Scope from today's roles only (officer or member in office, `forums.manage`, `forum.read`); others 403; former members lose access (E03's rule); matrix columns for each | V8 |
| T1 | A row is written for the wrong tenant or with another tenant's ids | 1 | The writer functions check the tenant argument against `app.tenant_id` and raise otherwise; `memo_app` has no privilege on the table; `DecisionSearchExplainIT` (as built: "every function refuses another tenant" and the privilege check; there is no separate `DecisionSearchCrossTenantIT`) | V8 |
| T2 | The register shows a stale decision (an old outcome after a correction, a voided number as in force) | 1 | Indexing in the change's transaction from E06's events, corrections and voids included (`ResolutionChanged`); the nightly unindexed check; the property test compares rows with E06's current entries | V2 |
| T3 | An export is altered after it leaves the system, or denied later | 3 | The audit records the filters, count and `rowsSha256` over `decision_id:entry_seq`, so a copy can be matched to the register; the records themselves (approved minutes, extracts) are object-locked PDF/A (E06) | V16 |
| R1 | An auditor or secretary denies having exported the register or attendance | 3, 4 | `search.decisions.exported` and `analytics.attendance.exported` in the hash-chained audit, written before streaming | V16 |
| I1 | A director recused from an item finds its decision by searching words of the decision text, or by an outcome or "with dissent" filter, or sees it ranked | 2 | Text and content filters match FULL rows only; a recused viewer's row is a LINE; ranking only among FULL rows; leak suite with canaries in decision text and conditions | V8 |
| I2 | A just-declared interest is not yet applied, so the director still finds the item | 2 | Recused items are computed in the request's transaction from the committed declarations (`Recusals.itemsOf`), including awaiting rulings (fail closed) and alternates' inheritance; no indexed copy that could lag | V8 |
| I3 | Counts, facets or analytics reveal a hidden item: "Deferred (3)" where the viewer sees two, a median moved by a hidden decision, late papers on a restricted item | 2, 4 | Every count from the same rule in the same query; outcome facets and M3/M4 over FULL rows only; M5's late counts through `PaperAccess` with live recusals; differential leak tests (the measure equals the measure on data without the hidden items) | V8 |
| I4 | Classified decisions or meetings above the viewer's clearance appear as lines or in attendance | 2, 4 | Rank above clearance is NONE: not returned, not counted, not in attendance | V8 |
| I5 | Search words leak into URLs, proxies or logs, or reach the audit's readers (words typed by a secretary can name a borrower an auditor may not see) | 2 | Searches are POST with a JSON body; the audit holds the words as a SHA-256 of their normal form only (T2, as built), with the number of words and whether it was a number lookup | V14 |
| I10 | A client reads a cursor to learn a restricted line's decision time, or crafts one to page past the rule or into another person's search | 2 | Cursors are sealed (T2): AES-256-GCM under the installation's data key, bound to the tenant, the viewer and a fingerprint of the search; anything else is 422 `cursor INVALID`; the keyset values only position a page, the rule is applied again on every call | V8, V11 |
| I6 | The register's table holds discussion text that a future feature could expose | 1 | The row never holds notes, minutes discussion, papers, natures of interests, dissenters' names or override reasons (design §1.1); a test asserts the column list | V14 |
| I7 | An export spreads LINE rows' content or another viewer's view | 3 | Exports run the same function with the exporter's parameters; LINE rows are "Restricted item"; voids' reasons only where the viewer may see the item | V8 |
| I8 | Formula injection through titles or decision text in the XLSX, or markup in the PDF | 3, 4 | String cells with the formula prefix (`SheetCells`); PDF text drawn as text by PDFBox (ADR-0052); control characters stripped | V1, V5 |
| I9 | Per-member attendance is read by colleagues as a performance ranking | 4 | Members see only their own row and the committee's total; no ranking or score; officers and oversight see all, as disclosure requires | V8 |
| D1 | Expensive searches or analytics over ten years exhaust the database | 2, 4 | Rate limits (search 30 a minute; exports 5 an hour); text caps (200 characters, 12 terms, 2,000 candidates, 2 s statement timeout as ADR-0054); periods at most 25 years for the register, 10 for analytics, 5 for per-member attendance; the `EXPLAIN` gate | V2 |
| D2 | Large exports exhaust a node | 3 | 20,000 rows (XLSX) and 2,000 (PDF), streamed (SXSSF with encrypted temporary data; `ReportPages`), one read-only transaction | V2 |
| E1 | A member exports, or a reader of one committee searches another | 2, 3 | Deny by default; scope checked per committee in body filters (422) and paths (404); matrix rows for every endpoint and column | V8 |
| E2 | The privileged function is called with forged parameters (a wider scope or no recused items) | 2 | Only the application role may EXECUTE; parameters are computed server-side by `DecisionRegisterAccess` from live state, never from the request; the equivalence test pins the function's rule to E06's | V8, V15 |

## Residual risks
- A secretary may write discussion into a decision's text. It is then searchable by those who see the row in full, as on the meeting page. The runbook and the decision panel say that the text is the decision, not the discussion.
- Restricted lines show that a numbered decision exists at a meeting. This is already visible on the agenda to the same viewers and is needed for completeness.
- Time-to-decision figures for a committee with few decisions describe individual items. The viewer sees those items in full anyway.

## ASVS rows to add (T11)
- V8: the register's one rule with restricted lines, live recusals, scope by today's role; analytics counts over visible rows only; per-member attendance limited to own row for members.
- V15: `search_decision` reached only through definer functions in `db/migration/search/` (the existing "one privileged path" row extended).
- V16: searches and exports audited with the rows' hash.
- V1 and V5: exports' formula prefix and text-only PDF.
- V2: caps, rate limits, periods, streaming.
- V14: terms out of URLs; no discussion in the register's store.

## Phase-close review (T11)
At phase close every Phase 4 threat model (E01–E08) is re-read against what was built: each mitigation checked for its test or evidence, residual risks confirmed or closed, and each file gains a line "Reviewed at phase close on {date}: {result}". New threats found go into the epic's file and, if they need work, into the retrospective's "Carried". The external pen test for Phase 4 joins the one booked after Phase 1b (Phase 4 Q2); its scope names the recusal leak surfaces (overview risk R1) and the definer functions.

## Reviewed at phase close
Reviewed at phase close on 2026-11-28 (T11): **every mitigation is built and tested.** The performance half of D1 waits for T5.
- **Changed by the build:**
  - T1: the cross-tenant test lives in `DecisionSearchExplainIT`, as corrected above.
  - I5 (T2): the audit holds the words as a SHA-256 of their normal form, never the words.
  - I10 (T2, new): cursors are sealed.
  - D2 (T3): with words, an export takes at most 10,000 candidates, so a word search over 10,000 decisions is `TOO_MANY_ROWS` for a spreadsheet too.
  - I7 (T3): a void's reason is shown only when its decision is a FULL row of the same export.
  - S1, E1 (T3): an officer who is not oversight exports only the committees where they are an officer. A committee where they only hold a seat is `FORUM_OUT_OF_SCOPE`.
  - Analytics (T4, master CI): a test tenant without a zone dated meetings held in Dhaka after the default period from 18:00 to 24:00 UTC. The fix was in the test (#247, #251), and `AnalyticsDayBoundaryTest` pins it. The demo tenant is now on Asia/Dhaka (E06 T12), so the gap the T4 entry of the log carried for the demo is closed.
- **Evidence:**
  - S1, E1: matrices `p4-e08-register-analytics.yaml` and `p4-e08-analytics.yaml`, `DecisionRegisterIT`, `AnalyticsIT`. T2: `DecisionIndexingIT` and the nightly unindexed check. T3, R1: `DecisionExportIT` recomputes `rowsSha256`.
  - I1, I2, I7: `RegisterLeakIT`, `RecusalLeakIT` (REGISTER and EXPORTS), `DecisionRegisterIT` ("a declaration applies at once"). I3, I9: `AnalyticsLeakIT` (the differential), `AnalyticsIT`. I4: `AnalyticsIT` (above clearance), `DecisionRegisterEquivalenceProperties`.
  - I6: `DecisionRowTest`. I8: `SheetCells` (`DecisionExportIT`, `AnalyticsIT`). I10: `DecisionRegisterIT` (sealed cursor).
  - D1: rate limits, caps and periods (`DecisionRegisterIT`, `AnalyticsIT`), and the `EXPLAIN` gate (`DecisionSearchExplainIT`). D2: `DecisionExportIT` (20,010 rows).
  - E2: `DecisionRegisterArchTest`, `DecisionSearchExplainIT` (privileges), `DecisionRegisterEquivalenceProperties`.
- **Still open in E08 (not security gaps):** T5's volumes (`RegisterVolumeIT` at 108,000 decisions, `AnalyticsVolumeIT`, the Gatling simulation) are on `phase4/e08-t5`. Until they merge, D1's budget evidence is the `EXPLAIN` gate on seeded data. T8's exit journey and T10's runbook `decision-register-and-analytics.md` are also open.
- **ASVS:** the rows listed above were added (V8 twice, V15 extended, V16, V1, V2, V14), plus a V2 row on business dates in the tenant's zone.
- **Residual risks:** all three confirmed.
- **Phase 4 as a whole:** the threat models of E01–E07 were re-read at the same time, and each has its own "Reviewed at phase close" section. The pen-test scope is `docs/security/pen-test-scope.md` (Phase 4 part). It covers the recusal leak surfaces, the definer functions, minutes copies, exports, invitations, the action taken report, step-up and rate limits.
