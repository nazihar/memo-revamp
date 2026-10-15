# Phase 0 — retrospective (final, 2026-10-15)

> **Phase 0 exited on 2026-10-15** with the owner's approval. The one open goal, the first deploy to the dev server, is carried into Phase 1a. It can run as soon as the VM exists (see "Exit status").

## Exit status (MASTER_PLAN Phase 0 exit criteria)

| Criterion | Status | Evidence |
|---|---|---|
| `make dev` (or equivalent) starts the full local stack; the sample slice works end to end | **Met locally** | `pnpm dev` (ADR-0003); Playwright smoke 6/6 against the real stack and against the production images |
| *(goal)* deploys to a dev environment in one command | **Carried into Phase 1a** (owner decision, 2026-10-15): built and tested locally, waiting for the VM | `deploy.sh` with rollback tested locally; `deploy-dev.yml`; Ansible playbook lint-clean |
| CI green with all gates; SBOM and license report produced | **Met**: GitHub CI green on PRs #1–#6 and on `master` (7 jobs) | `ci.yml`, `nightly.yml`, `release.yml` (actionlint clean); SBOMs and `licenses.md` generated |
| Cross-tenant RLS test passes on the reference table and fails if RLS is disabled | **Met** | `CrossTenantIsolationIT`, `RlsNegativeIT`, `RlsMetaTest` |
| ADRs for every decision taken in this phase | **Met** | ADR-0001 to ADR-0016; index check in CI |

Phase-0 special tests (TESTING_STRATEGY §12):
- RLS meta-test and negative test: done.
- CI stages wired: done, green on GitHub. The first nightly run is scheduled; the first release waits for the cosign key.
- Demo tenant seeding: done.
- ZAP baseline: run locally with 0 failures and 0 warnings; nightly job defined.

Generic exit items:
- Demo script: `demo.md`.
- STATUS: updated.
- NFRs: **confirmed** by the owner on 2026-10-15 (`nfr.md`).
- Usability check: **done** 2026-10-15. UI OK; the layout is too simple, and that is carried into Phase 1a (E03 `ux-review.md`).

## What changed versus the plan
- **DB roles moved from E02 to E01**, because the health slice needed a real app role from the start.
- **The web SBOM is generated from pnpm's own resolution** (`ci/web-sbom.mjs`). `cyclonedx-npm` does not support pnpm, and Trivy over `node_modules` was too slow on Windows.
- **Dev/staging run the on-prem Compose profile**, not Kubernetes. This follows Q2 (own VM) and ADR-0015. The Helm chart is verified on kind in CI instead.
- **Migrations became a separate job** (`memo.mode=migrate`). The app instances never hold migrator credentials, which is stricter than the overview implied.
- **Pseudo-localisation uses three locales.** `xx-LS` only lengthens strings, so `en-XA` was added for accented and bracketed text.
- **Menus are non-modal.** The e2e CSP fixture showed that modal Radix menus inject inline styles and hide the app root from assistive technology.

## What the gates caught (the value of building them first)
- **jqwik:** a UUIDv7 generator that was not monotonic within a millisecond.
- **Negative RLS test:** defence in depth from the tenant-registry policy, which led to a stronger negative test.
- **Trivy:** 3 CRITICAL Tomcat CVEs and 5 HIGH Jackson CVEs in the framework's managed versions, now pinned to patched releases.
- **FindSecBugs:** a response-splitting pattern in the correlation-id filter (now re-rendered from parsed bits) and an unnecessary CSRF disable.
- **Playwright CSP fixture and axe:** the modal-menu issues above.
- **ZAP:** missing Cross-Origin-Embedder/Resource-Policy headers (added).
- **On-prem smoke:** a crash when storage was partly configured, and a SeaweedFS permission issue under hardening.

## What slowed us down
- **VS Code's Java tooling** ran its own Gradle 9.7.1 against the same tree and corrupted outputs. Mitigations: workspace settings and the optional `MEMO_BUILD_ROOT` isolation (README).
- **Windows specifics:**
  - CRLF from Jackson;
  - native curl and `/dev/null`;
  - bind-mount performance for scanners;
  - `NoDefaultCurrentDirectoryInExePath` for `gradlew.bat`.
- **Same Compose project name (`memo-dev`)** for the local stack and the VM environment. The local stack is now `memo-local`.

## Carry into Phase 1a
- **First dev deploy** once the VM exists (STATUS, "Waiting for human"): run Ansible, set the `dev` secrets, run ZAP and Gatling against dev.
- **Product rename after Phase 0:** `memo-revamp` became `memo` everywhere except the repository (PR #6, STATUS A1).
- **First epic: app layout and visual design.** Wireframes of the app frame and the page types, plus a visual direction; owner approval before the feature screens (from the owner walkthrough).
- Tenant context: request filter and propagation into jobs and listeners, on top of `TenantTransactions`.
- A CSP nonce strategy (ADR) before the first modal dialog.
- Rate limiting (Bucket4j); the audit module (hash chain, same-transaction outbox).
- Revisit a static JSX a11y lint plugin.
- Remove the Tomcat and Jackson security overrides once Boot manages patched versions; Renovate groups them.
- Re-check Spring Boot 4.2 GA (expected November 2026) and TypeScript 7 ecosystem support.
