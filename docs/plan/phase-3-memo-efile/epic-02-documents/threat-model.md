# E02 Documents — threat model

> Step 4 of `AGENTS.md §5`. STRIDE over the new data flows; ASVS 5.0 IDs as in `docs/security/asvs-checklist.md` (chapter level).

## Data flows
1. Upload of an attachment or a new version → files pipeline (scan, encrypt, store) → `FileVersionCreated` → rendition rows.
2. Rendition job: backend decrypts → ONLYOFFICE conversion (internal network) or Tika (internal network) → result checked, encrypted, stored.
3. Reads: preview (PDF rendition, image), comparison texts, attachment lists, round bindings.
4. Editing: browser ↔ ONLYOFFICE (`/office/`); ONLYOFFICE ↔ backend internal connector (download, callback); rooms shared by co-authors.
5. Configuration: deploy files for ONLYOFFICE, Tika and the proxies.

## STRIDE
| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| T1 | A crafted document exploits a converter or parser (code execution, file read, SSRF) | 2 | Parsers only in ONLYOFFICE 10.0.0.1 and Tika 4.2.0 containers: unmodified, resource-limited, internal network, no egress (ADR-0045); the 10.0 security fixes; the backend never parses content beyond its existing inspectors | V12 (files), V15 (architecture) |
| T2 | Decompression bombs and runaway conversions exhaust resources | 1, 2 | Upload inspectors (ZIP ratio, PDF pages) as today; ONLYOFFICE `inputLimits`, `maxDownloadBytes`, visibility timeout 120 s; Tika write limit, embedded limits, zip-bomb ratio, 120 s timeout, 1 GiB fork heap; per-node concurrency; final error codes stop retries | V12, V2 (anti-automation) |
| T3 | ONLYOFFICE used to reach internal addresses (insert image from URL, AI proxy) | 4 | `ALLOW_PRIVATE_IP_ADDRESS` removed; `externalRequest.action.allow: false` with an allow-list of the internal connector only; AI off; network policy with no egress; T1 spike and `security.spec` check | V15, V13 (configuration) |
| T4 | Conversion or command APIs reached from browsers | 4, 5 | The `/office/` proxy denies converter, command, DocBuilder, info and example paths; JWT with token purpose on every API; DocBuilder off | V13, V8 |
| S1 | A forged callback saves content as a co-author | 4 | Callbacks only on the internal connector; JWT checked; key must match an open room; the saving user must hold a session in that room | V8, V10 (JWT) |
| T5 | Two co-editing sessions diverge and a later save silently overwrites another's work | 4 | One room per file with a session-bound key; a save whose base is stale is refused (`STALE`) and members are told | V2 business logic |
| T6 | An attachment changed after submit, or the record loses what approvers saw | 1, 3 | Changes only while editable; per-round binding with version and SHA-256, append-only by trigger; removal is a flag | V2, V16 |
| I1 | An uncleared reader learns an attachment's content, title or text | 3 | Effective classification checked on list, content, rendition, text and compare; restricted rows redacted; 404 for content | V8 |
| I2 | Derived content (PDF, text) stored or logged in plaintext | 2 | Renditions encrypted with the tenant key; text never logged; no text in the database in E02 | V14 (data protection) |
| I3 | Cross-tenant read through rendition object keys or job payloads | 2, 3 | Job payloads carry tenant and version ids only; the job binds the tenant and reads under forced RLS; object keys include the tenant and are never accepted from clients | V8, tenancy rules |
| I4 | The document server's cache keeps plaintext after editing | 4 | Cache on tmpfs (gone on restart), files kept one hour, cleaned every ten minutes; host swap off or encrypted (deployment note) | V14 |
| E1 | A co-author performs initiator actions | 3, 4 | Co-authors edit content only; actions check the initiator; matrix tests | V8 |
| E2 | Malicious script in a previewed PDF or image runs in our origin | 3 | PDF.js with scripting and XFA off, `useWasm: false`, worker from our origin; images served as `image/*` with `nosniff`; SVG never previewed; CSP unchanged (`script-src 'self'`) | V3 (web frontend) |
| R1 | A co-author denies a change saved from a shared session | 4 | `docedit.version.saved` records the saving user and every room member | V16 |
| D1 | A burst of uploads starves editing on the single ONLYOFFICE instance | 2, 4 | Conversion concurrency caps; the job queue absorbs bursts; editing sessions capped per tenant (Phase 2) | V2 |
| E3 | Desktop Word tokens persist on clients | — | Not adopted (ADR-0047) | V8, V14 |

## Residual risks
- **ONLYOFFICE CE is one instance:** a crash loses edits since the last five-minute checkpoint, and rendition jobs wait.
- **Licence:** the AGPL and additional terms need a lawyer's reading before the first on-premises delivery (`docs/legal/onlyoffice-licence-brief.md`).
- **Restricted attachments:** a reader learns that one exists (ADR-0046).
