# E02 In-browser editing — log

## 2026-11-11
- Plan written under the delegated gate (Phase 2 Q1): research (R1, R8), ADR-0037, design, threat model, tasks, test plan.
- Findings while planning: ONLYOFFICE 9.4.0.1 is already pinned in Compose with JWT on and an internal network (Phase 0), and the backend has a health probe for it, but nothing uses it yet. Sub-path hosting (`/office/`) is not officially documented; T1 verifies it before anything depends on it, with a dedicated host as the fallback. 10.0 is out (2026-10-10); E02 stays on 9.4 until the bake period ends, as the dependency register says.
- Dependency on the owner: the dev VM upgrade to 16 GB (Phase 2 Q2) before ONLYOFFICE runs on dev. Everything else runs on the local full stack; the D1 demo does not need E02.
- Owner: dev VM upgraded to 16 GB (Phase 2 Q2 done); ONLYOFFICE runs on dev with the `documents` profile.
- Implementation T1–T6 on `feat/2-e02-docedit`. Findings and deviations from the plan:
  - **Callback URL host (research §R8.4):** ONLYOFFICE names the saved file on the *browser-facing* address (`https://<tenant>/office/cache/…`), built from `X-Forwarded-Host`. Allow-listing the host would not work; the backend now takes only the path, requires it under `/cache/` (after the public `/office` prefix), refuses `..`, and fetches from the configured internal address. Design §3 and threat I2 updated.
  - **Session endpoint:** `GET /edit-sessions/{id}` (how my session ended) instead of `GET /files/{id}/edit-sessions/current`: opening again already resumes the open session, and the editor page needs the outcome after leaving.
  - **Route:** `/memos/$memoId/document?mode=edit|view` instead of `/memos/$memoId/edit`, since reading uses the same page.
  - **Expired sessions:** the expiry job marks them `EXPIRED`; a late save may still land on an expired session (`OPEN` and `EXPIRED` are both live for saves), as design §2 says.
  - **Repeated callbacks:** the upload idempotency key already stored once; the session update and `docedit.version.saved` now also happen once.
  - **Local stack:** `pnpm dev` set `MEMO_DOCSERVER_URL` but no secret, so the backend would have refused to start with this branch. `scripts/dev.mjs` now passes the secret, and a `backend-gateway` socat gives ONLYOFFICE its only way out: the host backend's port 8082.
  - **Bean names:** `identity` already has a `SessionStore`; ours is `EditSessionStore`.
  - **Tests:** the planned separate ITs (callback, download, session, race, submit-while-editing, expiry, internal connector) are one `DocEditIT` sharing a fake document server; each planned case is a step in it. `OnlyOfficeContainerIT` and the E2E edit journey (nightly) follow in their own PR.
