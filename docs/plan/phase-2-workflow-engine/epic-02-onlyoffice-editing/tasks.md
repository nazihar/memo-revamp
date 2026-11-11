# E02 In-browser editing — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Service and network:** verify the `/office/` sub-path behind Caddy with the pinned 9.4.0.1 image (fallback: own host); Compose `full` and on-prem profiles (edge route, cache volume, memory limit, random `JWT_SECRET` in the generated `.env`); Helm optional Deployment, Service, NetworkPolicies (ONLYOFFICE ↔ backend internal port only), Ingress `/office/`; measure memory idle and with one editor and record it in `nfr.md`; dev VM stays off until the 16 GB upgrade | M | — |
| T2 | **`docedit` module:** `docedit/V1__sessions.sql` (RLS), `DocumentEditing`, `EditableFilePurpose`, `EditMode`; the internal Tomcat connector and the path filter; HS256 sign/verify over `Mac`; document keys and signed links; `EditorAdapter` and the ONLYOFFICE adapter (config with permissions, customization, language); configuration with start-up checks; expiry job; `POST /files/{id}/edit-sessions`, `GET …/current`, `GET /files/editing` | L | T1 (config shape) |
| T3 | **Download and callbacks:** content endpoint (link + JWT, `file.opened` with `channel: editor`); callback handling per status; download only from the configured host; save through `FileUploads` as the session's user with a callback idempotency key; veto handling (`LOCKED`) and the user's notification; audit actions; `memo.document` implements `mayEdit`; submit and resubmit refuse while editing; `EDIT_DOCUMENT` / `VIEW_DOCUMENT` in the memo view | L | T2 |
| T4 | **Web:** edit and view buttons; `/memos/$memoId/edit` page (script loading, config, back link, "saving" wait); phone view-only; CSP check in Chromium, Firefox, WebKit (`worker-src`, WebSocket); i18n en/ar; axe | M | T3 |
| T5 | **Tests:** `DocEditCallbackIT`, `DocEditDownloadIT`, `EditSessionIT`, `EditSaveRaceIT`, `SubmitWhileEditingIT`, `EditSessionExpiryIT`, `InternalConnectorIT` against a fake document server; `OnlyOfficeContainerIT` (nightly, real pinned image: open, edit via the command service, force save, version stored); matrix `p2-e02-docedit.yaml`; E2E edit journey on the full stack (nightly); audit coverage | M | T3, T4 |
| T6 | **Docs and close:** licence register (obligations, on-prem source pointer), on-prem guide section, runbook (secret rotation, cache cleanup, editing off), ASVS rows, STATUS, epic log | S | T5 |

T1 and T2 can start together. The D1 demo does not depend on E02.

## Acceptance criteria (selection; full list in test-plan.md)
- **T2:** Given a blank or placeholder JWT secret with the document server configured, the app refuses to start with a clear message. Given `/internal/docedit/**` on the public port, 404.
- **T3:** Given a callback with status 2 and a valid token, a new memo document version exists with the editor's user as uploader and the audit shows `docedit.version.saved`; the same callback again stores nothing new. Given a callback whose `url` points elsewhere, nothing is fetched and `{"error":1}` is answered. Given the memo was submitted after the editor opened, the save is refused and the user is told.
- **T4:** The editor page loads with no CSP violation and no third-party request; an approver sees the document read-only.
- **T5:** The nightly container test stores a version through a real ONLYOFFICE.
