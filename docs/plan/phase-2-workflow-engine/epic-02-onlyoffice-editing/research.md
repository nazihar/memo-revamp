# E02 In-browser editing (ONLYOFFICE, minimal) — research

All sources were accessed on **2026-11-11**.

## R1 — Versions, licences, maintenance

E02 adds **no library** to the backend or the web build. It uses one service the repository already pins and one JavaScript file that service serves itself.

| Component | Current | Licence | Decision |
|---|---|---|---|
| ONLYOFFICE Docs (Community), image `onlyoffice/documentserver` | 9.4.0.1 pinned in Compose since Phase 0 (`docs/dependencies.md`); 10.0.0 listed in the vendor changelog (2026-10-10) | AGPL-3.0 with additional terms | **Keep 9.4.0.1** for E02; take 10.0 through Renovate after its bake period, as the register already says. 9.4 removed the 20-connection limit from the Community Edition and runs a lighter single-process mode |
| Editor API script (`web-apps/apps/api/documents/api.js`) | served by the document server itself | (part of ONLYOFFICE) | Loaded at run time from our own origin (§R8.6), not bundled; no npm package |
| JWT (HS256) on the backend | `java.security`/`javax.crypto` (`Mac`, HmacSHA256) | JDK | **No JWT library.** Signing and verifying HS256 with a fixed header is a few lines over `Mac`; a library would add a dependency for one algorithm. The verifier rejects any `alg` but `HS256` and checks `exp` |

Sources: https://helpcenter.onlyoffice.com/docs/docs-changelog.aspx, https://www.onlyoffice.com/blog/2026/05/onlyoffice-docs-9-4, https://linuxiac.com/onlyoffice-docs-9-4-removes-community-connection-limit/, https://github.com/ONLYOFFICE/Docker-DocumentServer

## R8 — ONLYOFFICE Docs API

### R8.1 Document key
`document.key` identifies the document for the editor's cache: at most 128 characters from `0-9 a-z A-Z - . _ =`, unique across every service using the same document server, and **new every time the document is saved**. Users who open the same key co-edit one session.
→ Our key is derived from the **file version** being edited: `m` + Base32 (no padding) of HMAC-SHA256(server key, tenant id ‖ file id ‖ version id), truncated to 40 characters. The same version opened twice joins one session (E02 allows co-editing by the people who may edit, which is the initiator only); a saved version yields a new key; keys cannot be guessed or collide across tenants.

### R8.2 Callback statuses
The document server POSTs to `callbackUrl`; the integrator must answer `{"error":0}` or the editor shows an error.

| Status | Meaning | `url` present | Our handling |
|---|---|---|---|
| 1 | Being edited (a user connected or disconnected) | no | Record activity; keep the session open |
| 2 | Ready for saving: sent about 10 s after the last editor closed with changes | yes | Download from the document server, store as a **new version** through the files pipeline, close the session |
| 3 | Error while saving | yes | Close the session as failed; audit; the user is told the edit was not saved |
| 4 | Closed with no changes | no | Close the session |
| 6 | Force save while still editing (`forcesavetype` 0 command, 1 Save button, 2 timer, 3 form submit) | yes | Store a new version (only for types 0 and 1: an explicit save); keep the session |
| 7 | Error while force saving | yes | Audit; keep the session |

### R8.3 JWT
HS256 throughout. Three directions, each with its own secret and switch in `local.json` (`services.CoAuthoring.secret.browser|inbox|outbox`, `token.enable.browser|request.inbox|request.outbox`); the Docker image maps `JWT_ENABLED`, `JWT_SECRET` (one secret for all three) and `JWT_HEADER` (default `Authorization`). The default secret is a placeholder the server warns about at start-up.
- **Editor config:** signed on the backend and handed to the browser (the secret never reaches the browser).
- **Outgoing requests (callbacks, document download):** POST bodies carry `token`; GET requests (our document link) carry the token in the header only. The header token may omit history data because of header size limits, so for POSTs the body token wins.
→ We set our own secret per installation (never the placeholder), require the token on every callback and download, and verify signature, `alg`, and that the payload's `key` and `url` match what we expect.

### R8.4 Document URL and download
`document.url` must be an absolute URL the document server can fetch (local links need a token). The `url` in a callback points at the document server's cache.
→ Our `document.url` is a **short-lived, single-purpose signed link** to a backend endpoint (HMAC over session id and expiry, five minutes), reached on an internal port (§R8.6). When saving, the backend **ignores the host of the callback `url`**: ONLYOFFICE builds it from the browser-facing address (`X-Forwarded-Host`, so `https://<tenant>/office/cache/…`, found in the T1 spike). Only a path under the document server's `/cache/` is accepted, and it is always fetched from the configured internal address, with a size limit and a timeout, so a forged URL cannot point the backend anywhere else (no SSRF).

### R8.5 Permissions and modes
`editorConfig.mode` `edit` or `view`; `document.permissions` (`edit`, `download`, `print`, `copy`, `comment`, `review`).
→ Initiator in `DRAFT`/`RETURNED`: `edit` with `download: false` (downloads go through our audited endpoint), `review: false`, `comment: false`. Everyone else who may see the memo: `view`, which E02 offers so approvers can read the document without downloading it.

### R8.6 Network placement
The browser loads `api.js` and talks to the document server over HTTP and WebSocket; the document server talks to the backend for downloads and callbacks.
- **Browser → document server:** served under a **sub-path of the tenant's own host** (`/office/`), so the CSP stays `'self'` for `script-src`, `frame-src` and `connect-src`. ONLYOFFICE's proxy guide requires `X-Forwarded-Proto` and `X-Forwarded-Host`; sub-path hosting is not officially documented, and the community practice is `X-Forwarded-Host: <host>/office` (with `X-Forwarded-Prefix` reported to work too). **T1 verifies this with the pinned image**; the fallback is a dedicated `office.<base-domain>` host with that origin added to `script-src`, `frame-src` and `connect-src`.
- **Document server → backend:** a **separate internal connector** on the backend (`memo.docedit.internal-port`, default 8082) that serves only the download and callback endpoints. It is not routed by the edge or the ingress; the Helm network policy admits only the document server's pods; Compose keeps ONLYOFFICE on the internal network with no outbound route.

Sources: https://api.onlyoffice.com/docs/docs-api/usage-api/config/document/, https://api.onlyoffice.com/docs/docs-api/usage-api/callback-handler/, https://api.onlyoffice.com/docs/docs-api/additional-api/signature/, https://api.onlyoffice.com/docs/docs-api/additional-api/signature/request/, https://helpcenter.onlyoffice.com/installation/docs-configure-jwt.aspx, https://helpcenter.onlyoffice.com/installation/docs-community-proxy.aspx, https://forum.onlyoffice.com/t/virtual-path-configuration-fur-kubernetes-installation/670, https://forum.seafile.com/t/onlyoffice-integration-via-subfolder-seafile-and-onlyoffice-document-server-running-in-docker/5270

### R8.7 Footprint
No official minimum for 9.4; a third-party write-up calls the 9.4 single-process mode suitable for 2 CPU / 4 GB machines. **T1 measures** the pinned image idle and with one editing session on the local stack, and records it in `nfr.md`; the dev VM gets ONLYOFFICE only after the owner's 16 GB upgrade (Phase 2 Q2).
Source: https://www.besthub.dev/articles/onlyoffice-9-4-lightweight-mode-one-command-docker-deployment-for-self-hosted-docs-77081c138e4a (third-party; treated as a hint only)

### R8.8 Licence obligations
AGPL-3.0 with additional terms (section 7) that require keeping the ONLYOFFICE logo and attribution in the editor's interface. We run it **unmodified, as a separate service**: no patched images, no hidden branding, no linking into our code (the browser loads its script from it). On-prem distribution of the image is a conveying under the AGPL: the installation guide points to the exact upstream source for the pinned version and keeps its notices. **Legal review before the first on-prem delivery** stays open (`MASTER_PLAN §5`, `docs/dependencies.md`).
Sources: https://github.com/ONLYOFFICE/DocumentServer (licence and additional terms in the repository), https://www.gnu.org/licenses/agpl-3.0.html
