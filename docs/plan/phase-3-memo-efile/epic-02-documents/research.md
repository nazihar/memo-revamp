# E02 Documents: conversion, versions, attachments, editing — research

> Step 1 of `AGENTS.md §5`. Overview research items R5 (ONLYOFFICE in production) and R6 (desktop Word), plus text extraction, version comparison and attachment preview. Gathered 2026-11-14 by a research agent and checked by the lead: the 10.0.0.1 release date (Docker Hub) and our `ALLOW_PRIVATE_IP_ADDRESS=true` (all three deploy paths) were confirmed directly. Points marked "unconfirmed" are confirmed or dropped by the T1 spikes (§8).

All sources accessed **2026-11-14**. Builds on `docs/plan/phase-2-workflow-engine/epic-02-onlyoffice-editing/research.md` (R8.1–R8.8: document key format, callback statuses, JWT directions, signed document URL, network placement, licence summary); none of that is repeated here. "Unconfirmed" marks anything not verified against a primary source or the pinned image.

Repo baseline: `onlyoffice/documentserver:9.4.0.1` (deploy/compose/compose.yaml:73, onprem compose, ci/e2e-stack.sh); env `JWT_ENABLED=true`, `JWT_SECRET`, `JWT_HEADER=Authorization`, **`ALLOW_PRIVATE_IP_ADDRESS=true`** (needed today because the backend is on a private address); internal-only `docs-internal` network. `tika-core` 4.1.0 (detection only), PDFBox 3.0.8, Commons Compress 1.28.0 already in `gradle/libs.versions.toml` / `docs/dependencies.md`. Web CSP (`web/security-headers.json`): `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; object-src 'none'` (no `worker-src`, so workers fall back to `script-src 'self'`).

---

## 0. Version decision first: move to ONLYOFFICE Docs 10.0 in E02

| Fact | Source |
|---|---|
| Docker Hub tags: `10.0.0.1` / `10.0.0` / `10.0` / `latest` pushed 2026-10-12. `9.4.0.1` was re-pushed 2026-07-22. There is **no 9.4.x patch release and no 10.0.x patch yet** | https://hub.docker.com/v2/repositories/onlyoffice/documentserver/tags?page_size=25&ordering=last_updated |
| Git tags `v10.0.0` (DocumentServer) and `v10.0.0.1` (server) exist | GitHub API, ONLYOFFICE/DocumentServer and ONLYOFFICE/server tags |
| 10.0 has **more than 40 security fixes** (other sources say 35), including: arbitrary file write when extracting archives during conversion; local file read during EPUB conversion; path traversal in HML; out-of-bounds reads in DOC/XLS/PPT metafiles; a double free in DOCX; **SSRF in `/ai-proxy`** and in WOPI; **two macro sandbox bypasses** (one reached browser cookies); XSS in the HTML plugin; execution of embedded PDF JavaScript; a heap overflow in PDF image masks | https://www.onlyoffice.com/blog/2026/10/security-fixes-onlyoffice-10 |
| 10.0 adds `convertServiceEnable`, `docbuilderEnable`, `requestDefaults.checksumVerification`, `aiSettings.enable`, `customization.ai.disabled`, `/converter/from-file`, the `coauth.connections` metric, and stricter token rules (`token.purposeCheck`, empty secrets rejected) | https://www.onlyoffice.com/blog/2026/10/onlyoffice-10-developers, https://helpcenter.onlyoffice.com/installation/docs-roadmap.aspx |
| **Breaking change in 10.0:** `/converter`, `/docbuilder` and `/command` "no longer accept the token from the editor configuration". A token may carry `operation` (`command`/`converter`/`docbuilder`). The from-file endpoints require it. `services.CoAuthoring.token.purposeCheck` defaults to `validate` | api.onlyoffice.com changelog (repo `ONLYOFFICE/api.onlyoffice.com`, `site/docs/docs-api/more-information/changelog.md`); server-config.md line "services.CoAuthoring.token.purposeCheck" |
| 10.0 changes the source licence headers and the additional terms (see §2) | GitHub, ONLYOFFICE/server `DocService/sources/server.js` at `v9.4.0.1` vs `v10.0.0.1` |

**Recommendation:** make "upgrade to 10.0.0.1" the first E02 task. It is one month old, so it is past the bake period. 9.4 carries the conversion and macro vulnerabilities listed above, and E02 widens conversion to arbitrary tenant-allowed types, legacy formats included. Handle the breaking changes: conversion tokens carry `operation: "converter"`, and the backend download endpoint sends correct `Content-Length` (and optionally `Content-Digest`) because `checksumVerification` is on. Keep Renovate for 10.0.x.

---

## 1. R5 — ONLYOFFICE production depth

### 1.1 Conversion API
- **Endpoints:** `POST /converter` (JSON body; the document is fetched from `url`) and, **new in 10.0**, `POST /converter/from-file` (`multipart/form-data` with parts `file`, `params`, optional `token`; "Any other part makes the request invalid", which returns error -7). Both are switched off together by `services.CoAuthoring.server.convertServiceEnable=false` (HTTP 403). "Conversions initiated by the editors are not affected."
  - from-file response: **on success the body is the converted file** (Content-Disposition from `title`). If an async conversion is still running, the body is JSON `{"endConvert":false,"percent":n}`. On error it is JSON `{"error":n}`.
  - from-file with JWT: "the conversion parameters are taken from the token only. The token payload must contain the `operation` field with the `converter` value and must not contain the `url` field."
  - Size is capped by `FileConverter.converter.maxDownloadBytes` (default 104857600 = 100 MiB). Larger uploads get HTTP 413.
- **Parameters** (`/converter`): `async`, `filetype`, `outputtype` (or `ooxml`/`odf`), `key` (required for `/converter`), `url`, `token`, `password` (the result has no password), `title`, `region` (spreadsheet → PDF number/date formats), `pdf.form`, `thumbnail` (image output), `spreadsheetLayout` (PDF page setup), `documentLayout`, `documentRenderer`, `watermark`, `codePage`, `delimiter`. Since 8.1, add the `shardkey` query parameter (only matters for clusters).
- **Async:** "the response is formed instantly"; poll "without parameter change until the conversion is finished". From-file async: send the same `params` (with `key`) **without** the `file` part. "Without the key parameter, the conversion task is identified by the file contents". **Sync** requests can hit a web timeout while conversion continues; re-sending the same key fetches the result.
- **Response fields:** `endConvert`, `fileType`, `fileUrl` (only when complete; "uses the same server name as the conversion request"), `percent`, `error`. JSON requires `Accept: application/json`.
- **Error codes:** -1 unknown, **-2 conversion timeout**, -3 conversion error, -4 download error, -5 incorrect password, -6 conversion result database error, -7 input error, -8 invalid token, -9 output format cannot be determined (XML), **-10 size limit exceeded**.
- **Timeout:** the converter (x2t) child is killed when the task exceeds `queue.visibilityTimeout` (default **300 s**). "Together with `queue.retentionPeriod` [900], it contributes to the conversion timeout." (Source: server-config.md, and `FileConverter/sources/converter.js` at v10.0.0.1, lines ~1160–1194: `childRes.kill()` and `CONVERT_TIMEOUT`.)
- **Other limits** (default.json v10.0.0.1): `maxDownloadBytes` 100 MiB; `downloadTimeout` 2 min; `downloadAttemptMaxCount` 3; `maxprocesscount` 1; `maxRedeliveredCount` 2; `inputLimits` (OOXML zip **uncompressed** caps: docx 50 MB, **xlsx 300 MB**, pptx 50 MB, vsdx 50 MB). There is **no input page-count or nesting limit**. The only page cap is output: "Spreadsheet-to-PDF or image conversions return at most 1500 pages at once."
- **Formats → PDF** (official tables plus `ONLYOFFICE/document-formats/onlyoffice-docs-formats.json`):
  - text: doc docm docx dot dotm dotx epub fb2 fodt hml htm html hwp hwpx md mht mhtml odt ott pages rtf stw sxw txt wps wpt xml
  - spreadsheets: csv et ett fods numbers ods ots tsv sxc xls xlsb xlsm xlsx xlt xltm xltx
  - presentations: dps dpt fodp key odg odp otp pot potm potx pps ppsm ppsx ppt pptm pptx sxi
  - fixed layout: djvu oxps xps ofd
  - diagrams: vsdx vsdm vssm vssx vstm vstx
  - **Images (png/jpg/gif/bmp/tif/webp/heic) are output-only. They are not convertible to PDF.** eml/msg are not supported.
- **Formats → TXT:** all text formats (except djvu/txt), presentations (the official table says pptx only; the formats JSON lists all slide formats, so this is unconfirmed beyond pptx), oxps/xps/ofd, **and pdf**. **Spreadsheets and diagrams cannot go to TXT.**

Sources: https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/request/ · https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/response/ · https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/error-codes/ · https://api.onlyoffice.com/docs/docs-api/additional-api/conversion-api/conversion-tables/ · ONLYOFFICE/api.onlyoffice.com `site/docs/docs-api/additional-api/conversion-api/request-from-file.md` · ONLYOFFICE/server `Common/config/default.json` @v10.0.0.1 · https://github.com/ONLYOFFICE/document-formats

**Recommendation:** use **`/converter/from-file`, async with a `key`**. The backend streams the decrypted bytes over the internal network, so ONLYOFFICE never fetches a URL for conversion. `key` = HMAC(tenant ‖ file version ‖ output type ‖ params version), because results are cached per key. Set `queue.visibilityTimeout` lower (for example 120 s) and `maxDownloadBytes` = `memo.files.max-upload-bytes`. Lower the xlsx `inputLimits` (for example 100 MB). Check the rendition's page count with PDFBox afterwards, because ONLYOFFICE has no input page limit. Map -2/-10 to "too large / too slow" (no retry) and -1/-3/-4/-6 to retry with backoff (db-scheduler job, tenant context). Mark a rendition "not possible" for images, eml/msg and unknown types. Text for spreadsheets always comes from Tika (§3).

### 1.2 Co-editing modes and force-save
- `editorConfig.coEditing = {mode: "fast"|"strict", change: true}` (fast is the default). If a user changes the mode in the UI, "the choice is saved in browser local storage, and it overrides" the config value. Use **`change: false`** to pin it. Fast mode needs autosave (`customization.autosave=false` forces strict only).
- Fast: "All changes are sent to other users in real time", with no redo. Strict: "each user works in an isolated session" until Save. Strict is the default for PDF.
- Force-save: three triggers. (0) the command service `forcesave`. (1) `customization.forcesave=true`, which fires on each Save click. (2) the `autoAssembly` timer (`services.CoAuthoring.autoAssembly.enable` false by default, `interval` "5m", `step` "1m"). Form submission (3) also exists. "Auto assembly saves the file's current state. Users in strict mode who haven't clicked Save are excluded." "Document versions created by forcesave do not appear in the document history."
- Session lifetime: `expire.sessionidle` "1h" (idle users are disconnected, which drives the final save), `expire.sessionabsolute` "30d", `expire.sessionclosecommand` "2m" warning.
Sources: https://api.onlyoffice.com/docs/docs-api/usage-api/config/editor/ · https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/saving-file/ · server-config.md (`expire.*`, `autoAssembly.*`)

### 1.3 Document keys, versions and co-editing (the important nuance)
- "For co-editing to work, all users must open the document with the same `key`." After a successful save (status 2) "the key can no longer be used to open the document for editing", so "the integrator must generate a new key". **During force-save "the key must not be changed — otherwise co-editing stops." After a forcesave, new users joining the session also need the same key.**
- Consequence for our design (key = HMAC of the *file version*): if a force-save (status 6, types 0/1) stores a new version V2 while the session started from V1 is still open, a co-author who opens the editor afterwards would get key(V2) and start a **separate session**. That splits the edits and the last save wins. **The key must be bound to the editing session**: anyone opening while a session is open for that memo document joins that session's key. A new key is minted only when the session closes (status 2/4) or when a version is created outside the editor.
Source: https://api.onlyoffice.com/docs/docs-api/get-started/how-it-works/co-editing/

**Recommendation:** for drafting co-editing, use fast mode with `coEditing.change=false`. Bind the key per session as above. Keep "force-save on Save click → new version" (types 0/1). Keep ignoring timer force-saves as versions, unless the restart-loss risk (§1.5) makes `autoAssembly` worth turning on as a checkpoint that is not shown as a version. Set `sessionabsolute` to match `memo.docedit.session-lifetime` (12 h) and `sessionidle` to policy (for example 30–60 min).

### 1.4 Cache behaviour and cleanup
- `services.CoAuthoring.expire.files` = **86400 s** ("time of life for the folders in App_Data"). `filesCron` = `"00 00 */1 * * *"` (hourly). `filesremovedatonce` 100. `documentsCron` every 2 min. `expire.saved` 3600. `storage.fs.urlExpires` 900 s (cache download links). `storage.urlExpires` 604800.
- If the callback handler does not answer `{"error":0}`, the file can end up in the **"forgotten" folder** (`server.forgottenfiles`), which persists outside the normal expiry. Community reports describe cache folders not being cleared. The command service has `getForgottenList`/`getForgotten`/`deleteForgotten` (unconfirmed for CE 10.0; check).
- The cache lives under `/var/lib/onlyoffice` (Docker volume).
- **CE 9.4+/10.0 keeps its session/change/task state in memory**: `DocService/sources/databaseConnectors/memoryConnector.js` @v10.0.0.1 says "In-memory persistence connector for the public/community standalone runtime ... **No SQL pool, no driver dependencies, no persistence across restarts.**" After a restart the cleanup job may no longer know about files from earlier tasks. That would leave orphans on a persistent volume (inferred, unconfirmed).
Sources: server-config.md (`expire.*`), ONLYOFFICE/server source, https://community.onlyoffice.com/t/cache-files-arent-cleared/1005, https://community.onlyoffice.com/t/cache-files-are-not-cleared/13544, https://github.com/ONLYOFFICE/Docker-DocumentServer

**Recommendation:** mount the cache (`/var/lib/onlyoffice`) as a **size-capped tmpfs** (RAM-backed, gone on restart). That meets "encrypted cache volume / cleanup" without a persistent plaintext disk, provided host swap is off or encrypted. Set `expire.files` to 3600 s and `filesCron` to every 10 minutes. Monitor and delete forgotten files. Persistent-volume fallback: an encrypted volume plus wipe-on-start.

### 1.5 Scaling and HA (Community Edition)
- 9.4 removed the 20-connection limit, consolidated "the architecture into a single process" and removed the RabbitMQ and database dependencies (https://linuxiac.com/onlyoffice-docs-9-4-removes-community-connection-limit/, https://www.it-connect.tech/onlyoffice-docs-9-4-drops-the-20-connection-limit-and-updates-its-open-source-license/, roadmap). The Docker README lists `DB_*`, `REDIS_*`, `AMQP_*` under "Enterprise and Developer editions only". The official Kubernetes chart (`ONLYOFFICE/Kubernetes-Docs`) uses the commercial `onlyoffice/docs-cluster-de` image with Redis/RabbitMQ/PostgreSQL.
- Therefore **CE = one instance, no shared state, no horizontal scaling or failover.** A restart drops open sessions and any **unsaved** changes (memory connector). There is no published sizing for 10.0; Phase 2 measured 9.4 (see `docs/plan/nfr.md`). There is a `coauth.connections` metric with `mode`/`replica` tags (10.0) for StatsD (`METRICS_ENABLED`).

**Recommendation:** document CE as single-instance. HA means restart-on-failure plus "editors lose unsaved work since the last force-save". If a customer needs HA, the answer is the commercial Enterprise Edition behind the same adapter (MASTER_PLAN risk row already names it). The per-tenant cap `memo.docedit.max-sessions-per-tenant` stays the capacity control. Run conversions on the same instance with a concurrency limit in our job runner.

### 1.6 .xlsx editing differences
- The spreadsheet editor supports the same `coEditing` modes. External-link features are **hidden unless the integrator handles the events**: `onRequestReferenceData` (Update values / IMPORTRANGE), `onRequestReferenceSource`, `onRequestOpen`. "If not declared, the *Paste link* and *Update values* buttons are hidden." Do not declare them.
- Conversion: spreadsheet → PDF uses `region` and `spreadsheetLayout` and is capped at 1500 output pages. **No → TXT**. The zip uncompressed limit is 300 MB by default (higher than docx).
- Macro-enabled `xlsm`/`docm`: ONLYOFFICE macros are JavaScript, and VBA is not executed (VBA preservation on save is unconfirmed). `customization.macros=false` blocks ONLYOFFICE macros (below).
- `server.downloadFileAllowExt` default `["pdf","xlsx"]`; `utils.limits_document_types_upload` "xlsx"; `assemblyFormatAsOrigin: true` (saves in the original format).
Sources: https://api.onlyoffice.com/docs/docs-api/usage-api/config/events/ · default.json @v10.0.0.1

**Recommendation:** enable `.xlsx` with the same adapter and the same key/session rules; do not declare the reference events. Keep refusing macro-enabled formats for editing (or decide per tenant allow-list). Use Tika for xlsx text.

### 1.7 Hardening checklist (master plan list, point by point)
| Item | Setting / fact | Recommendation |
|---|---|---|
| JWT on configs, browser, inbox, outbox | Docker `JWT_ENABLED` (default true) sets all three. `token.outbox.expires` "5m". `purposeCheck` `validate` (10.0). Empty secret → tokens rejected (10.0) | Keep. Consider `purposeCheck: "strict"` |
| Callback reachable only from the ONLYOFFICE network | Already done (internal port 8082, NetworkPolicy) | Keep |
| **Deny outbound / private IPs** | Today `ALLOW_PRIVATE_IP_ADDRESS=true`, so the request filter permits every private address. Server code: `externalRequest.directIfIn.allowList` (URL **prefixes** that go direct), `externalRequest.directIfIn.jwtToken` (default true: a URL inside a signed token bypasses filtering), `externalRequest.action.allow` (default true), `blockPrivateIP` (true), `request-filtering-agent.allowIPAddressList/denyIPAddressList` | Set `externalRequest.directIfIn.allowList: ["http://backend:8082/"]` (our internal prefix) and **`externalRequest.action.allow: false`**, so everything else is refused (for example "insert image from URL" SSRF). Drop `ALLOW_PRIVATE_IP_ADDRESS`. Keep the network-level isolation as the primary control. Verify on 10.0.0.1 in a spike (code read from `Common/sources/utils.js` @v10.0.0.1) |
| Macros off | `customization.macros=false`: "Since version 9.0.3, setting it to false fully blocks macros". `macrosMode` "disable" | Set both in every editor config |
| Plugins off | `customization.plugins=false`; Docker `PLUGINS_ENABLED=false`; `services.CoAuthoring.plugins.autostart: []` | Set both |
| AI off (not on the list, but no hosted AI is allowed) | `aiSettings.enable` **defaults to true**: "/ai-proxy requests are rejected with HTTP 403" when false. `aiSettings.allowBrowserProxy` true by default. Editor `customization.ai.disabled` | Set `aiSettings.enable=false`, `allowBrowserProxy=false`, `customization.ai.disabled=true` |
| Public APIs | `docbuilderEnable` (default true); `/converter` is needed by us; `wopi.enable` false (default) | `docbuilderEnable=false`. At the `/office/` browser-facing proxy, **block** `/converter*`, `/docbuilder*`, `/coauthoring/CommandService.ashx`, `/command`, `/ConvertService.ashx`, `/info`, `/example`, welcome page (`WELCOME_PAGE_ENABLED=false`, `EXAMPLE_ENABLED=false`). Allow only editor paths |
| Encrypted cache volume + cleanup | §1.4 | tmpfs, short `expire.files` |
| CPU/memory limits | none built in; `maxprocesscount` 1 | Compose `deploy.resources` / k8s limits (≥ 4 GiB per Phase 2 nfr) |
| Session limits | `sessionabsolute` 30d default | 12 h |
| Download/print/copy | `document.permissions` (Phase 2) | Unchanged |

Sources: https://api.onlyoffice.com/docs/docs-api/usage-api/config/editor/customization/customization-standard-branding/ · https://raw.githubusercontent.com/ONLYOFFICE/Docker-DocumentServer/master/README.md · server-config.md (`externalRequest.*`, `request-filtering-agent.*`, `aiSettings.*`) · ONLYOFFICE/server `Common/sources/utils.js` @v10.0.0.1

---

## 2. ONLYOFFICE licence — inputs for the legal-review brief (facts and questions only)

### 2.1 What the licence says (two versions matter)
**(a) 9.4.0.1 (currently pinned): source file headers** (`ONLYOFFICE/server` `DocService/sources/server.js` @v9.4.0.1):
> "In accordance with Section 7(a) of the GNU AGPL its Section 15 shall be amended to the effect that Ascensio System SIA expressly excludes the warranty of non-infringement of any third-party rights."
> "The interactive user interfaces in modified source and object code versions of the Program must display Appropriate Legal Notices, as required under Section 5 of the GNU AGPL version 3."
> "**Pursuant to Section 7(b) of the License you must retain the original Product logo when distributing the program.** Pursuant to Section 7(e) we decline to grant you any rights under trademark law for use of our trademarks."
> "All the Product's GUI elements, including illustrations and icon sets, as well as technical writing content are licensed under the terms of the Creative Commons Attribution-ShareAlike 4.0 International."

**(b) 10.0.0.1 and master: restructured.** The `LICENSE` file in ONLYOFFICE/DocumentServer was changed in commit `a07795c4` (2026-05-06, "Update LICENSE with restructured AGPLv3 additional terms"). Its preamble:
> "This Program is licensed under the GNU Affero General Public License v3.0 (AGPLv3), as supplemented by the terms set forth below. These terms form an integral part of this License and shall be included in all copies and distributions of the Program. ... **Failure to comply with these terms constitutes a violation of the License and may result in termination of rights under Section 8 of the License.**"
Additional Terms:
> "1. Retention of Notices and Attribution. Pursuant to Sections 4, 5, and 7(b) of the License, you must retain all applicable copyright notices, license notices, warranty disclaimers, and attribution or origin notices included in the Program."
> "2. Modification Notice Requirement. Modified versions of the Program must carry prominent notices stating that they have been modified, including the date (or dates) of such modification, and must clearly indicate that they are based on the original ONLYOFFICE software developed by Ascensio System SIA."
> "3. Appropriate Legal Notices in User Interfaces. Where the Program, or any modified version thereof, includes interactive user interfaces, the required notices shall be displayed through a clearly accessible and prominently visible user interface feature ... Such notices must enable users to: (i) identify ONLYOFFICE as the original developer; (ii) understand that the version in use may be a modified version; and (iii) access the applicable license information."
> "4. No Trademark License. In accordance with Section 7(e) this License does not grant any rights to use the trademarks, service marks, trade names, logos, or branding of the Licensor. Any use of such trademarks is governed by the Licensor's Trademark Policy, available at: https://www.onlyoffice.com/trademark-policy"
> "5. Non-Code Content Licensing. Certain non-code elements ... including ... illustrations, icon sets, and technical writing or documentation content, are licensed under ... CC BY-SA 4.0."
The 10.0.0.1 source headers carry `SPDX-License-Identifier: AGPL-3.0-only` and "No trademark rights are granted under this License". **The explicit "retain the original Product logo" sentence is gone in 10.0.**

**(c) AGPL v3 text (the relevant sections, from the same LICENSE file):**
- §13: "if you **modify** the Program, your modified version must prominently offer all users interacting with it remotely through a computer network ... an opportunity to receive the Corresponding Source of your version ..." (The trigger is modification.)
- §6: conveying object code requires conveying the Corresponding Source in one of the listed ways. For example §6(d): "offering access from a designated place ... and offer equivalent access to the Corresponding Source in the same way ... the Corresponding Source may be on a different server (operated by you or a third party) ... provided you maintain clear directions next to the object code ... you remain obligated to ensure that it is available for as long as needed".
- §7 permits only listed additional terms ((a)–(f), for example "(b) Requiring preservation of specified reasonable legal notices or author attributions", "(e) Declining to grant rights under trademark law"). "All other non-permissive additional terms are considered 'further restrictions' ... If the Program as you received it ... contains a notice stating that it is governed by this License along with a term that is a further restriction, **you may remove that term**."

**(d) Public dispute (context, no ruling):** after the Euro-Office fork (Nextcloud/IONOS) removed ONLYOFFICE branding, ONLYOFFICE called it a licence violation. The FSF reportedly said the logo requirement is a further restriction that recipients may remove. Commentators say the question (does a logo count as a §7(b) "legal notice or author attribution"?) may only be settled in court. Sources: https://linuxiac.com/fsf-says-onlyoffice-cannot-use-agpl-to-restrict-forks/ · https://ie-forum.nl/artikelen/the-logo-trap-onlyoffice-s-agplv3-gambit-and-what-it-means-for-free-and-open-source-compliance · https://www.onlyoffice.com/blog/2026/03/interview-with-lev-bannov-ceo-at-onlyoffice-on-the-euro-office-situation · https://heise.de/-11241334 · https://lwn.net/Articles/1067771/

### 2.2 Our facts (for the lawyer)
- We run the vendor's **unmodified** image as a separate container. We configure it through `local.json` / environment variables and the editor config (JWT, customization flags such as plugins/macros/AI off). We do not change code or branding, and we do not white-label. The browser loads `api.js` from the document server (served under our `/office/` path). Our code talks to it only over HTTP (editor API, callbacks, conversion API).
- (a) **SaaS:** we operate the container; users interact with the editor over the network. (b) **On-prem:** we ship Compose/Helm files that pull the vendor image (or, possibly, deliver the image in an offline bundle).

### 2.3 Questions to ask the lawyer
1. With an unmodified Document Server, is AGPL §13 triggered at all for SaaS? Does configuration (local.json, environment variables, editor `customization` flags that hide plugins/AI/macros) count as "modification"?
2. On-prem: is shipping Compose/Helm files that make the customer pull the image from Docker Hub "conveying"? If we ship the image in an offline bundle, what source-offer form satisfies §6 (a pointer to the GitHub tag per §6(d), or a written offer)? Does this cover the third-party and Ubuntu base-image packages inside the image (GPL components), and for how long must the source stay available?
3. Which additional terms apply to us: the 9.4 header terms ("retain the original Product logo", §15 warranty amendment) or the 10.0 LICENSE terms? Can 9.4 and 10.x terms differ for the same customer over upgrades?
4. Additional Term 3 (notices in the UI): is the editor's own About screen enough when it is embedded in our iframe? Must our product show anything ("powered by ONLYOFFICE", a licence link)?
5. Trademark policy: may we name "ONLYOFFICE" in our docs, UI, marketing and runbooks? Under what conditions?
6. The "termination under Section 8" clause and the enforceability of the logo/notice terms (§7 "further restrictions"): what is the risk for us, given that we comply in full anyway?
7. CC BY-SA 4.0 on GUI elements/icons: any obligations when shown inside our product?
8. Is Enterprise Edition (commercial licence) needed for any customer scenario (HA, white-label, support)? What are the contractual terms?

---

## 3. Text extraction (Tika)

### 3.1 Versions, licence
- Maven Central: `tika-core`, `tika-parsers-standard-package`, `tika-server-standard`: **4.2.0** (2026-10-28), 4.1.0 (2026-09-29), 4.0.0 (2026-08-21); 3.3.3 (2026-10-13) is a 3.x support release. Java 17+ for 4.x. Apache-2.0.
- 4.0: "Parsing moves into **crash-isolated forked processes**, including tika-server ... via tika-pipes". `ForkParser` was removed and replaced by `PipesForkParser` (module `tika-pipes-fork-parser`). Default output is Markdown. 4.0 also adds vision-language-model parsers (Claude, Gemini, OpenAI), which **must not be configured** (no hosted AI).
- 4.2: "Configuration sent with a request is untrusted unless the operator marks it otherwise. Per-request limits can only tighten operator limits. tika-server adds transfer limits and reworked TLS. XML parsing moves to Apache Commons Secure XML."
- Docker: `apache/tika:4.2.0` (standard, about 190 MB) and `4.2.0-full` (about 590 MB, adds OCR/GDAL; not needed). `4.1.0-2` also exists.
- Recent CVE: CVE-2025-54988 (XXE via XFA in PDFParser, 1.13–3.2.1).
Sources: https://tika.apache.org/ · https://tika.apache.org/security.html · https://repo1.maven.org/maven2/org/apache/tika/ · https://hub.docker.com/v2/repositories/apache/tika/tags

### 3.2 Footprint: in-process vs server
- `tika-core` (what we have): detection only, small. `tika-parsers-standard-package`: pulls POI, PDFBox, many parsers. The size is tens of MB of jars (unconfirmed exact number) and a very large parse surface in the app JVM. In-process Tika 4 is designed to be used through `PipesForkParser`, which forks a JVM. From a distroless backend that means a second JVM inside the backend container, sharing its memory limit and network.
- tika-server 4.x: `/tika` (`/tika/text` for plain text), `/rmeta`, `/detect`, `/meta`, `/unpack` run in **forked JVMs by default**. "A fork that times out, runs out of memory, or crashes returns 503." Pool `pipes.numClients`, `pipes.maxWaitForClientMillis` (60 s → 429). "It has no built-in authentication or authorization." `allowPerRequestConfig` false by default; `allowPipes` false (keeps fetchers/emitters off, so no server-side URL fetching). `maxRequestSizeBytes` default 1 GiB → 413. Exceptions are returned unredacted (stack traces).
Sources: https://tika.apache.org/docs/4.0.x/using-tika/server/index.html · https://tika.apache.org/docs/4.0.x/advanced/robustness.html ("Never run Tika in the same JVM as an indexer, search system, or other service whose availability matters.")

### 3.3 Hostile-file limits (Tika 4 `parse-context` keys; defaults)
- `output-limits`: `writeLimit` -1 (unlimited), `throwOnWriteLimit` false, `maxXmlDepth` 100, `maxPackageEntryDepth` 10, **`zipBombThreshold` 1,000,000 chars, `zipBombRatio` 100** (chars per input byte; this is the old SecureContentHandler logic, now configured here).
- `embedded-limits`: `maxDepth` -1, `maxCount` -1 (both unlimited by default).
- `timeout-limits`: `totalTaskTimeoutMillis` 3,600,000, `progressTimeoutMillis` 120,000 (forked only), `throwOnDeadline` false.
- `standard-metadata-limiter-factory`: `maxTotalBytes` 10 MB, `maxFieldSize` 100 KB, `maxKeySize` 1024, `maxValuesPerField` 10.
- Fork heap via `pipes.forkedJvmArgs` (the example uses `-Xmx1g`). An OS OOM-kill shows up as a client socket timeout (`pipes.socketTimeoutMillis` 60 s).
Sources: https://tika.apache.org/docs/4.0.x/advanced/setting-limits.html · https://tika.apache.org/docs/4.0.x/pipes/timeouts.html

### 3.4 Alternatives
- ONLYOFFICE → TXT (for documents, presentations, pdf; **not spreadsheets**). This reuses a service already isolated, but it is a second C++ parser path and still needs Tika for xlsx/csv/eml.
- PDFBox `PDFTextStripper` in-process (already a dependency). This keeps PDF parsing in the app JVM, which is what we want to avoid for hostile files.

**Recommendation:** run **tika-server as a separate, unmodified container** (`apache/tika:4.2.0`, standard image, Apache-2.0) on an internal network with **no egress**, reachable only by the backend (it has no auth of its own). Configure: `writeLimit` (for example 5–10 M chars, `throwOnWriteLimit=false` and mark the text truncated), `embedded maxDepth` 3–5 and `maxCount` (for example 500), zipBomb defaults, `totalTaskTimeoutMillis` 120 s, `progressTimeoutMillis` 60 s, fork `-Xmx1g`, container CPU/memory limits, `allowPerRequestConfig=false`, `allowPipes=false`, no OCR/VLM parsers. Use `/tika/text`, and `/rmeta` only if embedded attachments are wanted. Treat 503/429/timeouts as retryable job failures with a cap, then "text unavailable". Raise `tika-core` in the backend to the same version (4.2.0) so detection and extraction agree. Add a `service` row in `docs/dependencies.md`. Extracted text is stored encrypted like the document (E05 says it is as confidential as the document).

---

## 4. Version comparison — diff library

| Library | Version / date | Licence | Notes |
|---|---|---|---|
| **jsdiff** (`diff`, npm) | 9.0.0 (2026-04-13); repo pushed 2026-11-11; about 9.2k stars | BSD-3-Clause | Zero dependencies. `diffWords` (optional `intlSegmenter` for word boundaries), `diffLines`, `diffSentences`, `diffChars`. **`maxEditLength` and `timeout` options abort expensive diffs (return `undefined`)**. Async mode via `callback` |
| diff-match-patch (google) | npm 1.0.5 (2020) | Apache-2.0 | **Repository archived** (last push 2024-05-22) |
| @sanity/diff-match-patch | 3.2.1 (2026-09-21) | Apache-2.0 | Maintained fork; character-level with semantic cleanup; fewer releases |
| java-diff-utils | 4.18 (2026-10-18) | Apache-2.0 | Server-side Myers/histogram; `DiffRowGenerator` makes side-by-side rows with inline word diffs |
| react-diff-viewer-continued | 4.4.0 | MIT | **Uses @emotion (injects `<style>` elements), which breaks our `style-src 'self'` CSP.** One maintainer. Avoid |

Sources: https://registry.npmjs.org/diff, https://github.com/kpdecker/jsdiff (README: `maxEditLength`, `timeout`, `intlSegmenter`), https://github.com/google/diff-match-patch, https://registry.npmjs.org/@sanity/diff-match-patch, https://repo1.maven.org/maven2/io/github/java-diff-utils/java-diff-utils/, https://github.com/java-diff-utils/java-diff-utils/releases

**Recommendation:** use **jsdiff (`diff` 9.0.0) in the browser, inside a Web Worker** (same-origin worker file, so CSP is unchanged). Use two levels: a paragraph/line diff first, then `diffWords` inside changed paragraph pairs, with `timeout` (for example 2 s) and `maxEditLength`, and fall back to "too different to compare inline; showing both texts". Render side-by-side with our own component (no CSS-in-JS). The server provides both extracted texts through one audited "compare" endpoint (document-open audit for both versions) plus version number, SHA-256 and author. The texts are capped at the extraction `writeLimit`. If a server-side diff is ever needed (for example for the PDF/A record), java-diff-utils is the Apache-2.0 match.

---

## 5. Attachment preview

### 5.1 PDF.js
- `pdfjs-dist` **6.5.438** (2026-11-01, too new for the bake rule) and **6.4.299** (2026-10-03); Apache-2.0; 5 maintainers (Mozilla); unpacked package about 37.6 MB (most is legacy builds/cmaps/fonts; the shipped min build plus worker is much smaller, size unconfirmed).
- CSP points (from `src/display/api.js` and `src/core/wasm_loader.js` on master):
  - **Worker:** set `GlobalWorkerOptions.workerPort`/`workerSrc` to a same-origin module file (Vite `new URL(..., import.meta.url)`). With no `worker-src` directive, `script-src 'self'` applies. No `blob:` is needed.
  - **WASM:** `useWasm` defaults to true (image decoders such as JPEG2000, ICC colour). Compiling WASM needs `'wasm-unsafe-eval'` in `script-src`. The loader has a JS **fallback import** when WASM fails (`#importFallback`). Set **`useWasm: false`** (or ship the wasm files and add `'wasm-unsafe-eval'`, a CSP change). Fallback behaviour under our CSP: verify in a spike.
  - **eval:** current master has no `isEvalSupported` option. Older 4.x needed `isEvalSupported:false` (CVE-2024-4367, GHSA-wgrm-67xf-hhpq, fixed in 4.2.67).
  - **Fonts:** "fonts are converted to OpenType fonts and loaded via the Font Loading API or `@font-face` rules". Binary FontFace loading should not hit `font-src`/`style-src`, but `@font-face` rules would be blocked by `style-src 'self'`. Verify in the spike; the fallback is `disableFontFace: true` (path rendering, slower).
  - Keep `enableXfa: false` (default) and no scripting (do not use `pdf_viewer` scripting). Links in the annotation layer get `rel=noopener noreferrer` and external targets.
  - cMaps and standard fonts are fetched from `cMapUrl`/`standardFontDataUrl`. Host them same-origin (`connect-src 'self'`).
- The rendition PDF is served through the backend download endpoint (range requests are already planned in MASTER_PLAN "page-by-page PDF loading").
Sources: https://registry.npmjs.org/pdfjs-dist · https://github.com/mozilla/pdf.js (`src/display/api.js`, `src/core/wasm_loader.js`) · https://advisories.gitlab.com/pkg/npm/pdfjs-dist/ · https://www.nutrient.io/guides/web/troubleshooting/content-security-policy/

### 5.2 Alternatives
- The browser's native PDF viewer in an `<iframe>`/`<object>`: needs `frame-src`/`object-src` changes (we have `object-src 'none'`), behaves differently per browser (poor on iOS), and exposes download/print controls we do not control. Rejected.
- Server-side page images (ONLYOFFICE `thumbnail` with `first:false` gives a zip of page images; or PDFBox rendering): no PDF parser in the browser, but heavy, there is no text selection, and PDFBox rendering in the app JVM is a hostile-file risk. Keep it as a later option for thumbnails only.
- Commercial viewers (Nutrient/Apryse): proprietary, need WASM. Rejected.

### 5.3 Which attachment types get a PDF rendition
- **ONLYOFFICE → PDF:** see §1.1 (word processing, spreadsheet, presentation, Visio, xps/oxps/djvu/ofd, html/md/epub, hwp, Apple iWork).
- **PDF:** the original is its own preview source. We may still normalise it, but PDF → PDF is not in ONLYOFFICE's conversion list.
- **Images:** no ONLYOFFICE conversion. Preview natively as `<img>` from the download endpoint (`img-src 'self'`; the endpoint must send `Content-Type` image/* and `X-Content-Type-Options: nosniff`; refuse SVG preview or serve SVG as a download only). E04 decides how images enter PDF/A (PDFBox).
- **eml/msg/zip/other:** no rendition. Show Tika text or "download only".

**Recommendation:** adopt **pdfjs-dist 6.4.299** (pin; take 6.5.x after the bake period) with `useWasm:false` and a same-origin module worker. Lazy-load it only on the memo view. Add a register row. Spike: render a set of fonts/JPX/ICC PDFs under the production CSP with zero CSP reports (the e2e fixture already fails on CSP violations).

---

## 6. R6 — Desktop Word round trip (WebDAV + `ms-word:` URI)

### 6.1 Findings
- **URI scheme:** `ms-word:ofe|u|<https-url>` (edit), `ofv` (view), `nft` (new from template). Supported on Windows Office 2010 SP2+, Office for Mac 2011+, iOS. "the maximum path length is 256 characters for all apps except Excel, where the limit is 216." The spec's only security note: applications "should implement protections to guard against opening documents from untrusted remote systems". (Page last updated 2022.) https://learn.microsoft.com/en-us/office/client-developer/office-uri-schemes
- **Authentication:** the URI hands Word a bare URL. **Browser cookies are not passed.** A Microsoft Open Specifications engineer: passing cookies "in ofe-type URIs doesn't work ... the protocol handler only launches Word, which then uses cached credentials or prompts the user." https://learn.microsoft.com/en-gb/answers/questions/1840924/pass-authenication-cookie-to-ms-word-to-open-word
  - **Basic auth:** Microsoft 365 Apps on Windows **block Basic authentication sign-in prompts by default**. They are re-enabled only per host by Group Policy ("Allow specified hosts to show Basic Authentication prompts to Office apps"), and Microsoft does not recommend that. Basic also cannot carry our MFA/step-up. https://learn.microsoft.com/deployoffice/security/basic-authentication-prompts-blocked
  - **NTLM/Kerberos:** needs AD integration; this is not our auth model (local accounts + TOTP, later SSO).
  - **MS-OFBA** (forms-based auth): the server answers 403 with `X-FORMS_BASED_AUTH_REQUIRED` (login URL) and `X-FORMS_BASED_AUTH_RETURN_URL`. Office opens an embedded login dialog and keeps the resulting **session cookie in Office's own cookie store**, which we do not control (lifetime and scope beyond our session cookie flags, behaviour on Mac unclear). The spec lists Windows Office 2007–2021/LTSC 2024; Mac is not listed. "the process for actually establishing the user's identity is not specified". https://learn.microsoft.com/en-us/openspecs/sharepoint_protocols/ms-ofba/2a1d01dc-ac63-438a-a768-af1222d2164b, https://docs.airlock.com/iam/8.5/index/1597849690675.html
  - **Token in the URL path** (the common workaround, for example Atlassian "Allow authentication tokens in the URL path"; tokens must be in the path because Office drops query strings on save): https://jira.atlassian.com/browse/CONFSERVER-40720, https://support.atlassian.com/confluence/kb/edit-in-word-requires-login-or-doesnt-work-at-all/, https://kaweah.freedombox.rocks/kiwix/stackoverflow.com_en_all_2023-11/questions/33991463/ms-office-url-authentication
- **Token scope / lifetime / audit:**
  - *Scoped to one file:* **yes.** A path token can be bound to user + file version + session + allowed methods, and the WebDAV handler serves only that resource.
  - *Audited:* **yes**, server-side per request (OPTIONS/PROPFIND/GET/LOCK/PUT/UNLOCK).
  - *Short-lived:* **only partly.** Word keeps using the same URL for the whole time the document is open (lock refreshes, AutoSave/PUT, re-open from Recent). It has no way to refresh a token. A token that expires mid-edit makes saves fail (the work stays in the Office Upload Center / cache). The token must therefore live as long as the editing session (hours), not minutes. Revocation at UNLOCK/submit/session end is possible.
  - *No credentials in places that persist:* **no.** Office records the **full URL** in the per-user registry File MRU (`HKCU\Software\Microsoft\Office\16.0\Word\User MRU\...\File MRU`), in Windows Recent LNK files and jump lists, and keeps a local copy in the Office document cache. Sources: https://www.cybertriage.com/blog/ntuser-dat-forensics-analysis-2026/, https://kb.binalyze.com/air/features/acquisition/supported-evidence/windows-collections-detail/officemru, https://digitalinvestigator.blogspot.com/2026/06/microsoft-office-file-mru-and-trust.html. Whether the Recent list also roams to Microsoft's cloud for signed-in accounts is **unconfirmed**. The token would also land in any customer proxy access logs. AGENTS §3.4: "Never put sensitive content in URLs."
- **Locking:** Word opens read-write only against a WebDAV **Class 2** server (LOCK/UNLOCK). Otherwise it opens read-only. The lock must interact with our "attachments lock during review" and with submit (submit must break or wait for Word's lock). https://ithittest.azurewebsites.net/ajax/programming/open-doc-webpage/opening_ms_office_docs
- **macOS:** many reports of read-only opens (no LOCK sent), "Cannot Download" errors with URIs that work on Windows, and authenticated OPTIONS problems in Office 2016+. https://techcommunity.microsoft.com/discussions/microsoft-365/microsoft-word-opens-custom-webdav-server-in-read-only-mode/4483813, https://learn.microsoft.com/en-us/answers/questions/4925784/word-for-mac-error-cannot-download-when-using-offi, https://help.nextcloud.com/t/error-read-only-when-open-a-word-document-from-web-app/21254
- **Browser behaviour:** the scheme hand-off depends on the browser (Firefox issues reported); the user gets an "Open Word?" prompt.
- **Other control gaps:** no step-up/MFA inside Word; the document plaintext is cached on the endpoint (bypassing the in-browser `download:false` policy and document-open audit granularity); a new WebDAV server surface (XML bodies for PROPFIND/LOCK need XXE-safe parsing).

### 6.2 Recommendation: **do not adopt** (record as "not adopted" in ADR-OO-FULL)
Tokens can be scoped and audited, but they cannot be both short-lived and usable for a desktop editing session. More importantly, the only workable auth (a bearer token in the URL path) **persists in the Office MRU, Windows Recent items and the Office cache**, and would sit in proxy logs. That breaks "no credentials in places that persist" and AGENTS §3.4. Basic prompts are blocked by default in Microsoft 365; MS-OFBA puts our session in a cookie store we do not control and is Windows-only. macOS support is unreliable. Step-up cannot be enforced. The alternative for users who insist on desktop Word is the existing **download → edit → upload a new version** flow (audited, scanned, versioned), optionally with a "checked out by" marker. Revisit only if a customer supplies an SSO-integrated setup (Kerberos/AD) and accepts the endpoint-cache risk, by a new ADR.

---

## 7. Summary of new or changed dependencies to register
| Item | Version | Licence | Scope |
|---|---|---|---|
| ONLYOFFICE Docs | 9.4.0.1 → **10.0.0.1** | AGPL-3.0 + additional terms (restructured in 10.0) | service |
| Apache Tika server image `apache/tika` | **4.2.0** (standard) | Apache-2.0 | service (new) |
| `tika-core` | 4.1.0 → 4.2.0 (align) | Apache-2.0 | runtime |
| `diff` (jsdiff) | 9.0.0 | BSD-3-Clause | runtime (web) |
| `pdfjs-dist` | 6.4.299 | Apache-2.0 | runtime (web) |
| (optional) java-diff-utils | 4.18 | Apache-2.0 | not needed if the diff is in the browser |

## 8. Spikes suggested for T1
1. 10.0.0.1 upgrade on the local stack: the existing editor flow, callbacks with `checksumVerification`, a conversion token with `operation`, `externalRequest` allow-list with `ALLOW_PRIVATE_IP_ADDRESS` removed, "insert image from URL" refused, `/ai-proxy` 403, tmpfs cache.
2. `/converter/from-file` async with a hostile corpus (zip bomb docx, huge xlsx, deeply nested, legacy doc/xls/ppt): timeouts, error codes, memory peak.
3. tika-server 4.2.0 with the limits config on the same corpus: 503/timeout handling and memory.
4. pdfjs-dist under the production CSP (`useWasm:false`; font loading): zero CSP reports.
5. Co-editing: two authors, force-save in the middle, a third joins. Confirm one session under the per-session key rule.
