# E02 Documents — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **ONLYOFFICE 10.0.0.1 and hardening:** image in every deploy path and the E2E stack; `local.json` override (`externalRequest` allow-list, `action.allow: false`, AI off, DocBuilder off, `expire`, `autoAssembly`, `inputLimits`, visibility timeout); `PLUGINS_ENABLED=false`; tmpfs cache; limits; `/office/` proxy denies; editor config flags (macros, plugins, AI, `coEditing.change: false`); exact `Content-Length` on the internal download; spikes recorded (editor flow, outbound refused, `/ai-proxy` 403); dependency register; runbook | M | — |
| T2 | **Renditions:** `files/V2`; `FilePurpose.renditions`, `allowedTypesNow`; ports `RenditionConverter`, `TextExtractor`; the job and the recovery job, semaphores, retries, final codes; ONLYOFFICE from-file conversion in `docedit` (token with `operation`); PDF check; encrypted storage in `work`; read endpoints and audit; memo document opts in | L | T1 |
| T3 | **Text extraction:** Tika 4.2.0 service in every deploy path and the E2E stack, its config with the limits, `TikaClient`; `tika-core` 4.2.0; the hostile-file corpus run against both services (nightly) | M | T2 |
| T4 | **Attachments:** `memo/V10`; `MemoAttachmentPurpose` (types by tenant setting, editable DOCX and XLSX); add, retitle, reclassify, reorder, remove; effective classification on every read; rules at submit; per-round binding and its read endpoint; history shows the bound list; matrix `p3-e02-attachments.yaml` | L | T2 |
| T5 | **Co-editing:** `docedit/V2` rooms; join, force-save, close, stale saves, membership of the saver; co-authors (`COAUTHOR` participants, `PUT /memos/{id}/coauthors`), their rights; submit waits for any open room on the document or attachments | M | T1, T4 |
| T6 | **Comparison:** compare endpoint with audit; web worker with jsdiff (`diff` 9.0.0), side-by-side view, budget and fallback | M | T3 |
| T7 | **Web:** Attachments section (upload with classification, list, restricted rows, rendition states, preview dialog with PDF.js 6.4.299 in a same-origin worker, `useWasm: false`, image preview, edit XLSX/DOCX, remove, reorder); co-authors picker; compare view; submit messages; Arabic; axe; visual baselines | L | T4, T5, T6 |
| T8 | **Close:** E2E journeys (attachment with stricter classification seen as restricted by an uncleared approver; preview; compare two versions; two co-authors in one room); demo seed (attachments on demo memos); performance checks of §9; ASVS rows; runbooks (documents, attachments, Tika); licence brief handed over; STATUS and log | M | all |

**Order:** T1 → T2 → T3 and T4 (either order) → T5 → T6 → T7 → T8. Each task is its own PR, stacked until the owner merges.

## Acceptance criteria (selection; the rest in `test-plan.md`)
- **T1:**
  - Given ONLYOFFICE 10.0.0.1, when a user opens, edits and closes the memo document, then a new version is stored as in Phase 2.
  - Given a document that inserts an image from `http://169.254.169.254/`, then the document server refuses the request.
  - `GET /office/ai-proxy` answers 403.
  - `GET /office/converter` through the proxy answers 403.
- **T2:**
  - Given a DOCX upload, then a `PDF` rendition reaches `DONE` with pages and hash, and reading it is audited.
  - Given ONLYOFFICE error -10, then the rendition is `FAILED` with code `SIZE` and is not retried.
  - Given error -4 twice and then success, then it is `DONE` after three attempts.
  - Given a PNG, then the PDF kind is `NOT_POSSIBLE`, and the preview shows the image.
- **T3:**
  - Given an XLSX, then `TEXT` is `DONE`.
  - Given a zip bomb disguised as DOCX, then the upload is refused as today, or the extraction ends `FAILED` within 120 s without affecting the backend.
- **T4:**
  - Given a memo at rank 1, when an attachment is set to rank 0, then 422 `NOT_STRICTER`.
  - Given an approver cleared to rank 1 and an attachment at rank 2, then the list shows "Restricted attachment" without a title, and its content answers 404.
  - Given a type that needs two attachments, when the initiator submits with one, then 422 `ATTACHMENTS_REQUIRED`.
  - After submit, adding, removing or versioning an attachment answers 409 `WRONG_STATE`.
  - Round 1's binding lists the versions and hashes present at submit, and still does after a resubmit with changes.
- **T5:**
  - Given A editing and a force-save to version 3, when B opens, then B gets A's key.
  - A callback naming a user without a session in the room is refused.
  - A co-author cannot submit (403).
  - Submit answers 409 `DOCUMENT_BEING_EDITED` while a room is open on an attachment.
- **T6:**
  - The compare endpoint returns both texts and audits two reads.
  - A user not cleared for the attachment gets 404.
