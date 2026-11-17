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
