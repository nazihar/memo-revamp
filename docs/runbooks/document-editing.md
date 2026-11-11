# In-browser document editing (ONLYOFFICE)

**What it is:** memo initiators edit the memo's Word document in the browser while they hold the memo; everyone else who may see the memo reads it there. The editor is ONLYOFFICE Docs `9.4.0.1`, a separate, unmodified service (ADR-0037, design `docs/plan/phase-2-workflow-engine/epic-02-onlyoffice-editing/`). A save becomes a new document version through the normal upload pipeline (scan, inspection, record lock), as the person who edited.

## Turning it on

Editing is on when the backend has a document server URL and a secret. Without them every editing endpoint answers 404 and the memo shows no edit buttons.

| Where | Setting |
|---|---|
| Compose (on-prem, dev) | profile `documents`; `MEMO_DOCSERVER_URL=http://onlyoffice`; `ONLYOFFICE_JWT_SECRET` in `.env` (made by `deploy/scripts/gen-secrets.sh`) |
| Helm | `integrations.documentServer` in `values.yaml` (URL, `jwtSecret` secret reference, pod labels for the NetworkPolicies, DNS resolver for the web proxy) |
| Backend | `MEMO_DOCSERVER_URL`, `MEMO_DOCEDIT_JWT_SECRET`, `MEMO_DOCEDIT_INTERNAL_URL` (how the document server reaches the backend, default `http://backend:8082`) |

The backend **refuses to start** when the URL is set and the secret is missing, shorter than 32 bytes or a vendor placeholder. Give ONLYOFFICE at least 4 GB of memory (see `docs/plan/nfr.md`).

**Network:** browsers reach the editor only through the web container's `/office/` path (same origin, so the app's CSP holds). ONLYOFFICE reaches only the backend's internal port 8082, which serves nothing but signed document links and callbacks; nothing outside may reach 8082 or ONLYOFFICE directly.

## Rotating the secret

1. Generate a new value (`openssl rand -base64 48`).
2. Set it for ONLYOFFICE and the backend in the same change (`ONLYOFFICE_JWT_SECRET` in Compose, the secret behind `integrations.documentServer.jwtSecret` in Helm) and restart both.
3. Editors open at that moment can no longer save. Rotate outside office hours, or ask editors to close the editor first.

## Turning it off

Remove `MEMO_DOCSERVER_URL` (Compose: also drop the `documents` profile) and restart the backend. Open sessions stop blocking submit at once (the endpoints answer 404); nothing else changes.

## When something goes wrong

| Symptom | Cause and action |
|---|---|
| "The editor could not be opened" | ONLYOFFICE is down or still starting (it takes about a minute): `docker compose ps onlyoffice`, its health check. In the browser console, a CSP report for `/office/` means the proxy is not in front of it. |
| A user is told "Your changes … could not be saved" | The memo was submitted, or a newer version was uploaded, while they edited (`docedit.session.closed` with reason `LOCKED` or `SUPERSEDED` in the audit log). Their text is not stored; they copy it from the editor before closing, or re-apply it. |
| Submit answers "The document is open in the editor" | Someone still has the editor open. Closing it saves within about ten seconds. A forgotten editor stops blocking after `memo.docedit.session-lifetime` (12 h), when the expiry job marks it `EXPIRED`. |
| `docedit.callback.refused` in the audit log | A callback failed the signature, the token or the download-path check. Many in a row mean the secrets differ (see rotation) or someone is probing the internal port. |

**Cache:** ONLYOFFICE keeps working copies in its own volume and removes them itself (about a day after the last editor closes). Clearing the volume while editors are open loses their unsaved changes; otherwise it is safe.

## Licence (on-prem)

ONLYOFFICE Docs Community is AGPL-3.0 with additional terms. We ship the vendor's unmodified image; its source for the pinned release is at https://github.com/ONLYOFFICE/DocumentServer (tag `v9.4.0.1`). Keep its notices and this pointer with any on-prem delivery. Legal review is required before on-prem distribution (`docs/dependencies.md`).
