# E06 Legacy import — log

## 2026-11-16 — Plan written
- **Research:** R11 (spreadsheet formats, archive intake and fixity, mapping legacy statuses and people, dry runs, idempotency and reconciliation reports in records migrations) and R1 for what E06 adds, by a research agent, checked against the code by the lead (`research.md`). Sources include POI's security page, Commons CSV, RFC 4180 and RFC 8493 (BagIt), the IETF idempotency-key draft, TNA's migration guidance, ISO 15489-1 and 16175-1, MoReq2010, Microsoft SPMT and SharePoint migration guidance, AWS DMS validation, Alfresco's bulk importer and SAP's Migration Cockpit.
- **Main findings:**
  - A dry run must be unable to write (SAP KBA 3782784) and must check content as well as metadata (Alfresco's does not).
  - Excel corrupts legacy numbers and dates on open, so the template is an XLSX with Text columns, and CSV is accepted only as strict UTF-8.
  - Server-side unzipping cannot meet "scan before encryption" at migration sizes (100 MiB upload ceiling, clamd's 110 MB stream limit), so the browser sends a picked folder file by file.
  - Migration tools that fall back to the migrating account, or invent placeholder users, falsify authorship; in-flight workflows are not migrated by the major tools.
- **ADR-0055** (legacy import) is accepted under the delegated gate. It amends ADR-0029 and ADR-0046 for the two legacy purposes only (OLE2 `.doc`, `.xls`, `.ppt`, plus RTF and TIFF), with short amendment notes added to both.
- **Design, threat model, tasks T1–T10, test plan and the screen flows (L–O) written.** E06 has no owner gate (overview §10 Q1); the ten decisions taken under the delegation are listed in `ux-review.md`, and the planning choices beyond the research in `design.md` §17.
- **Departures from the research,** after checking the code:
  - **Permission name:** one key, `memo.import`, not `memo.import.manage`.
  - **Approvals** are a third table, so CSV is three files (the research's conclusions said two; its own template had an Approvals sheet).
  - **People** are linked by email or a mapped legacy id only: the directory holds no employee numbers.
  - **Search** needs more than the memo CHECKs: `search_memo.initiator_id` is `NOT NULL`, and each search part filters on its own table, so `origin` goes on all three search tables (`search/V3`).
  - **Dashboards:** throughput counts memos by `created_at`, so imported memos are excluded explicitly; cycle time already excludes them (it reads workflow instances).
  - **Auto-close and E04's backfill** would pick up imported APPROVED and CLOSED memos: imported memos keep `approved_at` null and both filter on `origin`.
  - **The dry run's read-only transaction** is explicit (`SET TRANSACTION READ ONLY`): `TenantTransactions.readInTenant` sets only Spring's read-only flag, which the driver may not enforce. T1 checks.
  - **OLE2 detection:** the in-process detector sees only a generic OLE2 container (`application/x-tika-msoffice`); the subtype comes from the Tika server inside the pipeline for the two legacy purposes, and `.msg` is refused.
  - **Staged copies:** the files module has no deletion, so T2 adds purging of ordinary, purgeable versions (`files/V4`, `purged_at`).
  - **Promotion** refuses a hash mismatch before storing (`copyFrom` with the expected SHA-256), instead of comparing after a locked record exists.
  - **Upload rate:** the general limit (30 a minute) would make a 25,000-file intake take 14 hours; intake gets its own (600 a minute).
  - **Classification "never less strict"** is enforced through a legacy order the admin gives, since the system cannot know legacy strictness otherwise.
  - **The report** is stored once, when renditions have settled (or after 72 hours), not regenerated while they are pending, so no locked record versions pile up.
  - **Audit names** use the module prefix: `import.batch.completed` instead of `memo.import.completed`.
- **Spike questions for T1:** POI's event API on `poi-ooxml-lite` (all classes present?) with time and heap at the caps; DTD refusal; Commons CSV's strict profile; clamd on macro OLE2; Tika's subtypes; ONLYOFFICE conversion of doc, xls, ppt, rtf and TIFF; whether `readInTenant` is really read-only.
- **New dependency:** Apache Commons CSV 1.14.1 (Apache-2.0, 2025-07-27), added to the register in T5. Nothing else new is linked.
- **Questions for the owner:** none.

## 2026-11-16 — T1 spike: workbooks, CSV, legacy formats, read-only dry run
- **Ran:** `ImportSheetSpikeIT`, `LegacyFormatsSpikeIT` and `ReadOnlyTransactionSpikeIT`, all passing, on the dev laptop against local containers: clamd 1.5.4 (`_base` with the test signatures and `deploy/clamav/clamd.env`), Tika 4.2.0 and ONLYOFFICE 10.0.0.1. They are gated by `MEMO_SPIKE_*` and are not in CI. The macro, message and encrypted samples are Apache POI's public test files, fetched at run time and not committed.
- **Workbooks (POI 6.0.0 streaming):**
  - 50,000 memo rows and 200,000 file rows (15 MiB, unique text) parse in 2.5 s, with about 150–225 MiB peak heap above the baseline. That is well inside the 120 s limit. The 20 MiB file cap and the row caps stand.
  - **1904 dates:** POI's `XSSFSheetXMLHandler` formats dates in the 1900 system only. A 1904 workbook's 2019-03-14 reads as 2015-03-13. A `DataFormatter` that applies the workbook's system fixes it (ADR-0055 amended).
  - **Formulas:** a cached value comes through; a formula with no cached value comes through as no cell, so it is reported as missing.
  - **DTDs:** a DOCTYPE in `sharedStrings.xml` or in a sheet is refused by POI's own XML reader ("DOCTYPE is disallowed"), and no entity is read.
- **CSV (Commons CSV 1.14.1, strict profile):**
  - Refused: a duplicate or missing header name, invalid UTF-8 (`MalformedInputException`), an unclosed quote at the end, and data after a closing quote.
  - A BOM and `;` are read.
  - `setMaxRows` stops reading at the cap.
  - A 10,000,000-character field reads in 55 ms. There is no per-field cap in the library, so the 20 MiB file cap is the bound.
- **Legacy formats:**

  | File | clamd | Tika `PUT /detect` | ONLYOFFICE to PDF |
  |---|---|---|---|
  | .doc, .xls, .ppt without macros | clean | msword, ms-excel, ms-powerpoint | about 1 s each |
  | .doc, .xls, .ppt with VBA | `Heuristics.OLE2.ContainsMacros.VBA` | as above | converts (refused earlier by clamd) |
  | encrypted .doc and .xls | `Heuristics.Encrypted.OLE2` | as above | -5 |
  | encrypted .ppt (CryptoAPI) | **clean** | ms-powerpoint | -5 |
  | Outlook .msg | clean | `application/vnd.ms-outlook` | -7 |
  | .rtf (made by ONLYOFFICE) | clean | `application/rtf` | about 1 s |
  | 3-page TIFF | clean | `image/tiff` | **-7** |

  - **Tika:** detection is `PUT /detect` (there is no `/detect/stream` in 4.2), and only when the config enables the `detect` endpoint; ours enables `tika` only, so T2 adds `detect`. Tika's text endpoint answers 422 for both encrypted files.
  - **ONLYOFFICE** cannot write the old binary formats (-3), which does not matter here.
- **Read-only dry run:** `readInTenant` reaches PostgreSQL as `READ ONLY` (`transaction_read_only` is on; a write fails with SQLSTATE 25006). An explicit `SET TRANSACTION READ ONLY` gives the same result. The design keeps `readInTenant` and a test.
- **Changed by the spike (ADR-0055 amended, design §6 and §17):**
  - TIFF is download-only;
  - encrypted PowerPoint is caught in the dry run;
  - the Tika config gains `detect`;
  - the macro signature carries `.VBA`;
  - the reader applies the workbook's date system;
  - no explicit `SET TRANSACTION`.
- **Dependency:** Commons CSV 1.14.1 (Apache-2.0, 2025-07-27, the latest release) is in the build for tests; T5 makes it a runtime dependency and adds the register row.

## 2026-11-16 — CI stall, caught by the watchdog
- The backend job on #131 reached its 30-minute limit again. This time the stall watchdog (E05 T8) left three dumps. The suite was in `SearchPerformanceIT` (100,000 memos) for over 21 minutes, against about 4 minutes normally, and the test worker was waiting in a socket read on PostgreSQL.
- The dumps cut each stack at eight frames (`ThreadInfo.toString()`), above the waiting call. The watchdog now writes every frame, plus the shared database's activity: running statements, their wait events and `pg_blocking_pids`. The next stall will name the statement and what blocks it.
- **Cause, found while building T2:** in a full local suite, `SearchVolume`'s seed insert into `search_chunk` ran at 100% CPU for over 30 minutes; alone, the class passes in about 5 minutes. The insert joined a subquery that makes each chunk's random 200-word text to `search_memo`, whose statistics were stale until the `analyze` at the end. With other tests' rows in the shared database, the planner chose a nested loop that ran the per-row text again for every memo. The notes insert had the same shape.
- **Fix:** both texts are made once into temp tables, which are analysed with `search_memo` before the join. The plan no longer changes how often the text is made. `SearchPerformanceIT` passes and generates 100,000 memos in 26 s locally. The watchdog stays, to name any other stall.
- **It came back on the fix's own run:** the notes insert, now a hash join between two analysed tables locally, ran on CPU for over 12 minutes on CI, and then the participants insert ran long. So the plan of the text subquery was not the whole cause. Two more aids for the next occurrence:
  - `SearchVolume` writes each insert's plan to `build/test-stalls/` before running it, and its statements time out after 8 minutes (60 for the weekly V1 run). A runaway statement then fails the class with its plan on record, instead of the job reaching its limit.
  - The watchdog's database section now also lists the twelve largest tables, with live and dead rows, size and last analysis.

## 2026-11-16 — T2: files, legacy formats and staging
- **Built (files module, with docedit and common):**
  - **Legacy types:** `FileType` gains DOC, XLS, PPT (`application/msword`, `vnd.ms-excel`, `vnd.ms-powerpoint`), RTF and TIFF (`.tif`, `.tiff`); `FileType.LEGACY` names the five.
  - **The purpose flag:** `FilePurpose.acceptsLegacyFormats`. The start-up check (`Purposes.verify`) lets only `imports.intake` and `memo.legacy` set it, and refuses a purpose that allows a legacy type without it. `ArchitectureRulesTest` keeps the declarations in the imports and memo modules.
  - **Subtype detection:** `TikaDetector` asks the Tika server's `PUT /detect` which document is in an OLE2 container, only for those purposes, inside the inspection's time limit. Word, Excel and PowerPoint are allowed. An Outlook message or any other container is `TYPE_NOT_ALLOWED`; encrypted OOXML in its OLE2 wrapper is `ENCRYPTED`. No server, or one that cannot answer, refuses the upload as `SCAN_UNAVAILABLE` (503 with `Retry-After`). `deploy/tika/tika-config.json` enables `detect` beside `tika`; it is the only Tika config (Compose, on-prem Compose, Ansible and the E2E stack all mount it; Helm points at an external server).
  - **clamd verdicts:** unchanged code. A macro OLE2 file (`Heuristics.OLE2.ContainsMacros.VBA`) already maps to `MALWARE` with its signature in the audit, and `Heuristics.Encrypted.OLE2` to `ENCRYPTED`, for every purpose.
  - **Renditions:** ONLYOFFICE converts doc, xls, ppt and rtf to PDF, and Tika extracts their text. TIFF has neither (both `NOT_POSSIBLE`): download only.
  - **`FileUploads.copyFrom(versionId, purposeKey, ownerId, idempotencyKey, expectedSha256)`:** streams a stored version's plaintext back through the whole pipeline (a new scan, detection and inspection under the target purpose, encryption, storage) under its stored name. A different SHA-256 is refused as `HASH_MISMATCH` after the scan and before inspection or storage. Idempotent on the key; not rate limited; throws `UploadRefusedException(reason)`.
  - **Purging (`files/V4__purge.sql`):** `files_version.purged_at` and `purged_by`, a CHECK that only unlocked `work` versions are purged, and the append-only guard extended to let them be set once. `FilePurges.purge(versionId)` marks and audits `file.version.purged` (file, purpose, owner, size, SHA-256) in one transaction, then deletes the object; a repeat answers false and deletes again. `purgeable` purposes must be ORDINARY with no renditions (start-up check). Downloads of a purged version answer 410 (`reason: PURGED`).
  - **Upload rate:** `RateLimit.IMPORT_INTAKE_PER_USER` (600 a minute), chosen by a purpose through `FilePurpose.uploadRateLimit`; everything else keeps `UPLOAD_PER_USER` (30).
  - **Pending renditions:** `FileRenditions.pendingCount(purposeKey, atMost)` (stops counting at `atMost`, for the import's throttle) and `pendingCount(versionIds)` (for the report's wait).
  - **`FileUploadRejected` event** (`tenantId`, `purposeKey`, `ownerId`, `fileId`, `idempotencyKey`, `reasonCode`, `signatureName`; named so `EventPayloadRulesTest` reads them as ids and codes), published in the transaction that audits `file.upload.rejected`, except for `SCAN_UNAVAILABLE`.
  - **Docs:** design §3.3, §5.1 and §5.3 say what was built; ADR-0045 amended (the `detect` endpoint, legacy renditions, TIFF); ADR-0029's amendment note corrected (`PUT /detect`, `.VBA`).
- **Decided while building:**
  - **The event is `FileUploadRejected`,** not `UploadRejected`: the files module's internal exception already has that name. It also carries the file id, the idempotency key and the clamd signature, so the imports module can match an intake upload and the report can name the signature.
  - **Tika down is `SCAN_UNAVAILABLE`,** not a new reason: same meaning (the file cannot be checked now), same 503 and `Retry-After`, and the web already shows it.
  - **Only the Tika server names a .doc, .xls or .ppt.** Tika's magic also names Word for DOS and early Excel files without a container; those are refused everywhere.
  - **`copyFrom` is not rate limited:** the import job is not a person, and 30 a minute would make a 30,000-file import take 17 hours. It reads the source without an access check, as `FileContents` does (the caller decides). A staged copy whose ciphertext fails authentication cannot be the expected content, so it is `HASH_MISMATCH` too.
  - **Purge marks first, then deletes:** a crash in between leaves a marked version whose object the next purge deletes, never an unmarked row without content.
  - **A purgeable purpose has no renditions,** because purging does not delete rendition objects. The staging purposes have none.
  - **The test purpose is keyed `imports.intake`** (`TestIntakePurpose` in `TestFilePurposes.kt`), because only the two legacy keys may accept legacy formats. **T5 must delete it** when it registers the real purpose (a duplicate key stops the start).
  - **A stand-in for the Tika server's `/detect`** (`TestTikaServer`, an in-JVM HTTP server that types an OLE2 file by its entries) runs in every `FilesIntegrationTest` context instead of a Tika container. It adds no Spring context, and `down` switches it off at run time. The real server's behaviour is the T1 spike's; `TikaDetectorTest` checks the client and that the config enables `detect`.
- **Tests** (the T2 rows of `test-plan.md`):
  - `LegacyFormatsIT` (6), `StagingIT` (6), `TikaDetectorTest` (7), `PurposesTest` (4), `FileTypeTest` (2);
  - extended: `ContentInspectorTest` (12), `ClamdReplyTest`, `TikaClientTest`, `OnlyOfficeConverterTest`, `ArchitectureRulesTest` (14);
  - unchanged and still green: `UploadIT`, `HostileFileCorpusIT`, `EventPayloadRulesTest`, `AuditActionCoverageTest` (`file.version.purged`), `JooqCodegenTest`, `OpenApiContractTest` (no endpoint changed).
  - The clamd test image flags POI-written samples as the real ones: an HSSF workbook encrypted with `Biff8EncryptionKey` is `Heuristics.Encrypted.OLE2`, and a compound file with a `Macros/VBA` project is `Heuristics.OLE2.ContainsMacros`. Nothing third-party is committed (`LegacySamples`).

## 2026-11-16 — T3: imported memos in the model
- **Built (memo module, with authz and web):**
  - **`memo/V19__legacy.sql`:** `memo.origin` (`NATIVE` by default, or `IMPORTED`); `initiator_id` nullable for imported memos only (`memo_initiator_native`); V1's unnamed "workflow exactly past DRAFT" CHECK found by its definition and replaced by `memo_workflow_by_status`, scoped to native memos; `memo_imported_shape` (final status, a reference, no series, workflow, round, document or `approved_at`, a pinned type version); the guard `memo_imported_guard`; the partial index `memo_tenant_imported_idx`; the tables `memo_legacy` (unique row key `(tenant_id, source_code, legacy_number_key)`), `memo_legacy_approval`, `memo_legacy_file` (one MAIN per memo) and `memo_legacy_correction`, each with RLS forced and `UPDATE`, `DELETE` and `TRUNCATE` revoked from `memo_app`. jOOQ regenerated (the guard is excluded like the others).
  - **`LegacyMemos`** (package `com.memo.memo`, implemented by `internal.legacy.LegacyMemoService`): `check(row)` (reads only), `create(row)` (in the caller's transaction), `existing(source, number)` and `series()`. `create` writes the memo, its legacy facts, approvals as history and originals, makes linked people participants (INITIATOR, APPROVER), publishes `MemoChanged` and `MemoFileChanged`, and audits `memo.imported` (batch, source, number, row, fingerprint, each original's file, version and SHA-256, the mapped type, status, level and unit, the number of linked people, `startedBy`). It creates no workflow, task, stamp, number, watch or notification. A closed memo's `closed_at` is the start of its legacy closing day in the batch's time zone.
  - **`LiveSeries`:** each published type version's format as a pattern, the period counted in now and the series' counters, with `mayStillGive(number)` for T6's `NUMBER_IN_LIVE_SERIES`.
  - **Purpose `memo.legacy`** (`MemoLegacyPurpose`): record, legacy formats, the attachment types plus TIFF, DOC, XLS, PPT and RTF, 100 MiB, PDF and text renditions, stored only by `System("legacy-import")`, read by the memo's readers cleared to the original's effective level. T2's start-up check accepts it.
  - **Permission `memo.import`:** privileged, not scopable, in `GRANTED_ON_PURPOSE`; English and Arabic labels in the web's access catalogue.
  - **`MemoView`:** `origin`, an optional `initiator`, and `legacy` (`LegacyMemoView`); the contract and the web client regenerated; the memo page and co-author panel accept a missing initiator.
  - **Exclusions:** the initiator's boxes, `MemoClosing.due` and the closing store, the final-record backfill (`MemoRenditionQueries.missing`), the workflow listener, the dashboard's "created" throughput, note-sheet comments and links from the memo all leave imported memos out. Closing one by hand is 409 `IMPORTED`.
- **Decided while building:**
  - **Search waits for T4.** `search_memo.initiator_id` is still `NOT NULL`, so the memo module answers no search facts for an imported memo until `search/V3`. The events are already published; T4's backfill indexes anything imported before.
  - **The guard compares whole rows** (`to_jsonb(NEW) - mutable` against `OLD`), so a column added later is locked on imported memos without changing the guard.
  - **The view names the batch by id,** and shows people by name and linked user only. The memo module does not read the imports module's batches; legacy emails and ids stay in the facts for the audit and the report.
  - **No `CORRECT_LEGACY` or `RECLASSIFY` yet:** they come with their endpoints in T4. An imported memo's `allowedActions` is empty apart from `ADD_COPIES`.
  - **Retention of originals is the tenant's default at promotion,** because the memo does not exist when its originals are stored (design §10, as built). T7 extends them to the type's years after the row commits.
  - **More codes:** `PERSON_UNKNOWN` (a linked id that is no user), `VALUE_INVALID` (a bad source code, row number or approval order), `FILE_NOT_RECEIVED` from `create` (an original that is not the memo's stored legacy file), and `ALREADY_IMPORTED` or `NUMBER_IN_USE` when a concurrent import took the key or reference (only unique violations are mapped; anything else is a bug and propagates).
  - **A unit is current when it exists:** the org directory has no open or closed state for units.
  - **The demo grant of `memo.import`** waits for T5, where the import endpoints arrive.
- **Tests** (the T3 rows of `test-plan.md`):
  - `ImportedMemoIT` (9): each final status, CLOSED with `closed_at`; unlinked and linked people; readers under clearance (initiator, approver, unit reader) and 404 for an uncleared approver, a stranger and the importer; a restricted original; the view (`origin`, `legacy`, no actions, `canLink` false, rendition NONE); `memo.imported` in the same transaction (a crash after `create` leaves neither memo nor audit); the CHECKs and the guard; append-only legacy tables as `memo_app`; the unique row key and `existing`; auto-close, backfill, boxes, throughput, closing, notes and links; another tenant neither reads nor writes the legacy rows.
  - `LegacyMemosCheckIT` (5): field values without `requireAll`; lengths, type, level, unit, source code, row number and a number in use; dates, approvals, originals and linked people; `create` refusing a foreign original; `series()` patterns for the default yearly format, a per-unit financial-year format and a never-resetting one, with periods and counters.
  - Updated: `JooqCodegenTest` (exclusion), `OpenApiContractTest` (contract regenerated).
