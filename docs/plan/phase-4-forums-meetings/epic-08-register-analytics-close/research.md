# E08 Decision register, analytics and close-out — research

> Step 1 of `AGENTS.md §5`. Every web source was accessed on **2026-11-21**. **[snippet]** marks a source seen only as a search-result excerpt or a page summary, not read in full; nothing load-bearing rests on a snippet alone. Repository facts were read from `master` at 766740f (after #206).

## 1. Starting point (code and plans)

### 1.1 What the earlier epics give E08
| Need | Where | State on `master` |
|---|---|---|
| Decisions per item: outcome, text, conditions, votes, named dissent, quorum snapshot, `decidedAt`, `finalAt`, release to the memo | E06 design §1.1, ADR-0065 (`minutes_decision`, `minutes_decision_entry`) | Planned; E06 T1–T2 merged, **T3 (decisions) is being built**, T4 (resolutions) next |
| Resolutions numbered per committee and period, voids, the per-committee register and its completeness check (`GET /forums/{fid}/resolutions/check`) | E06 §1.3, §3 | Planned (E06 T4) |
| The read rule for decisions: `MinutesAccess` on `PaperAccess` and `Recusals`; in the per-committee register "items the viewer is recused from show number, meeting and 'restricted'" | E06 §1.9, §3 | Planned (E06 T3, T4) |
| Events: `DecisionRecorded` (E07 asked for corrections too), `DecisionReleased`, `MinutesApproved` | E06 §2, E07 §11 | Planned |
| Actions with `ActionAccess`, RAG and overdue derived in SQL, `ActionChanged` events, the tracker's totals "from the same predicate" | E07 design §1.5, §1.14, §2.1 (ADR-0067) | Planned (#206 merged the plan); E07 T1–T12 to build |
| The one item rule (`PaperAccess`) and recusal (`Recusals`, `DeclarationExclusions`), the alternate's inheritance, fail-closed while the chair rules | E03 T4, E04 T2, E05 T3 (ADR-0061, ADR-0062, ADR-0064) | Built |
| The leak suite and its registry, with E08's placeholder paths `/api/v1/decisions`, `/api/v1/decision-register`, `/api/v1/analytics` | `backend/src/test/kotlin/com/memo/meeting/PaperSurfaceRegistry.kt` (`pending`), `RecusalLeakIT` | Built (E04 T5, #202). The meta-test's module list names `com.memo.decision`, a module that does not exist |
| Attendance register per meeting and person (`meeting_attendance`: capacity, state, mode, first arrival, last departure), events, `MeetingAttendance.of(meetingId)` | E05 T4 (#200), `meeting/V9` | Built |
| Quorum snapshots with each decision (`meeting_quorum_snapshot`) | E05 T5 (#203), `meeting/V10` | Built |
| Submissions with `submitted_at`, `triaged_at`, `decided_at`, `previous_id` (referrals and re-queues), the placement's meeting | E02 T2 (`forum/V3`), E03 T1 (`forum/V5`) | Built |
| Agenda revisions with `published_at`; items and papers flagged LATE or TABLED; `AgendaLateness.deadline` (internal) | E03 T3, T4 (`meeting/V2`, `V3`) | Built |
| Search tables behind `SECURITY DEFINER` functions, the `EXPLAIN` gate, the equivalence property test, audit `search.performed`, a per-user rate limit | Phase 3 E05, ADR-0054, `search/V1`–`V3`, `com.memo.search.internal` | Built |
| The approvals dashboard: `<figure>` with a caption, a summary sentence and its data as a table ("Show as table"), Recharts 3.10.1 loaded only on its route | Phase 3 E05 T7, `web/src/features/dashboard/Figure.tsx`, `charts.tsx` | Built |
| Streamed XLSX with string cells and the formula prefix (`SheetCells` in `common.poi`), SXSSF with encrypted temporary data; generated pages (`ReportPages`, ADR-0052) | E04 T4, Phase 3 E04/E06 | Built |
| Rate limits as an enum (`RateLimit`): `SEARCH_PER_USER` 30/min, `DECLARATION_EXPORT_PER_USER` 5/h | `common/ratelimit/RateLimiter.kt` | Built |

### 1.2 Findings while reading
- **E04's placeholder said "rows carry the item's recused ids".** Declarations change through `DeclarationChanged`, which is published *after commit* through the event publication registry (E04 design §2). Rows that carry recused ids would therefore lag a new declaration by the listener's delay, a leak window. Evaluating the viewer's recused items at query time closes it (design §1.3, ADR-0069).
- **E06's per-committee register already shows a recused officer "number, meeting and 'restricted'".** A cross-committee register that *excluded* such rows would disagree with it, and would hide numbers from the completeness check auditors rely on (§3.2). E08 keeps E06's behaviour.
- **E07 publishes `ActionCounts.overdue(forumId)` without a viewer.** Analytics must count only actions the viewer may see (E04 "no count"), so E08 needs a viewer-scoped count built on `ActionAccess` (design §11).
- **`com.memo.decision` in the meta-test.** No module of that name is planned. E08 puts the register in `search` (it is a search over a search table) and the measures in a new `analytics` module, and the meta-test's list changes to match (design §2).
- **Migrations on `master`:** `forum/V7`, `meeting/V10`, `memo/V20`, `search/V3`, `workflow/V16`, `notification/V4`. No open branch holds an unmerged migration (checked every `origin/phase4/*`, `fix/*` and `docs/*` branch). E06 plans `minutes/V1`–`V4`; E07 plans `forum/V8` and `actions/V1`–`V4`. **E08 takes `search/V4` only.**

## 2. Versions (R1)
E08 adds **no dependency**. It uses what is pinned:

| Component | Pinned | Latest seen | Note |
|---|---|---|---|
| Recharts | 3.10.1 | 3.11.0, released 17 November 2026 (<https://github.com/recharts/recharts/releases>) | Under two weeks old: Renovate's minimum release age applies; take it when Renovate offers it. No feature of 3.11 is needed |
| Apache POI (XLSX, SXSSF) | 6.0.0 | — | As E04's export and Phase 3 E06 |
| Apache PDFBox (`ReportPages`) | 3.0.8 | — | As ADR-0052 |
| PostgreSQL | 18.6 (Compose) | — | ADR-0054 asks for 18.7 or later (GIN fix) from 2026-11-26; `search_decision`'s GIN index is covered by that upgrade |
| TanStack Table / Query | 9.3.1 / 5.104.2 | — | Register list and figures' tables |

## 3. Decision and resolution registers

### 3.1 What regulators and law ask to be kept
- **Basel Committee, *Corporate governance principles for banks* (2015), ¶60:** the board "should maintain appropriate records (eg meeting minutes or summaries of matters reviewed, recommendations made, decisions taken and dissenting opinions)", available to the supervisor. Read in E04 and E06 research; <https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks>.
- **Bangladesh Bank, notice of 4 August 2025** (reported by The Daily Star, read): banks must record every differing opinion, observation and "note of dissent" in the minutes of board and committee meetings, in full, and the remarks of Bangladesh Bank's observers; earlier circulars (BRPD Circular 02 of 11 February 2024) stay in force. <https://www.thedailystar.net/business/news/banks-asked-clearly-mention-dissent-meeting-minutes-3955276>. A supervisor or internal auditor will therefore ask for **decisions with dissent**; the register must filter on it.
- **UK Companies Act 2006 s.248:** minutes of directors' meetings must be recorded and kept for at least ten years from the meeting; failure is an offence by every officer in default. <https://www.legislation.gov.uk/ukpga/2006/46/section/248> **[snippet]** (search excerpt of the statute and its explanatory notes). The CGI recommends keeping them for the life of the organisation **[snippet]** (<https://www.companylawclub.co.uk/minutes-of-members-and-directors-decisions>, quoting the CGI).
- **Auditors read the minutes.** ASA 560 (Australia's conformed ISA 560, *Subsequent events*), ¶7(c): the auditor's procedures include "Reading minutes, if any, of the meetings, of the entity's owners, management and those charged with governance" held after the date of the financial statements. Read: <https://www.legislation.gov.au/F2009L04094/2011-07-01/2011-07-01/text/original/epub/OEBPS/document_1/document_1.html>. The IAASB text and the UK, Hong Kong and New Zealand adoptions carry the same procedure **[snippet]** (<https://xrb.govt.nz/standards-navigator/isa-nz-560>, <https://www.frc.org.uk/docs/1700/html>).
- **Certified extracts, not the minute book:** banks, registrars, auditors and counterparties ask for a certified true copy of a resolution instead of the minute book **[snippet]** (<https://www.taxclue.in/blog/certified-true-copy-of-resolution-format-auditor-appointment-and-additional-director/>). E06 already issues extracts (§1.6.6); the register links to them.

**What this means for the register:**
1. Auditors work **period by period and committee by committee**: "every decision of the Board Credit Committee between 1 July and 30 June". Date range and committee are the first filters.
2. They test **completeness**: numbers 1..n per committee and period, with each void explained. E06's register check proves it per committee; the export carries it.
3. They look for **exceptions**: dissent, conditions, decisions taken without a quorum awaiting ratification, decisions corrected after the meeting, approvals with a validity date that has passed.
4. They need **evidence they can keep**: a spreadsheet they can sort, and a PDF they can file, each saying who produced it, when, with what filters.
5. They need to trace a decision **to its source memo** ("which resolution approved credit proposal CP-2026-00042?") and **to the record** (approved minutes, extract).

### 3.2 How portals present decision registers
| Product | What it offers | Source |
|---|---|---|
| **BoardPro** "Decision register" | "A searchable record of Decisions made by the Board", including decisions outside meetings ("Flying Minutes") and votes. Filters: date range, Type (meeting or flying minute), Outcome (Not Approved, Approved, Not Set); keyword search; Reset; **export to PDF**. Each row opens the minutes. Only decisions in unconfirmed minutes can be edited | <https://help.boardpro.io/en/articles/10297276-decision-register>, <https://help.boardpro.io/en/articles/11364632-review-past-decisions> (read) |
| **Diligent Boards** (Minutes) | Attendance reporting per committee and date range, exported to Excel (§4.2). Minutes exports in Word; actions exported as .xlsx. A resolution register with committee and date filters is not documented in the help pages found | <https://help.diligentoneplatform.com/helpdocs/boards/en-us/Content/boards-add-ons/minutes/attendance-reporting.htm> (read); <https://help.highbond.com/helpdocs/boards/en-us/Content/boards-add-ons/minutes/exporting-minutes.htm> **[snippet]** |
| **OnBoard** Advanced Search | One keyword search over files, conversations, **voting records, meeting minutes** and agendas; results show type, location and filters. The page does not say whether results respect permissions | <https://www.onboardmeetings.com/board-portal/advanced-search/> (read) |
| **iBabs** | Decision lists "segmented by agenda type, date, and approved / rejected status"; "Export decision lists, minutes and to-do's in one click"; decisions published and archived | <https://www.ibabs.com/en/actions-decisions/> **[snippet]** (403 on fetch) |
| **ISO** committee documents | Resolutions searched as a document type "Decision" with a range of resolution numbers (Start, End) and the year | <https://helpdesk-docs.iso.org/article/512-search-resolutions> (read) |

**Adopted:**
- Date range, committee, type of committee and outcome as the first filters (BoardPro, iBabs), with a keyword search and one Reset (BoardPro).
- Number lookup: a resolution number in any spelling finds it, as memo numbers do (ADR-0054's `ref_norm`, `ref_tail`), and a number range per committee (ISO).
- Export to a spreadsheet and to PDF (BoardPro: PDF; iBabs and Diligent: Excel).
- Each row leads to its record: the meeting, the resolution page, the approved minutes, the extract (BoardPro opens the minutes).
- A state on every row: recorded, numbered, sent to the memo, confirmed by approved minutes (BoardPro's "unconfirmed minutes").

**Where we do better:**
- **Permissions are stated and tested.** OnBoard's page says nothing on it. Our register runs one rule, with a recusal leak suite and an equivalence test against E06's rule.
- **Completeness is shown, not assumed.** The export carries the committee-and-period number check with voids and their reasons for those who may see them.
- **Exceptions are filters:** with dissent, with conditions, to be ratified, corrected, validity passed.
- **Decisions link to memos both ways,** which none of the portals above have (their decisions start in the portal).

### 3.3 Retention
- The register is **derived**. Its rows are rebuilt from decisions and resolutions, and never deleted. The records it points to keep the retention of their committee type (E01, stored; Phase 7 executes it), and E06 keeps minutes and extracts as object-locked records.
- Exports are produced on demand and not stored. Their production is audited with the filters, the row count and a hash of the rows (design §5).

## 4. Meeting analytics: useful or vanity

### 4.1 The principle
- "A useful metric is one that changes what someone does next"; vanity metrics are easy to count and guide nothing **[snippet]** (<https://techcrunch.com/?p=1250207>).
- Attendance, meeting length and agenda-item counts can give "an illusion of productivity"; perfect attendance does not make a board effective; compliance measures are "necessary but not sufficient" **[snippet]** (<https://boardsi.com/the-metrics-that-matter-rethinking-how-boards-measure-performance/>).
- Pair measures so one number cannot be gamed (cycle time with the share past due) and keep the set small **[snippet]** (<https://www.performancemagazine.org/?p=31155>, <https://performancemagazine.org/?p=32038>).
- Track cycle times by segment (approval tier, customer type, exposure), not as one average **[snippet]** (APQC, <https://www.apqc.org/resource-library/resource/credit-approval-cycle-time-how-cfos-can-cut-delays-without-raising-risk>).

### 4.2 Measures with a reason to exist
| Measure | Why it is useful | Evidence |
|---|---|---|
| **Attendance per member and per committee** | It is a **disclosure duty**, not a vanity number: the annual report sets out "the number of meetings of the board and its committees" and "the individual attendance by directors" (UK Corporate Governance Code 2024, Provision 14). Bangladesh's BSEC code asks for the number of board meetings and each director's attendance (ACI Limited's compliance report lists it) **[snippet]**. Investors' guidance treats under 75 % as a concern (Glass Lewis via Davis Polk) **[snippet]**. Diligent's attendance report exists "for annual reporting, audit requirements, and answering Director inquiries": per committee and date range, each attendee's meetings attended and percentage, exported to Excel | FRC Code 2024 (an AI-converted HTML page the FRC marks as not human-verified): <https://www.frc.org.uk/docs/6709/html/>; 2014 Code A.1.3 **[snippet]** <https://www.scribd.com/doc/297246773/UK-Corporate-Governance-Code-2014>; BSEC code <https://www.sec.gov.bd/slaws/Corporate_Governance_Code_10.06.2018.pdf> (404 on fetch) and ACI <https://aci-bd.com/assets/files/ACI-FL/cgg/cgc-status_-aci-fl_2017-2018.pdf> **[snippet]**; Davis Polk <https://www.davispolk.com/index%2ephp/print/pdf/node/111554> **[snippet]**; Diligent (read, above) |
| **Actions overdue, and actions completed on time** | Overdue counts tell the secretary whom to chase; the on-time share for closed actions tells the chair whether dates are realistic. Pairing them stops "closing late but closing" from looking good. E07's research (CGI, IIA 15.2, RBI's action taken report) asks for missed and revised dates | E07 research §3; pairing advice above |
| **Time from submission to decision** (median and 90th percentile, by committee and memo type) | It is the committee's share of the credit turnaround that customers and management watch. Benchmarks are measured in calendar days from application to decision (OpsDog) **[snippet]**; committees are a known bottleneck when deals exceed delegated authority (Moody's) **[snippet]**; segment it (APQC) | <https://opsdog.com/products/cycle-time-commercial-loan-application-to-underwriting-decision> **[snippet]**, <https://www.moodys.com/web/en/us/insights/banking/improving-commercial-lending-efficiency.html> **[snippet]** |
| **Items deferred** (share, and items deferred twice or more) | A deferral is a decision not taken. A rising share points to late or weak papers, or to packed agendas, which Board Intelligence names as common problems **[snippet]** | <https://www.boardintelligence.com/blog/how-to-measure-board-effectiveness> **[snippet]** |
| **Papers on time** (agenda published by the committee's deadline; items and papers added late) | Late papers are a standing complaint: 15 % of 382 directors in a 2024 NACD and Board Intelligence study said materials came too late to review, and 35 % in a 2025 Board Intelligence survey of US directors; a PwC Ireland 2023 survey found 77 % of boards circulate 5–9 business days ahead **[snippet]**. The IoD advises the charter state a submission deadline **[snippet]**. E03 already records the deadline and LATE and TABLED flags, so the measure costs nothing new | <https://www.boardintelligence.com/blog/the-state-of-board-effectiveness-in-2025> **[snippet]**, <https://www.iod.com/resources/governance/the-sacrosanct-value-of-a-well-compiled-and-timely-board-pack/> **[snippet]** (403 on fetch) |

### 4.3 Left out on purpose
- **Who opened which paper, and for how long** (E03 records opens for the audit). Directors read printed packs too, so it measures the tool, not the work, and it reads as surveillance of board members. It stays an audit record, not a chart.
- **Number of meetings held, agenda items, pages in the pack, meeting length.** Counts without a decision behind them (§4.1).
- **A composite "board score" or league tables of members.** Attendance is shown per member because disclosure needs it, sorted by name, not ranked.

## 5. How the numbers are shown to older, non-technical users
- **Tables are not a fallback for older readers; they work best.** In a CHI 2025 study comparing younger adults with people in late adulthood across ten tasks and five basic visualisations, older participants were noticeably more accurate with a **table** than younger ones on many tasks, and ranked **line charts lowest** for accuracy across several tasks; older participants took longer at comparable accuracy and varied more between individuals **[snippet]** (While and Sarvghad, *Toward Filling a Critical Knowledge Gap: Charting the Interactions of Age with Task and Visualization*, <https://arxiv.org/abs/2503.02699>).
- **Bars over pies, donuts and radial charts.** An earlier study of people aged 60 and over found bar charts the most effective for speed and accuracy **[snippet]** (British Library record, *Elementary Graphical Perception for Older Adults*, <https://eld.bl.uk/catalog/100170248405_0x000001>); the CHI 2024 replication with participants aged 65 and over found "strong evidence of differences for participants aged 75 and older" (read: <https://arxiv.org/abs/2403.12343>).
- **Contrast:** age reduces contrast sensitivity; for universal use the authors suggest contrast of "0.02 or higher" and avoiding fine detail above eight cycles per degree (read: Shao et al., <https://arxiv.org/abs/2407.21767>). Thin gridlines and pale series are out.
- **Text alternatives (WCAG 1.1.1):** a short alternative that identifies the chart and says where the long description is, and a long description with scales, values and trends; a data table can be part of it, grouped with the chart in a `<figure>` with its `<figcaption>`; `aria-describedby` loses structure, so tables are not referenced that way (read: W3C WAI, <https://www.w3.org/WAI/tutorials/images/complex/>).
- **Lead with the message:** "Text descriptions should convey the main message and be equally as informative as viewing the chart"; "could it be recreated with that description?" over the phone; put it in the body text (read: GOV.UK, <https://accessibility.blog.gov.uk/2023/04/13/text-descriptions-for-data-visualisations/>).
- **Colour is never alone** (WCAG 1.4.1), as E07's RAG words.

**Adopted for E08's screens** (ux-review principles): one sentence first ("Board Risk Committee: 92 % attendance over the last 12 months, 3 actions overdue, decisions took a median of 18 days"); the **table visible by default** with the chart beside or under it, not the other way round (a change from Phase 3's "Show as table", justified by the CHI 2025 finding); horizontal bars with value labels; no line, pie or donut charts; every figure links to the list behind it.

## 6. Checking the overview against practice (Phase 4 §10)
| Overview (E08) | Practice | Result |
|---|---|---|
| Register filtered by date, forum, type and outcome, exportable for auditors | BoardPro, iBabs filters; auditors work by committee and period and test completeness (§3) | **Kept**, plus number lookup, exception filters and the completeness check in the export |
| Search through `search_decision` behind the search function (ADR-0054) | GIN is not used under forced RLS (ADR-0054); 54,000 decisions per tenant is enough to need the index | **Kept** |
| Recusal: "rows carry the item's recused ids; excluded" (E04 placeholder) | E06's register shows a recused officer the number only; auditors need every number | **Refined:** the viewer's recused items are evaluated live; such rows show as a restricted line (number, committee, meeting, date) with no content and are never matched by text or content filters (ADR-0069) |
| Analytics: attendance, overdue actions, time to decision, items deferred | §4.2 | **Kept**, plus "actions completed on time" (pairing) and "papers on time" (directors' complaint, data already there); paper-reading statistics left out (§4.3) |
| "In the style of E05's dashboards" | Older users read tables best (§5) | **Kept with one change:** the table is shown by default |
| Volumes: 10 years of 30 forums meeting monthly; register and actions inbox load-tested against NFR P1 | — | **Kept** (design §8) |

These refinements are recorded in ADR-0069 and ADR-0070 and reported to the owner (Phase 4 §10: a finding that changes a recommendation goes into an ADR and is reported).

## 7. Earlier close-outs (the shape E08 follows)
- **Phase 2** (`phase-2-workflow-engine/retrospective.md`): exit status per criterion with evidence; Phase-2 special tests; generic exit items (demo script, ASVS, usability, manual accessibility); "What changed versus the plan"; "What the gates caught"; "Carried". Lesson recorded there: **a stage that only runs on `master` (load, nightly) must be run by hand before the phase closes**, and a red nightly stops the next merge.
- **Phase 3 E05 T8 and E06 T10:** journeys, load stage, demo seed, runbook, ASVS rows, and the phase demo script (`phase-3-memo-efile/demo.md`: one table per epic with "Show / Who, where / What to point out").
- Phase 3 is not yet closed (its E07, the M1 gate, follows Phase 1b), so there is no Phase 3 retrospective to copy.

## 8. Sources tried that failed (not retried)
- `www.sec.gov.bd` BSEC code PDF: 404.
- `ibabs.com`, `iod.com`: 403.
- `media.frc.org.uk` key-changes PDF and the KPMG copy of the 2024 Code: binary streams the fetcher could not read; no PDF text tool on this machine.
- `cgi.org.uk` press release on board packs: 404.
- Not found: an OnBoard or Diligent help page describing a resolution register with committee and date filters; a Bangladesh Bank rule on sending minutes to the supervisor (one press report says 15 days for financial institutions, not verified).
