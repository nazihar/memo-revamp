# E06 Minutes and resolutions — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. Phase-4 special tests (`§12`): "resolution numbering under concurrency (parallel decisions, rollbacks leave no gap, voids recorded)" and E06's surfaces in "recusal enforced across API, search, notifications and exports". State-machine tests for decisions and minutes (every allowed and every forbidden transition). PIT on resolution numbering. CI stages: PR (unit, property, integration, matrix, leak suite, smoke E2E), nightly (PIT, Jazzer, browsers, visual, pseudo-locale, ONLYOFFICE round-trip), weekly (load).

## Scope and risks
- **Risks covered:**
  - a resolution number skipped, reused or given twice, or out of agenda order;
  - a memo resumed twice, with the wrong outcome, or never (an undecided submission item);
  - a decision changed without trace, or after release, or after approval;
  - a recused member named as voting, or counted;
  - a recused or restricted reader seeing an item's discussion through a copy, its text layer, revisions, comments, metadata, a notification or the memo's note sheet (overview risk R1);
  - a missing or duplicated content control producing a wrong copy;
  - approved minutes or their record altered; a chair's confirmation without step-up or by the wrong person;
  - any existing memo number changed by the numbering move (overview risk R8).
- **Seed:**
  - the E03–E05 seeds: the BRC meeting (7 voting members, three `independent`, a vice-chair, `secretary@`, a guest, an alternate standing in), `director@` recused from item 5, item 6 restricted to two members with a hidden title, a consent section with item 8 lifted; the MCC meeting with the BDT 10 million credit memo as item 5; the override committee type (`quorumOverride` WITH_REASON);
  - the next BRC meeting with a published agenda containing "Minutes of the previous meeting";
  - **canaries:** `CANARY-NOTE-…` in notes, `CANARY-DISC-…` typed into item 5's block of the draft, `CANARY-COMMENT-…` in a member's comment, `CANARY-PAPER-…` in papers (E04 set), checked in every copy, record, notification, email, note sheet entry, API response and audit detail for the recused and restricted readers.

## Tests per slice
| Task | Layer | Tests |
|---|---|---|
| T1 | Integration (stack) | Done as a spike (E06 log, T1): the editor is driven in a browser by `web/e2e/spike/minutes-controls.spec.ts` against the pinned ONLYOFFICE 10.0.0.1 container (open, edit inside and outside controls, deletions across controls refused, undo, force save and closing save through the callback, reopen; review mode produces `w:ins`/`w:del`), and `MinutesControlsRoundTripIT` (env-gated like the other spike ITs) writes the draft, checks the saved files and converts a copy to PDF and PDF/A. The editor cannot be driven from a JVM test, so the **nightly** coverage is the `minutes-approval.spec` journey (T11), which edits the minutes in ONLYOFFICE on the stack; the structure check runs on every saved version from T5 |
| T1 | Unit (golden) | `ContentControlWrapperTest`: the draft saved by Microsoft Word (`templating/minutes-saved-by-word.docx`) keeps nine tagged, locked controls |
| T1 | Unit | `BlockLoopFillTest` (poi-tl block sections over lists with nested sections and row loops; loop variables); `ContentControlWrapperTest` (one control per item, tag and lock, replace one control's content, restore after the previous control); `StructureScanTest` (StAX scan of 200 pages under 2 s; missing, duplicated, unknown tags) |
| T1 | Unit (regression) | Memo numbering suite (`NumberFormatsTest`, `MemoNumbersIT`, `NumberingConcurrencyIT`) unchanged and green after the move to `common.numbering`; an architecture test that `memo.internal.MemoNumbering` delegates |
| T2 | Unit + property | `ResolutionFormatTest` (parts, periods, financial year, meeting number, 100-character cap, problems); property: for random formats and dates, render then parse the skeleton gives back the period and sequence; `MinutesPolicyChecksTest` (ranges, presets, defaults when null) |
| T2 | Unit | `MinutesVocabularyTest`: the default template (English and Arabic) scans with no problems; unknown tags with suggestions; a block tag outside its block is `UNKNOWN_TAG`; memo templates scan as before (`MemoTemplateScanTest` unchanged) |
| T2 | Integration | `ForumTypeMinutesPolicyIT` (maker-checker, changes view, pinned per meeting); `ForumDecisionsIT` (decide, defer at meeting, refer at meeting, lock order with a racing triage); `WorkflowForumsDetailsIT` (details stored on FORUM_DECIDED) |
| T3 | Unit + property | `DecisionRulesTest` (each invariant of design §1.1 with its problem code); property: for random snapshots, named people and counts, a recorded decision never names a recused or absent person and never has more votes than present voting seats; `TookNoPartTest` |
| T3 | Integration | `DecisionsIT` (record, correct, history, consent section, notes autosave versions, override and ratification, `decidedAt` defaults, minutes returned to draft on correction with confirmation); `DecidedItemsPortIT` (E04's declaration on a decided item needs the correction path) |
| T3 | State machine | `DecisionStateMachineTest`: every allowed transition of design §1.1 and every forbidden one (`FROZEN`, `RELEASED`, `WRONG_STATE` on a cancelled or not-started meeting) |
| T3 | Authorisation | Matrix `p4-e06-decisions.yaml`: every endpoint × Sec, Chair, Mgr, Member, Alt, Guest, Att, Read, Memo, other-committee secretary, **recused secretary**, **recused member**; 404 vs 403 as design §4 |
| T3 | Persistence | `MinutesRlsIT`, `MinutesCrossTenantIT` (decisions, entries, notes; joins and the snapshot id); triggers refuse updates of entries and of a decision's item |
| T4 | Integration (special test) | **`ResolutionNumberingConcurrencyIT`**: 20 committees × meetings ending concurrently plus 50 concurrent decisions after HELD on 10 threads; per committee and period, sequences 1..n, no duplicates, numbers in agenda order for each meeting's HELD batch; a release forced to throw after numbering rolls back with no gap; voids listed and never reused; the register check equals the counter |
| T4 | Property + PIT | `AgendaOrderProperties` (random agendas, random recording order → agenda order at HELD); PIT on `ResolutionNumbers`, `ReleaseMapping` (mutation score ≥ 80 %). **As built:** PIT on the pure cores `ResolutionOrder`, `ResolutionChecks` and `ReleaseMapping` (with `ResolutionChecksTest`, `ReleaseMappingTest`); `ResolutionNumbers` itself is the concurrency IT's |
| T4 | Integration | `DecisionReleaseIT`: each outcome kind → submission state and workflow effect (table in ADR-0065); pass-through submissions noted; AT_MEETING_END at HELD and at once after; ON_MINUTES_APPROVAL at confirmation; idempotent rerun; one failing decision does not block the others and is reported; `RELEASED` on an outcome change; corrected-text system note on the memo. **As built:** pass-through submissions are `ReleaseMappingTest`'s (no "for information" route in the fixture); ON_MINUTES_APPROVAL runs the job of the approval by hand (T6 schedules it); the failure is forced by a submission naming a step its memo does not wait at |
| T4 | Integration (journey, backend) | `ForumEscalationIT` extended: credit memo escalated → accepted → placed → decided → meeting HELD → memo resumed and approved → final record's approval history and note sheet carry "Resolution MCC/2026/001". **As built:** on the Board Risk Committee of the E04 fixtures (`BRC/{year}/001`), the record built and its PDF text read |
| T4 | Integration | `ResolutionCounterStartIT` (step-up, once, `ALREADY_STARTED`); `ResolutionRegisterIT` (keyset pages, recused rows "restricted", `forum.read` cleared only). **As built:** both in `ResolutionsIT` (with one resolution for its decision's viewers and its memo's readers); the recused rows are the leak suites' RESOLUTIONS probes; `MinutesCrossTenantIT` covers the two tables and the four endpoints |
| T4 | Authorisation | Matrix `p4-e06-resolutions.yaml` |
| T5 | Integration | `MinutesDraftIT`: generation from the seeded meeting (attendance tables, alternates, apologies, arrivals and departures, quorum lost and restored, declarations and "left the meeting", decisions with vote, dissent, abstentions, conditions, ratification); regenerate one item keeps edits elsewhere; regenerate all needs `replaceEdits`; restore; structure check on each saved version (callback) |
| T5 | Integration | `MinutesEditingIT`: `mayEdit` for secretaries not recused; a recused secretary 403; co-editing session keys (ADR-0047); edits refused after APPROVED |
| T5 | Unit (fuzz) | Jazzer nightly on `MinutesTemplateData` (names, titles, notes, decision text with XML metacharacters, control characters, 10,000-character notes): the filled document parses, has one control per item and no injected element |
| T6 | Integration | `MinutesReviewIT` (reviewer rule with chair, vice-chair, recused chair, none; review mode; on behalf; return); `MinutesCirculationIT` (readers, deadline on the business calendar, comments and responses once, people asked for comments, withdraw, end early, sweep (i)–(l) idempotent); `MinutesApprovalIT` (AT_NEXT_MEETING via the previous-minutes item decision, DEFERRED keeps them circulated; BY_CIRCULATION; confirmation with step-up; approval row chain; meeting MINUTES_APPROVED; attendance and declarations frozen; release on approval) |
| T6 | Integration (leak) | **`MinutesRedactionIT`** and the recusal leak suite's E06 surfaces: for `director@` (recused from 5), `director2@` (not in item 6's restriction), an executive asked about item 3 only, the guest, a former member, a `forum.read` holder below item 6's level: every copy's PDF text layer, the copy's .docx (never served, checked internally), notifications, emails, `/me/meetings`, the decisions list, the resolution register and the memo's note sheet carry no canary of an item they may not see; the full record carries all canaries |
| T6 | State machine | `MinutesStateMachineTest`: every allowed and forbidden transition of design §1.5 |
| T6 | Authorisation | Matrix `p4-e06-minutes.yaml` (every endpoint × every column incl. reviewer, confirmer, vice-chair, recused chair, asked-for-comments) |
| T7 | Integration | `MinutesRecordIT` (PDF/A-2u validated by veraPDF, cover fields, the annex, object lock and retention date, audit with SHA-256, approved copies per set, fixity job verified and mismatch on a tampered object); `RecordPdfsRefactorIT` (memo's final-record suite unchanged) |
| T8 | Integration | `MinutesCorrectionsIT` (proposal, approval with step-up, version 2 with annex, version 1 readable, declined); `ResolutionExtractsIT` (not before release, VOID refused, wording before and after approval, step-up, locked, opens audited, memo readers may read) |
| T9 | Performance | `MinutesVolumeIT` (the volumes of design §8; every budget; `EXPLAIN` plans recorded for the register, decisions list, `/me/meetings` and the sweep); weekly `load` |
| T10, T11 | Component | Vitest + Testing Library: the decision panel (outcome buttons as a radio group, conditions list, vote section validation in words, people pickers limited to those present and not recused, autosave status), the minutes page per stage, comment forms, the step-up flow on "Approve the minutes"; axe on each |
| T10, T11 | E2E | Playwright: `meeting-decisions.spec` (record, consent section, override, mark held, numbers appear, memo resumed); `minutes-approval.spec` (generate, edit in ONLYOFFICE on the stack, send to chair, agree, circulate, recused member's copy, comment, next meeting approves, chair confirms with step-up, record); the E06 leg of `committee-escalation.spec`; visual baselines; pseudo-locale and Arabic RTL screenshots |
| T12 | Manual | Keyboard and screen-reader (NVDA and VoiceOver) pass over flows AA and AF, recorded in the log |

## Phase-4 special tests covered here
| Special test | Where |
|---|---|
| Resolution numbering under concurrency (parallel decisions, rollbacks leave no gap, voids recorded) | `ResolutionNumberingConcurrencyIT`, `AgendaOrderProperties`, PIT (T4) |
| Recusal enforced across API, search, notifications and exports (E06 surfaces: decisions, notes, register, extracts, draft and approved copies, records, notifications, the memo's note sheet) | `MinutesRedactionIT` and the registry probes (T3, T4, T6, T8); the surface registry meta-test fails a `minutes` controller method without a probe |

## The leak-suite surfaces added by E06
| Surface | A recused member sees | Probe |
|---|---|---|
| Decisions list and one decision | Outcome, number, text, conditions, validity; no vote, names or notes | `DecisionsProbe` |
| Notes | 404 | `NotesProbe` |
| Resolution register | Number, meeting, "restricted" | `RegisterProbe` |
| One resolution, extracts | As decisions; extracts 403 to issue | `ResolutionProbe` |
| Draft copy, approved copy, record | Their copy only; the record only when their set is empty | `CopyProbe` (text layer canaries) |
| Minutes notifications and emails | No item titles or decision text at all | `NotificationProbe` |
| `/me/meetings` minutes actions | No per-item content | `MyMeetingsProbe` |
| The memo's note sheet and final record (for memo readers) | The memo's own item's decision only | `MemoNoteProbe` |

## CI stages
- **PR:** unit, property, integration (Testcontainers PostgreSQL, SeaweedFS), matrix files, leak suite, `ResolutionNumberingConcurrencyIT`, smoke E2E, veraPDF on generated records.
- **Nightly:** PIT on numbering and release mapping; Jazzer on the template data; `MinutesControlsRoundTripIT` and the record suite with the ONLYOFFICE container; full browsers, visual, pseudo-locale.
- **Weekly (`load`):** `MinutesVolumeIT`.
