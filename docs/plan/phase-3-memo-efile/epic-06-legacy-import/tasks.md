# E06 Legacy import — tasks

> Step 5 of `AGENTS.md §5`. Vertical slices; each task is its own PR, stacked until the owner merges. E06 has no owner gate on screens (overview §10 Q1), so every task can start once its dependencies are done. Decision: ADR-0055; design: `design.md`.

| Task | Goal | Touches | Size | Depends |
|---|---|---|---|---|
| T1 | **Spike:** POI 6.0.0 streaming on `poi-ooxml-lite` (`XSSFReader`, `ReadOnlySharedStringsTable`, `StylesTable`, 1900 and 1904 dates, cached formula values) with time and peak heap on a 20 MiB workbook (50,000 memos, 200,000 files) and on a maximal shared-strings table; DTD and XXE refusal; Commons CSV 1.14.1 with the strict profile; the OLE2 path (clamd `AlertOLE2Macros` on macro `.doc`, `.xls`, `.ppt`; encrypted OLE2; the Tika server's `/detect/stream` for doc, xls, ppt, msg and encrypted; ONLYOFFICE to PDF for doc, xls, ppt, rtf and multi-page TIFF); whether `readInTenant` is really `READ ONLY` | spike tests, log; ADR-0055 amendment if a finding changes it | M | — |
| T2 | **Files: legacy formats and staging:** `FileType` DOC, XLS, PPT, RTF, TIFF; the purpose flag `acceptsLegacyFormats` (start-up check and architecture test); the Tika subtype for those purposes; ONLYOFFICE input types; `FileUploads.copyFrom(versionId, purpose, owner, key, expectedSha256)`; `files/V4` purge (`purged_at`, `purgeable` purposes, 410 on download, `file.version.purged`); per-purpose rate limit (`IMPORT_INTAKE_PER_USER`); `FileRenditions.pendingCount(purpose)`; `UploadRejected` event | files, docedit (converter types), common (rate limits) | M | T1 |
| T3 | **Imported memos in the model:** `memo/V19` (origin, scoped CHECKs, nullable initiator, `memo_imported_guard`, the four legacy tables, append-only grants); purpose `memo.legacy`; `LegacyMemos` (`check`, `create`, `existing`, `series`); participants for linked people; `memo.imported` audit; `MemoView.origin` and `legacy`, optional `initiator`; exclusions (boxes, auto-close, E04 backfill and approval listener, dashboard throughput); permission `memo.import` | memo, authz (catalogue), dashboards | L | T1 |
| T4 | **Imported memos in search, records and corrections:** `search/V3` (origin columns, nullable initiator, functions replaced) and the Origin filter in `POST /search`; facts for imported memos (raised-on date, legacy names in the text); legacy originals in `MemoSearchSource.files`; `RecordFixity` over `memo_legacy_file`; `POST /memos/{id}/legacy/corrections` and `…/reclassify` with step-up | search, memo | M | T3 |
| T5 | **Batches, intake and the readers:** module `imports` and `imports/V1`; purposes `imports.source`, `imports.intake`, `imports.record`; batch create, view, settings, list; source binding; path registration, listing and resume; the template download; the XLSX and CSV readers on the parse pool with every cap; Commons CSV in the catalogue and the register; the Jazzer target `ImportSheetFuzzTest` | imports (new), files (purposes), dependency register | L | T2 |
| T6 | **Dry run and mappings:** the `imports-dry-run` job (stage, group and match, propose, validate); path matching; mappings with proposals, confirmation and the classification order; the live-series check; `READ ONLY` validation transactions; input hash and staleness; problems and rows APIs with filters; matrix | imports, memo (`LegacyMemos.check`, `series`) | L | T3, T5 |
| T7 | **The import:** start (step-up, `expectedRows`, clearance), stop, resume, finish; the `imports-run` job (chunks, throttle, pre-check, `copyFrom` promotion, one transaction per row, skip and conflict, retries, stale-row recovery); one running import per tenant; audit | imports, memo (`LegacyMemos.create`), files | L | T2, T6 |
| T8 | **Verification, report and closing:** `imports-verify`; `imports-report` (PDF through `ReportPages`, XLSX with the injection prefix, manifest, sources as records, balance, complete and valid); `GET …/report` with clearance; close and abandon; `imports-sweep` (stale rows, idle batches, purge after 30 days) | imports, memo (`ReportPages`), files | M | T7 |
| T9 | **Web:** Administration → Import (list, new batch); the batch stepper (metadata, folder upload with progress, pause and resume, mappings, dry-run problems with filters, start with step-up, progress with stop, report); the imported memo page (banner, legacy details, approval history, originals, corrections, no final record); the Origin filter and badge in search; i18n (English, Arabic); visual baselines | web | L | T4, T8 (the batch and dry-run screens may start after T6) |
| T10 | **Close:** journey `legacy-import.spec`; the synthetic legacy sample (`SyntheticLegacy`: a folder with docx, pdf, doc, xls, tiff, a zip and messy rows) seeded as an imported demo batch; `ImportVolumeIT` at the stated volume; runbook `legacy-import.md`; ASVS rows; the phase demo script `demo.md`; STATUS and log | all | M | all |

**Order:** T1 first (it may amend ADR-0055: TIFF previews, the parse limits, the read-only check). T2 and T3 can run in parallel; T4 follows T3; T5 follows T2; T6 needs T3 and T5; T7 → T8. T9 can start its batch and dry-run screens after T6 and finishes after T4 and T8. T10 is last.

## Acceptance criteria (selection; the rest in `test-plan.md`)
- **T1:**
  - given a 20 MiB workbook with 50,000 memo rows and 200,000 file rows, the event reader parses it within 120 s and the peak heap is recorded; if it exceeds 512 MB, the log says so and the caps are lowered in ADR-0055;
  - given a workbook with a DTD or an external entity in `sharedStrings.xml`, parsing fails as `MALFORMED` and no entity is resolved;
  - given a `.doc`, `.xls` and `.ppt` with VBA macros, clamd answers `Heuristics.OLE2.ContainsMacros`; given the same without macros, the Tika server names each subtype, and ONLYOFFICE converts each to PDF; the results for `.msg`, encrypted OLE2, RTF and multi-page TIFF are recorded and decide TIFF previews.
- **T2:**
  - given a macro-free `.doc` uploaded to `imports.intake`, it is stored as DOC; given the same file uploaded to `memo.attachment`, it is refused `TYPE_NOT_ALLOWED`;
  - given an Outlook `.msg` or an encrypted `.doc` for `imports.intake`, it is refused (`TYPE_NOT_ALLOWED`, `ENCRYPTED`); given the Tika server down, the OLE2 upload fails closed with 503;
  - given `copyFrom` with an expected SHA-256 that differs, nothing is stored and `HASH_MISMATCH` is returned;
  - given a purge of an `imports.intake` version, the object is gone, the row keeps its hash, a download answers 410 and `file.version.purged` is audited; a purge of a record version is refused;
  - given 601 intake uploads in a minute, the 601st is 429, while `memo.attachment` keeps its 30 a minute.
- **T3:**
  - given `LegacyMemos.create` for an APPROVED row with an unlinked initiator, the memo has `origin = IMPORTED`, a null `initiator_id`, the legacy number as reference, no series, no workflow instance and no `approved_at`, and `memo.imported` is in the same transaction;
  - given a native memo inserted with a null initiator or an imported memo with a workflow instance, the database refuses it;
  - given an update to an imported memo's status, reference or legacy rows, the database refuses it;
  - given a linked approver, they can read the memo when cleared and get 404 when not;
  - given imported APPROVED and CLOSED memos, auto-close, the final-record backfill, the initiator's Sent box and the dashboard's throughput ignore them.
- **T4:**
  - given an imported memo whose legacy initiator has left, searching their name finds it; the Origin filter `IMPORTED` returns only imported memos, and the date filter uses the raised-on date;
  - given a tampered stored original, the weekly fixity run audits `memo.legacy.mismatch`;
  - given a correction of `RAISED_ON`, the legacy row is unchanged and the correction shows with its reason; given a reclassification to a lower level, 422 `CLASSIFICATION_NOT_HIGHER`.
- **T5:**
  - given a holder of `memo.import`, they create a batch, upload a workbook and register 1,000 paths in one call; given someone without it, 403 on every endpoint;
  - given an intake upload with the owner of another batch in a non-OPEN state, it is refused;
  - given a reload, the intake list returns the paths already received with their sizes, so the client sends only the rest;
  - given a CSV in Windows-1252 or with a UTF-16 BOM, `NOT_UTF8`; given a duplicate or unknown column, the batch problem names it;
  - given the hostile corpus, every file ends as a batch problem and no worker thread dies.
- **T6:**
  - given a dry run, every business table's row count and checksum are unchanged, and a write inside its validation transaction fails with SQLSTATE 25006;
  - given an in-flight status, a number already used, a number a published series could still produce, a file not received and an unconfirmed mapping, each row is REJECTED with its code and the others are READY;
  - given a classification mapping that lowers a stricter legacy value below a less strict one, saving is refused with `CLASSIFICATION_ORDER`;
  - given a mapping saved after the dry run, the batch is OPEN and start is refused until a new dry run.
- **T7:**
  - given a start without a fresh step-up, 401 or 403 as for other step-up actions; with it, the batch imports;
  - given a crash after a row's files were promoted but before its commit, the resumed job imports the row once, reusing the stored versions;
  - given a second batch with the same row unchanged, it is SKIPPED; changed, it is REJECTED `ALREADY_IMPORTED_CHANGED`;
  - given 200 pending `memo.legacy` renditions, the job waits and the batch shows it is throttled;
  - given two starts at once, or a start while another batch imports, exactly one batch runs (`ANOTHER_IMPORT_RUNNING`);
  - given an import, no task, notification, watch, workflow action or final record is created.
- **T8:**
  - given a completed batch, the report balances, the manifest lists every stored original with its SHA-256, and `import.batch.completed` carries each part's hash;
  - given a legacy subject `=HYPERLINK("x")`, the XLSX cell is the string `'=HYPERLINK("x")`;
  - given a reader without clearance to the batch's highest level, the report download is 403;
  - given a closed batch, its staged objects are purged and its staging rows remain.
- **T9:**
  - given a folder of 1,000 files, the upload shows files and bytes done, pauses, resumes, and after a reload resumes by choosing the folder again;
  - given a dry run with problems, the list filters by level, code and sheet and shows the sheet row number;
  - given an imported memo, the page shows the banner, the legacy approval history labelled as not verified, the originals, and no final record box;
  - axe passes in English and Arabic; paths and numbers read left to right in Arabic.
- **T10:**
  - given the synthetic sample, the journey creates a batch, uploads the workbook and the folder, fixes a mapping, runs the dry run, imports with a step-up, downloads a balanced report, and finds an imported memo by a departed person's name;
  - given 10,000 memos with 30,000 originals, the import finishes within the budget on the dev VM, and the result is in the log with hardware and settings.
