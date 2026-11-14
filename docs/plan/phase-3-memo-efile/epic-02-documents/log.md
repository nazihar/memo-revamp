# E02 Documents — log

## 2026-11-14 — Plan
- **Research:** a research agent gathered sources on ONLYOFFICE 10, the conversion API, Tika 4, diff libraries, PDF.js and desktop Word (`research.md`). The lead checked the key facts directly: 10.0.0.1 was pushed on 2026-10-12, and all three deploy paths set `ALLOW_PRIVATE_IP_ADDRESS=true`.
- **Decisions:**
  - ADR-0045: renditions and text as background jobs, through ONLYOFFICE conversion and a separate Tika server.
  - ADR-0046: attachments with stricter-only classification and a per-round binding.
  - ADR-0047: ONLYOFFICE 10, edit rooms with session-bound keys, co-authors, spreadsheets and hardening. Desktop Word is not adopted. ADR-0037's key rule is amended.
- **Licence brief** for the owner's lawyer: `docs/legal/onlyoffice-licence-brief.md` (overview Q5c).
- **Gate:** delegated (Phase 3 Q1); E02 has no screen review on the owner's list. Planned and started the same day.
- **Found while planning:**
  - We switch off ONLYOFFICE's private-address filter today (`ALLOW_PRIVATE_IP_ADDRESS=true`). T1 replaces it with an allow-list.
  - Our Phase 2 key rule would split a co-editing session after a force-save. Not visible yet, because only the initiator edits.

## 2026-11-14 — T1 ONLYOFFICE 10.0.0.1 and hardening
- **Deploy:** 10.0.0.1 replaces 9.4.0.1 in the dev Compose, the on-prem Compose (dev VM) and the E2E stack. The Helm chart does not run the document server. Each path:
  - mounts `deploy/onlyoffice/local-production-linux.json`;
  - sets `PLUGINS_ENABLED=false` and drops `ALLOW_PRIVATE_IP_ADDRESS`;
  - puts the cache on a 2 GiB tmpfs.
  The deploy bundle ships the whole `deploy/` folder, so the file reaches the VM.
- **Override file:**
  - `externalRequest.action.allow: false`;
  - no AI providers or models, no browser AI proxy;
  - empty plugin autostart;
  - `expire.files` 3600 s, cleaned every ten minutes; sessions 12 h absolute and 1 h idle;
  - auto-assembly every 5 minutes;
  - conversion: visibility timeout 120 s, 100 MiB input, 100 MB uncompressed spreadsheets.
- **Proxy:** the web image's nginx refuses `/office/` conversion, command, DocBuilder, AI proxy, info, example and welcome paths with 403. A Vite middleware mirrors it for local runs and E2E. `security.spec` checks eight paths.
- **Editor config:** `coEditing {mode: fast, change: false}`, `macrosMode: disable`, `ai.disabled`, on top of plugins and macros off.
- **Spikes** (local Docker, 10.0.0.1 with the override):
  - It starts healthy with the file mounted at `/etc/onlyoffice/documentserver/local-production-linux.json`. Supervisor runs with `NODE_ENV=production-linux`, so node-config loads it after `local.json`.
  - `/converter/from-file` with a token carrying `operation: "converter"` returns the PDF (30 KB from a one-page DOCX); without the claim it answers `{"error":-8}`. Conversion to `txt` works for DOCX.
  - The tmpfs cache works.
  - Memory is 0.89 GiB idle.
  - Probing the raw server: `/docbuilder` and `/coauthoring/CommandService.ashx` answer (with token errors) and `/ai-proxy` answers 404. Hence the proxy denies.
  - Through the Vite preview, the denied paths answer 403 and editor assets pass through.
- **Outbound filtering:** verified by reading the 10.0.0.1 source (`Common/sources/utils.js`, `isAllowDirectRequest` and `addExternalRequestOptions`). With an empty allow-list and `jwtToken: true`, only URLs inside a signed token go direct (our document URL and callback); with `action.allow: false`, everything else throws "Block external request". The editor-side case (insert an image by URL) was not exercised locally; the nightly document-editing journey covers the editor flow on 10.0.0.1.
- **Research corrections:** `aiSettings.enable`, `docbuilderEnable` and `convertServiceEnable` are not in the 10.0.0.1 tag (`Common/config/default.json`). They come later. ADR-0047 and the design now name the settings that exist; the proxy covers the rest.
- **Backend:** the internal download already sends the exact `Content-Length` that 10.0's checksum check needs. The `docedit` tests pass.
- **Docs:** dependency register, `document-editing.md` hardening section, `nfr.md` memory.

## 2026-11-14 — T2 renditions
- **Model:**
  - `files/V2`: `files_rendition` per version and kind, with state, attempts, error code, encrypted object, size, hash, pages and a truncated flag. RLS forced; no deletes.
  - `FilePurpose.renditions` opts in; the memo document wants PDF and TEXT.
  - Public ports `RenditionConverter` and `TextExtractor`, and `FileRenditions` for owning modules.
- **Scheduling:** a `FileVersionCreated` listener records the rows in the upload's transaction and schedules one task per rendition, with instance id `<version>:<kind>`, so one rendition never runs twice at once.
  - A PDF original or an image needs no PDF rendition.
  - A type no service handles is `NOT_POSSIBLE` (`UNSUPPORTED`, or `NO_SERVICE` when the port is missing).
  - A recovery task every five minutes re-schedules pending rows older than five minutes.
- **Worker:**
  - waits for one of two slots per kind and node before it claims an attempt, so a busy node costs no attempt;
  - streams the decrypted original to the port and encrypts the port's output into a spool (rendition-specific associated data);
  - checks a PDF with `PdfInspector` (page limit);
  - stores the result in `work` under `t/<tenant>/r/<random>`.
  - Final errors fail at once and are audited (`file.rendition.failed`); transient ones are retried by the scheduler's backoff, up to four attempts.
- **ONLYOFFICE converter** (`docedit`, only when a document server is configured): `/converter/from-file`, async with a per-tenant, per-version key, the token carrying `operation: converter`, polling every two seconds up to 150 s.
  - Errors -2, -5, -7, -8, -9 and -10 are final; the others are retried.
  - HTTP 413 means size; an unreachable server is retried.
- **API:**
  - `GET /files/{id}/versions/{v}/renditions` (preview kind and states);
  - `GET …/renditions/pdf`: the rendition, or the original when it is a PDF, with ranges, sandboxed CSP and no sniffing.
  - Reads are audited as `file.opened` with `rendition`, separately from the original.
- **Tests:**
  - `RenditionIT` (7): done and read through the original's rules with audit and ranges; a final error; a retry; the attempt limit; an image; a PDF original; recovery; another tenant.
  - `OnlyOfficeConverterTest` (4): the multipart request, token claims, polling and error mapping, against a stand-in server.
- **Test infrastructure found:** test contexts share the scheduler table, so a task scheduled by one context may run in another. A plain context has no object storage and fails it. `FakeRenditionServices` is therefore in every test context, and `RenditionIT` runs the worker itself with `memo.files.rendition-jobs=false`.
- **Also fixed:** the new-memo page rendered an empty choice group before the types loaded. The E2E helper took the radio path and then waited for a radio that never came (nightly Firefox and WebKit, `memo-types.spec`). The chooser now waits for the types, and the helper waits for a choice or the list.
- **Nightly on the T1 branch:**
  - the document-editing journey passed on ONLYOFFICE 10.0.0.1 in Firefox and WebKit;
  - in WebKit only, every tenant-admin journey failed, unlike the master nightly. No artifacts were kept, so the cross-browser job now uploads its Playwright results on failure, and the next run will show why.

## 2026-11-14 — T3 text extraction
- **Service:** the unmodified Apache Tika server 4.2.0 (standard image) with `deploy/tika/tika-config.json`:
  - two forked parsers with a 1 GiB heap each;
  - tasks at most 120 s, 60 s without progress;
  - embedded documents at most 3 deep and 500 in all; zip-bomb ratio 100;
  - write limit 5,000,001 characters;
  - metadata limits;
  - per-request config and pipes off, and only the `/tika` endpoint.
- **Deploy:**
  - on-prem Compose: `tika` service in the `documents` profile, internal network only, 3 GB limit. Ansible sets `MEMO_TIKA_URL` for dev; re-run Ansible after merge so the VM's `.env` gets it.
  - dev Compose: `tika` plus a socat gateway on 9998.
  - E2E stack: Tika when `E2E_DOCUMENTS=1`.
  - Helm: `integrations.tikaUrl` (the chart does not run Tika, as with ONLYOFFICE).
  - Settings: `memo.files.tika-url`, `max-text-chars`, `rendition-concurrency`.
- **Client:** `TikaClient` (present only when the URL is set) sends `PUT /tika/text` with the bytes streamed and their type, keeps at most 5,000,000 characters and marks the rest as truncated.
  - 422 → `MALFORMED`, 413 → `SIZE`, 415 → `UNSUPPORTED`: all final.
  - 429 (every fork busy), 503 (a fork crashed) and network errors are retried.
- **Spike** (local, 4.2.0 with the config):
  - The config is used (log: `numClients=2`, heap user-set), and the other endpoints answer 404.
  - DOCX and PDF give their text; random bytes sent as DOCX, and an empty body, answer 422.
  - About 0.5 GiB idle.
- **`tika-core` 4.1.0 → 4.2.0** for upload detection, so detection and extraction agree.
- **Tests:** `TikaClientTest` (4) against a stand-in server: the request, truncation at the limit and exactly at it, status mapping, an unreachable server.
- **Not done here:** the hostile corpus is generated in tests and refused at upload, so none of it reaches the parsers. Upload inspection already bounds what can (OOXML expansion 100:1, PDF pages). A corpus aimed at the parsers themselves (deep XML, huge spreadsheets, malformed PDFs that pass inspection), run nightly against both services, moves to T8.

## 2026-11-14 — T4 attachments (backend)
- **Model (`memo/V10`):**
  - `memo_attachment`: memo, file, title, optional stricter level, position, added and removed by and at, version. Removed by flag, never deleted.
  - `memo_submission_attachment`: per round, the attachment, its file version and SHA-256. No updates or deletes for the app role.
  - No foreign keys to the files module, as with the other memo tables.
- **Purpose `memo.attachment`:**
  - the safe types (PDF, images, text and CSV, ZIP, OOXML, ODF), narrowed by the tenant setting `memo.attachments.types` (a list of extensions);
  - 50 MiB, records, reads audited, PDF and text renditions;
  - upload by the initiator while the memo is editable;
  - reading needs the memo and the attachment's effective level (the higher of its own and the memo's).
  - `FilePurpose.allowedTypesNow()` is the new hook the upload plan uses, inside the tenant transaction.
- **Adding** is an upload with the memo as owner. The listener records the attachment under the memo's row lock (still editable, at most 50) and audits `memo.attachment.added`.
- **API:**
  - `GET /memos/{id}/attachments`: restricted rows have no title, type, size or file;
  - `PATCH …/{attachmentId}`: title, stricter level or back to the memo's, position; If-Match. `NOT_STRICTER` and `above-clearance` are refused.
  - `DELETE …/{attachmentId}`: removes by flag.
  - `GET /memos/{id}/rounds/{n}/attachments`: what that round bound.
  - Audits: `memo.attachment.reclassified`, `.retitled`, `.removed`.
- **Submit and resubmit:**
  - the type version's `attachmentRules` (`ATTACHMENTS_REQUIRED`);
  - `DOCUMENT_BEING_EDITED` also when an attachment is open in the editor;
  - after the workflow starts, the round's binding is written.
- **Behaviour note:** an upload once the memo is not editable is refused by the purpose (403) before the listener's check (409). Contents of an attachment the reader is not cleared for are 403, as for any file a purpose does not let them open. ADR-0046 is reworded to match.
- **Tests:** `MemoAttachmentsIT` (5):
  - add and list, with audit;
  - stricter-only, retitle, a restricted row for an uncleared approver, content refused;
  - rules at submit;
  - locked during review, and both rounds' bindings after a return, removal and new version;
  - tenant type list, and another user cannot attach.

  Also: matrix `p3-e02-attachments.yaml`, audit coverage, contract.
- **Web** for attachments is T7.

## 2026-11-14 — T5 co-editing and co-authors (backend)
- **Co-authors:**
  - `memo/V11` adds the participant kind `COAUTHOR`. `MemoCoauthorStore` holds the queries.
  - `PUT /memos/{id}/coauthors {userIds}` is for the initiator of a draft: at most ten active people, never the initiator. It replaces the list and audits `memo.coauthors.changed` with before and after.
  - Co-authors read the memo (participants), upload versions of the document and attachments, open the editor, and change or remove attachments while the memo is editable. They cannot submit or change subject, details, route or classification (403).
  - `MemoView.coauthors`; a co-author's allowed actions are `UPLOAD_DOCUMENT` (with `EDIT_DOCUMENT` when editing is on).
- **Rooms (ADR-0047), simpler than designed:** a room is the live edit sessions sharing one key; there is no table.
  - Opening for editing joins the file's open, unexpired room when it is on the current version, taking its key and base. Otherwise a new key starts a room.
  - A force save stores the version as the user the callback names, when that user holds a session in the room (else the session's own user). It rebases every session in the room, which stays open.
  - A final save ends the room's sessions as `SAVED`; a close (status 4) ends them as `CLOSED`.
  - The upload's idempotency key names the room's key, so one save is stored once whichever member's callback URL it reaches.
  - `docedit.version.saved` records `savedBy` and the room's members. `docedit.session.opened` records whether the session joined a room.
  - The design and ADR-0047 are updated (no `docedit_room`, no `docedit/V2`).
- **Tests:** `DocEditIT` gains the room journey:
  - co-authors named; two editors share a key;
  - a force save naming the co-author stores a version as them and keeps the room;
  - a third co-author joins the same key; a stranger cannot open;
  - a co-author cannot submit; closing ends both sessions.

  The memo, docedit, authz, contract and tenancy suites pass (1,392 tests).
- **Flaky plan checks:** in the shared test database (thousands of tenants) the planner may pick the tenant index instead of `memo_number_uk` for the register page, still at 18 ms. `NumberingPerformanceIT` now asserts an index and no sequential scan. `OrgPerformanceIT` failed once the same way and passed alone; it is left as it is.
- **XLSX in the editor** (attachments): not in this task. The adapter's document type is fixed to DOCX; spreadsheets move with the web work in T7.

## 2026-11-14 — T6 comparison (backend)
- `GET /memos/{id}/files/{fileId}/compare?from&to`: the texts of two versions of the memo's document or of one of its attachments, each with number, name, SHA-256, uploader and time, and the text rendition's state.
  - The text is included only when `DONE`; `truncated` says when it was cut.
  - Readers of the memo may compare its document; an attachment also needs the reader's clearance for its effective level.
  - Anything else, unknown versions included, is 404. Both reads are audited as `file.opened` with `channel: compare`.
- The two texts are read outside the resolving transaction.
- `DocumentCompareIT`: pending state, texts after the worker runs, uploader and hash, audit, a stranger, an unknown file and an unknown version. Matrix entry.
- The browser side (jsdiff in a worker, side-by-side view) is with the web work in T7.
