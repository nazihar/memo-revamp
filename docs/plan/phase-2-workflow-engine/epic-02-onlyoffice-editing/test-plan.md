# E02 In-browser editing — test plan

New: the `docedit` module (sessions, adapter, internal connector, signed links, JWT), the ONLYOFFICE service in Compose and Helm, edit and view in the memo screens. Highest risks: forged or replayed callbacks, a save after submit, the document reachable through a leaked link, SSRF through the callback URL, plaintext in the document server's cache. See [design.md](design.md), [threat-model.md](threat-model.md).

## Test data
- **Fake document server:** a small HTTP server in the test JVM that serves a DOCX at a callback URL and sends signed callbacks (good, bad and missing tokens; every status), so the integration tests need no ONLYOFFICE image.
- **Real document server:** the pinned `onlyoffice/documentserver:9.4.0.1` as a Testcontainer, nightly only (large image, slow start).

## By task

| Task | Level | Tests | Stage |
|---|---|---|---|
| T1 | Deployment | Helm template tests (NetworkPolicies, Ingress has `/office/` and never `/internal/`), Compose config check, kubeconform | PR |
| T2 | Unit | `JwtHs256Test` (sign/verify, `alg` confusion, `exp`, tampered payload), `DocumentKeyTest` (charset, length, per-version uniqueness, tenant separation), `SignedLinkTest`, `DocEditConfigTest` (start-up refusal, editor config content) | PR |
| T2 | Integration | `InternalConnectorIT` (paths on the right connector only), `EditSessionIT` (purpose decisions, reuse of the open session, idempotency, tenant cap), `EditSessionExpiryIT` | PR |
| T3 | Integration | `DocEditDownloadIT`, `DocEditCallbackIT` (every status, token cases, foreign host, redirect, oversize, EICAR, DOCM, replay, audit), `EditSaveRaceIT`, `SubmitWhileEditingIT` | PR |
| T4 | Web unit | edit/view buttons by allowed actions and editing on/off; editor page states (loading, error, saving) with a stubbed `DocsAPI`; axe | PR |
| T4 | E2E | security spec extended: editor page without CSP violations or third-party requests (full stack) | nightly |
| T5 | Real document server | The edit journey (`smoke/document-editing.spec.ts`) on the nightly E2E stack with the pinned ONLYOFFICE (`E2E_DOCUMENTS=1`): open, type, close, version 2 stored, in Chromium, Firefox and WebKit. Replaces the planned `OnlyOfficeContainerIT` (see the log) | nightly |
| T5 | E2E | edit journey: open the editor, type, close, the memo shows version 2, submit allowed after the save | nightly (full stack) |
| T5 | Matrix | `p2-e02-docedit.yaml`: public endpoints for anonymous, user, initiator, approver, stranger; internal endpoints (`host: internal`) without a token → refused | PR |

## Non-functional
- Memory of the document server idle and with one editor, recorded in `nfr.md` (T1).
- Save latency from closing the editor to the new version visible: target under 20 s (ONLYOFFICE's 10 s delay plus scanning), measured in the nightly E2E.

## Exit
All PR-stage tests green; the nightly container test and edit journey green twice in a row; ASVS rows and the licence register updated; the runbook written.
