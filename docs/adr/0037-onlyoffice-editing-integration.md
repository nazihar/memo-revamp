---
status: accepted
date: 2026-11-11
deciders: agent (per-epic gate delegated by the owner, Phase 2 Q1)
---

# 0037 — In-browser editing through ONLYOFFICE behind an editor adapter

## Context and problem statement
Phase 2 ends the first slice with editing a memo's .docx in the browser while the memo is a draft or returned to its author, each save becoming a new document version (overview E02). The document server is AGPL software that needs the plaintext of our envelope-encrypted files, calls back into the backend, and runs JavaScript in our users' browsers. We need an integration that keeps files, versions, audit and locks in our hands and lets the editor be replaced later. Research: [E02 research](../plan/phase-2-workflow-engine/epic-02-onlyoffice-editing/research.md).

## Decision drivers
- Every new version goes through the same files pipeline as an upload (scan, inspection, encryption, record storage, purpose callbacks, the review lock of ADR-0036).
- Nothing reaches the document server without a short-lived, single-purpose credential; nothing from it is trusted without a signature.
- The callback and download endpoints are not reachable from the internet.
- The editor can be swapped (master plan §5) and the AGPL service stays unmodified and separate.
- On-prem: one more container, no outbound network.

## Considered options
1. **Adapter in a new `docedit` module, ONLYOFFICE as the first adapter, internal connector for its callbacks** (chosen).
2. Call ONLYOFFICE directly from the memo module.
3. Let ONLYOFFICE read and write object storage itself (its own storage connector or presigned URLs).

## Decision outcome
Chosen option 1.
- **Module `docedit`:** edit sessions (`docedit_session`, under RLS), the `EditorAdapter` interface (build a signed editor config; verify and parse a callback; fetch a saved result) and the ONLYOFFICE adapter. File purposes opt in with `EditableFilePurpose.mayEdit(file)`; the memo document is the first.
- **Keys and links:** the document key is HMAC(tenant, file, version) so a saved version gets a new key and keys cannot collide across tenants; `document.url` is a five-minute HMAC-signed link to one session's version on the internal connector.
- **JWT:** HS256 with an installation secret (never the vendor placeholder) for the editor config, required on every callback and download; implemented over `javax.crypto.Mac`, accepting only `HS256` and checking `exp`.
- **Network:** the backend serves downloads and callbacks on a separate internal connector (default port 8082) that neither the edge nor the ingress routes; the Helm network policy admits only the document server. The document server reaches the internet nowhere. Browsers reach it under `/office/` on the tenant's own host, so the CSP stays same-origin (fallback: a dedicated host, see research §R8.6).
- **Saving:** callback status 2 (closed with changes) and explicit force saves (6 with type 0 or 1) download the result **only from the configured document-server address**, then store it as a new version **through the files pipeline as the session's user**. The purpose's callbacks still apply: a memo that was submitted meanwhile vetoes the save. Submitting a memo while an edit session is open is refused, so a review never starts on a document that is still changing.
- **Licence:** unmodified upstream image, logo kept, source pointer in the on-prem guide; legal review before the first on-prem delivery.

### Consequences
- Good: one path for every new version (upload or edit), so scanning, record retention, hashes, audit and the review lock need no second implementation. The editor can be replaced by another adapter without touching memos.
- Good: the internet-facing surface grows only by the editor's static assets and WebSocket under `/office/`; callbacks are unreachable from outside and signed anyway.
- Bad: the document server holds plaintext in its cache while a document is open. Mitigated by its own cache cleanup, a deployment note to keep its volume on encrypted storage, and no outbound network; recorded as a residual risk in the threat model.
- Bad: a second backend connector to configure and secure.
- Follow-ups: Phase 3 adds co-editing by several authors, templates, conversion to PDF and the ONLYOFFICE hardening review (master plan); 10.0 after its bake period.

### Confirmation
`DocEditCallbackIT` against a fake document server (every status, bad and missing JWT, foreign download host, veto after submit), `OnlyOfficeContainerIT` nightly against the pinned image, the network-policy and connector tests, the E2E edit journey on the full stack nightly, and the E02 rows of the authorisation matrix.

## Pros and cons of the options
### Option 1 — adapter module with an internal connector
- Good, because versions, locks and audit stay in the files and memo modules.
- Good, because the editor is replaceable and the callback surface is isolated.
- Bad, because it adds a module and a connector.

### Option 2 — ONLYOFFICE called from the memo module
- Good, because it is less code today.
- Bad, because Phase 3 documents (attachments, minutes, agendas) would repeat it, and the editor would be welded to memos.

### Option 3 — ONLYOFFICE talks to object storage
- Good, because no download endpoint is needed.
- Bad, because our files are envelope-encrypted by the application (ADR-0028): the document server cannot read them, and giving it storage credentials or presigned URLs breaks "object storage is never exposed" (master plan §File delivery).

## More information
Sources (accessed 2026-11-11): https://api.onlyoffice.com/docs/docs-api/usage-api/callback-handler/, https://api.onlyoffice.com/docs/docs-api/additional-api/signature/, https://api.onlyoffice.com/docs/docs-api/usage-api/config/document/, https://helpcenter.onlyoffice.com/installation/docs-community-proxy.aspx

**Amended 2026-11-14 by ADR-0047:** the document key is bound to the editing session (edit room), not derived from the file version, so co-editing survives force-saves; the rest of this decision stands.
