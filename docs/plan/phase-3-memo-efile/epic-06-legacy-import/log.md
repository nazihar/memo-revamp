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
