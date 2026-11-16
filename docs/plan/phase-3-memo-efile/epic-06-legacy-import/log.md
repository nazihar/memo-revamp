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
