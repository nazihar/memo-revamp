# E06 Legacy import — test plan

> Step 6 of `AGENTS.md §5`, following `TESTING_STRATEGY.md §13`. CI stages: PR (unit, property, integration, matrix, smoke E2E), nightly (Jazzer, the hostile corpus against the services, browsers, visual, pseudo-locale), on demand and before close (the import volume test).

## Scope and risks
- **Risks covered:**
  - a dry run that writes, or an import that differs from its dry run;
  - a row imported twice, or changed by a re-run;
  - falsified authorship and lowered classification;
  - legacy numbers colliding with live series;
  - hostile workbooks, CSV files and OLE2 originals;
  - a report that does not balance, leaks to the uncleared, or carries formula injection;
  - an import starving live conversions;
  - imported memos leaking into workflow, notifications, final records, dashboards or boxes.
- **Seed data:**
  - the demo tenant (types with published versions and number formats, units with codes, classification levels, users with emails, a DEACTIVATED leaver);
  - a holder of `memo.import` (the demo tenant admin, through a demo role), an auditor, a user without the permission, and a user cleared below "Confidential";
  - the synthetic legacy sample from `SyntheticLegacy` (T10, a smaller copy from T5): a folder with docx, pdf, a macro-free doc, xls and ppt, rtf, tiff, a zip, a zero-length file, `Thumbs.db`, two paths differing only in case; a workbook with good rows and one of each problem; the same tables as CSV with `;`.
- **Hostile corpus** (`backend/src/test/resources/hostile/imports/`): XLSX with a DTD and an external entity in `sharedStrings.xml` and in a sheet; a zip bomb; duplicate zip entries; a huge shared-strings table; 50,001 memo rows; a 1904-date workbook; a formula without a cached value; CSV in Windows-1252, with UTF-16 and UTF-32 BOMs, invalid UTF-8, a 21 MiB file, an unterminated quoted field and a 40,000-character value; OLE2 with VBA (doc, xls, ppt), an encrypted doc, an Outlook `.msg`, a polyglot (`.doc` that is a PDF).

## Tests by task
| Task | Layer | Test |
|---|---|---|
| T1 | Spike (recorded) | `ImportSpikeIT` (not in CI after T1): parse time and peak heap for the 20 MiB workbook and the maximal shared-strings table; 1900 and 1904 dates; cached formulas; DTD refused; Commons CSV profile on the corpus; clamd verdicts on macro OLE2; Tika `/detect/stream` subtypes (doc, xls, ppt, msg, encrypted); ONLYOFFICE PDF from doc, xls, ppt, rtf and multi-page TIFF; `readInTenant` with and without `SET TRANSACTION READ ONLY` |
| T2 | Unit | `FileTypeTest`: the new types, their extensions, `NEVER_ALLOWED` unchanged; `TikaDetectorTest` (planned as `TikaSubtypeTest`): `PUT /detect`, media type to type, `.msg` and other containers refused, encrypted OOXML `ENCRYPTED`, no server, unreachable or failing fails closed, `detect` enabled in the config; `ContentInspectorTest`: OLE2 typed only with the server, RTF and TIFF from content, magic-only old Office refused; `ClamdReplyTest`: the macro and encryption signatures map to `MALWARE` and `ENCRYPTED`; `TikaClientTest` and `OnlyOfficeConverterTest`: legacy text and PDF types, none for TIFF |
| T2 | Integration | `LegacyFormatsIT` (clamd Testcontainer and an in-JVM stand-in for the Tika server's `/detect`, `TestTikaServer`): doc, xls, rtf and tiff accepted for `imports.intake` and refused for other purposes (which never ask Tika); `.msg` refused with the `FileUploadRejected` event; an encrypted .xls `ENCRYPTED` and a VBA project `MALWARE` by clamd; Tika unreachable fails closed (503) while RTF still goes in; extension mismatch `TYPE_MISMATCH`. `memo.legacy` is covered in T3, and real .ppt files by the T1 spike |
| T2 | Integration | `StagingIT` (planned as `CopyFromIT`, `PurgeIT` and `RenditionBacklogIT`): promotion through the pipeline into a record (new object, record retention, inspected again under the target purpose); same key returns the same version; expected-hash mismatch stores nothing and is audited; purge deletes the object, keeps the row and hash, 410 on download, audits `file.version.purged` once, refuses record versions (service and database); `pendingCount` by purpose (bounded) and by versions |
| T2 | Architecture | `PurposesTest` (the start-up check): only `imports.intake` and `memo.legacy` accept legacy formats, a legacy type needs the flag, only ORDINARY purposes without renditions are `purgeable`, upload limits only; `ArchitectureRulesTest`: those declarations only in the imports and memo modules |
| T2 | Integration | Rate limit (`LegacyFormatsIT`): about 600 intake uploads in a minute, then 429; the same person's other uploads keep their own 30 |
| T3 | Persistence/RLS | `memo/V19`: the CHECKs refuse a native memo without initiator, an imported memo with a workflow instance, a series, a document, `approved_at` or a non-final status; `memo_imported_guard` refuses status, reference and origin changes; the legacy tables refuse UPDATE and DELETE as `memo_app`; RLS forced on the four tables (`RlsMetaTest`); cross-tenant read, update and delete |
| T3 | Integration | `ImportedMemoIT`: `LegacyMemos.create` for each final status (CLOSED with `closed_at`), linked and unlinked people, participants under clearance (404 when not cleared), `memo.imported` in the same transaction with the file hashes; `MemoView.origin` and `legacy`; allowed actions; exclusions from Sent and Closed boxes, auto-close, the E04 backfill and approval listener, and throughput (`DashboardIT` extended) |
| T3 | Integration | `LegacyMemosCheckIT`: metadata checked without `requireAll`; subject length; type without a published version; number in use; `series()` patterns for each part kind |
| T3 | Contract | OpenAPI: `MemoView.initiator` optional, `origin` and `legacy` added; the web client regenerated; drift check green |
| T4 | Integration | `ImportedSearchIT`: Origin filter both ways; a departed initiator's and an approver's name find the memo; the date filter uses raised-on; an original's text is found at its effective rank and not by a reader below it; the `EXPLAIN` gate still passes with the new parameter |
| T4 | Integration | `LegacyFixityIT`: weekly run verifies legacy originals and audits a tampered one; `LegacyCorrectionIT`: each correctable field appends with before, after and reason; the subject correction reindexes; reclassify only upward, needs clearance to the new level, the permission, the read rule and a step-up |
| T4 | Matrix | `p3-e06-imports.yaml`: the two memo endpoints × subjects (readers without the permission 403, non-readers 404, no step-up refused) |
| T5 | Unit | `XlsxSheetReaderTest`, `CsvSheetReaderTest`: header matching (case, spaces, `-`, `_`), `field:` columns, unknown and duplicate columns, caps, dates (date cells in both systems, ISO text, nothing guessed), cached formulas, `NUMBER_NOT_TEXT`, delimiter detection, BOMs, strict UTF-8; `PathRulesTest`: NFC, `/`, `..`, control characters, length and depth, case-only pairs |
| T5 | Property | Paths: any string either normalises to a valid path that round-trips or is refused; never a path outside the folder |
| T5 | Fuzz (nightly) | Jazzer `ImportSheetFuzzTest` (targets `xlsx`, `csv` and, as built, `paths` in place of a separate `ImportPathFuzzTest`): no exception escapes the catch-all, no thread is lost, time per input bounded |
| T5 | Integration | `ImportBatchIT`: create, settings, list; source binding (workbook or CSV, not both); path registration (1,000 per call, duplicates, limits), listing by state, resume by size; intake binding and refusals recorded with the reason; template download (sheets, Text format, lists); the hostile corpus ends as batch problems; cross-tenant on every table and endpoint |
| T5 | Matrix | `p3-e06-imports.yaml`: every `/admin/imports` endpoint and the three purposes' uploads × subjects (403 without `memo.import`) |
| T6 | Integration | `DryRunIT`: business tables unchanged (counts and checksums) and SQLSTATE 25006 on a write in a validation transaction; every ERROR and WARNING code from a crafted workbook; path matching exact, case and ambiguous; cross-row checks (duplicates, in use, similar, shared content); the live-series check for current, past and future periods and unit-scoped counters; staleness after a source, original, mapping or setting change |
| T6 | Integration | `MappingIT`: proposals for each kind (status, type by code or name, unit by code, classification by name, person by email including a DEACTIVATED user; none by name); confirmation; `CLASSIFICATION_ORDER`; a batch default for blanks |
| T6 | State machine | Every allowed batch transition (§4.1) and every forbidden one (409), from each state |
| T7 | Integration | `ImportRunIT`: start with step-up and `expectedRows`; chunks; stop between chunks, resume with step-up, finish with NOT_STARTED rows; crash after promotion (job killed) imports once; stale IMPORTING row returned to READY; skip and conflict across batches; a check failing at import time rejects the row; a refused file fails the row; throttle at 200 pending renditions; nothing in workflow, tasks, notifications, watches or final records |
| T7 | Concurrency | `ImportConcurrencyIT`: two starts of one batch (one wins, the other 409); starts of two batches (`ANOTHER_IMPORT_RUNNING`); two job instances on one batch never import a row twice |
| T7 | State machine | Every allowed row transition (§4.2) and every forbidden one |
| T7 | Matrix | start and resume refused without a fresh step-up (`tenantAdminStepUp`); `IMPORTER_NOT_CLEARED` for an under-cleared holder |
| T8 | Integration | `ReconciliationIT`: balanced on the happy path; a tampered stored original, a missing file, a refused file and a stopped batch each move the right balance line (UNBALANCED, with the line named); complete and valid; the manifest's lines; the injection prefix for each trigger and full-width form; report parts stored as records with their hashes in `import.batch.completed`; report built after renditions settle, or at the 72-hour cap with pending stated; download needs clearance (403) |
| T8 | Integration | `ImportCloseIT`: close and abandon; the sweep abandons idle batches, closes 30 days after completion and purges staged objects; staging rows kept; audit |
| T8 | Golden | The report summary PDF rendered and compared (ADR-0052's golden tests) |
| T9 | Unit (web) | Batch list and new-batch form; stepper states and "needs a new dry run"; folder selection (system files skipped, too-large files flagged, paths normalised); upload queue (three at a time, pause, resume, retry, resume from the server list); mappings (proposals, confirm all, classification order and its error); problem list filters; start dialog with counts and the step-up; progress and throttled state; report view; imported memo sections; search Origin filter and badge; axe in English and Arabic |
| T9 | Visual (nightly) | Batch steps (metadata, originals in progress, mappings, problems, import progress, report), imported memo page, search with the filter: light and dark, LTR and RTL, phone |
| T10 | E2E (smoke) | `legacy-import.spec`: the journey in `tasks.md` T10 against the full stack, with the step-up and the report download; a reader finds the imported memo by a departed person's name; the memo shows no final record |
| T10 | Load (on demand, recorded) | `ImportVolumeIT` (`IMPORT_VOLUME=10000`): 10,000 memos, 30,000 originals (about 1.5 GB) on the dev VM: dry run < 5 min, import < 2 h with renditions excluded, the throttle engaged; plus a 50,000-row metadata-only parse and dry run; heap and database size recorded |

## Exit checklist
- [ ] Every test above green in its stage
- [ ] Matrix and audit coverage green with the new endpoints and actions (`import.*`, `memo.imported`, `memo.legacy.*`, `file.version.purged`)
- [x] T1 spike recorded in the log; ADR-0055 confirmed or amended (TIFF previews, parse limits, read-only check) (2026-11-16: amended)
- [ ] Hostile corpus (XLSX, CSV, OLE2) green nightly against clamd, Tika and ONLYOFFICE
- [ ] Jazzer targets `ImportSheetFuzzTest` (`xlsx`, `csv`, `paths`; T5) in the nightly set, with no open crash
- [ ] Import volume results recorded in the log with hardware and settings
- [x] Dependency register: Apache Commons CSV 1.14.1 (T5)
- [ ] ASVS rows (V1, V2, V5, V8, V14, V15, V16)
- [ ] Runbook `docs/runbooks/legacy-import.md` (rehearsal on a non-production tenant first, sizing the `work` bucket, throttling, stuck batches, fixity mismatch, the command-line uploader threshold)
- [ ] Phase demo script `docs/plan/phase-3-memo-efile/demo.md` (overview §5)
