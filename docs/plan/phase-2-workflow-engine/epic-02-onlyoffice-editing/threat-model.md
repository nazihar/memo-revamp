# E02 In-browser editing — threat model (STRIDE)

## Data flows
1. Browser → edge → backend (public): open an edit session, receive the signed editor config.
2. Browser → edge `/office/` → ONLYOFFICE: editor script, WebSocket for the editing session.
3. ONLYOFFICE → backend (internal connector): document download, status callbacks.
4. Backend → ONLYOFFICE (internal): download of the saved result from the callback URL.
5. Backend → files pipeline (in-process): the saved result as a new version.

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | A forged callback stores attacker content as a memo version | 3 | Callback JWT (HS256, our secret) required and verified; `alg` other than HS256 refused; the token's payload must match the body; `key` must equal the session's; internal connector not routed by edge or ingress; network policy admits only ONLYOFFICE | V9.x, V13.x | `DocEditCallbackIT` (no token, wrong secret, `alg: none`, `HS512`, mismatched key, body ≠ payload → `{"error":1}`, nothing stored); `InternalConnectorIT` (`/internal/**` on the public port → 404) |
| S2 | Someone opens the editor for a memo they may not edit | 1 | The purpose decides (`mayEdit` = initiator in `DRAFT`/`RETURNED`); view needs `mayRead` (visibility + clearance) | V8.x | matrix `p2-e02-docedit.yaml`; `EditSessionIT` (approver → view only; stranger → 404; draft of another user → 404) |
| T1 | A save lands on a memo that was submitted meanwhile (changes reviewers did not see) | 3, 5 | The files pipeline's purpose callback vetoes versions outside `DRAFT`/`RETURNED` under the memo row lock; submit refused while an edit session is open | V2.x (business logic) | `EditSaveRaceIT` (callback after submit → session `FAILED`/`LOCKED`, no version); `SubmitWhileEditingIT` (409) |
| T2 | Saved content bypasses scanning or type checks | 5 | Saves go through `FileUploads` (scan, DOCX inspection, macro refusal, encryption, record storage) | V12.x | `DocEditCallbackIT` (EICAR in the saved result → refused, session `FAILED`; DOCM content → refused) |
| T3 | Replayed callback stores the same version twice or an old result over a newer one | 3 | Idempotency key per (session, status, URL hash); the session's base version is fixed and the file version check (`expectedVersion`) refuses a stale save | V2.x | `DocEditCallbackIT` (same callback twice → one version) |
| I1 | The download link leaks and lets someone fetch the document | 3 | Link signed (HMAC) for one session, five-minute expiry, internal connector only, plus ONLYOFFICE's JWT; session must be `OPEN` | V8.x, V13.x | `DocEditDownloadIT` (expired, wrong signature, other session's signature, closed session → 403/404) |
| I2 | SSRF: a callback names an internal or external URL for the backend to fetch | 4 | Only the configured document-server scheme/host/port is fetched; redirects not followed; timeout and size limit | V12.x (SSRF) | `DocEditCallbackIT` (foreign host, redirect, oversized → refused) |
| I3 | Plaintext copies of documents in the document server's cache | 2, 3 | Separate service on an internal network with no outbound route; its cache cleaned by its own settings; deployment note: cache volume on encrypted storage; editing off where the operator does not run it | V14.x | Compose/Helm review checklist; `nfr.md` note. **Residual risk, accepted for E02** |
| I4 | The editor fetches resources from the internet (fonts, help, telemetry) | 2 | No outbound network for the service; `customization` disables help and external links; CSP `'self'` on the tenant host | V14.x, V3.x | E2E: no third-party requests (existing security spec extended to the editor page) |
| R1 | A user denies having saved a version | 3, 5 | `docedit.version.saved` and `file.version.created` audited with the session's user, the version hash and the callback status | V16.x | `DocEditCallbackIT` (audit entries asserted) |
| D1 | A flood of callbacks or huge results exhausts the backend | 3, 4 | Internal port only; rate limit per session; size limit on download; one open edit session per (file, user); tenant cap on sessions | V2.x | `DocEditCallbackIT` (oversized result), unit test of the cap |
| D2 | A forgotten editor blocks submit forever | 1 | Sessions expire after 12 hours (job); an expired session no longer blocks | — | `EditSessionExpiryIT` |
| E1 | The vendor's default JWT secret stays in place | 3 | Start-up fails when the secret is blank, shorter than 32 bytes or the vendor placeholder; the generated `.env` and Helm values create a random secret | V13.x | `DocEditConfigTest` |
| E2 | Macros or plugins run in the editor | 2 | Macros and plugins off in the editor config and the service; DOCM refused by the pipeline | V12.x | config unit test; `DocEditCallbackIT` (DOCM) |
