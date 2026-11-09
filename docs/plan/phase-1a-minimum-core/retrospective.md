# Phase 1a — retrospective (2026-11-09)

> **Phase 1a closed on 2026-11-09** at the owner's instruction ("merge and complete this phase"). All seven epics are merged and on dev, and the exit criteria are met with evidence. Three items need the owner in person and are carried, like Phase 0's first deploy: the owner demo, the moderated usability walkthrough and the manual keyboard and screen-reader pass (see "Carried").

## Exit status (MASTER_PLAN Phase 1a exit criteria)

| Criterion | Status | Evidence |
|---|---|---|
| A tenant can be created; users log in with password + TOTP; sessions can be listed and revoked; step-up works | **Met** | E2E exit journey `platform.spec.ts` in every PR (Chromium) and nightly (Firefox): operator creates an organisation → first admin accepts the invitation, enrols TOTP, signs in → signs a second device out → step-up prompt after the window → suspend and resume. Backend: `PlatformTenantsIT`, `OperatorAuthIT`, `AuthIT`, `MfaFlowIT`, `StepUpAndMeIT`, `SuspendedTenantIT`. On dev since 2026-11-08; the owner enrolled the operator account |
| Org directory manageable via UI | **Met** | `org.spec.ts`; `OrgUnitsIT`, `AssignmentsIT`; effective-dated history view |
| Every state change produces an audit event in the same transaction; tamper verification detects an altered row in a test | **Met** (coverage check added at close) | `AuditSameTransactionIT` (rollback leaves neither change nor entry), `AuditTamperIT`, `AuditChainVerifierProperties`, `AuditExportIT` (export verifiable offline). Coverage: `AuditActionCoverageTest` fails when an action recorded in the code has no test asserting it; `AuthorizationMatrixTest` fails when an allowed change succeeds without a new audit entry |
| Files are scanned, encrypted with tenant keys, versioned and streamed with range support; record buckets are object-locked; malicious-file corpus tests pass | **Met** | `UploadIT`, `DownloadIT`, `ObjectStoreIT`, `KeysetStorageIT`, `FileCipherProperties`, `HostileFileCorpusIT`; Jazzer targets nightly. ClamAV runs on dev (965 MiB after three days) |
| A test proves background jobs and async listeners run with the correct tenant context and fail closed without it | **Met** | `TenantContextPropagationIT`, `TenantTaskIT`, `TenantEventsIT`, `PlatformScopeIT` |
| Cross-tenant and permission tests pass for all endpoints | **Met** | `AuthorizationMatrixTest` (654 cells, both hosts), `EndpointCoverageTest` (no endpoint outside the matrix), `RlsMetaTest` (every tenant table under forced RLS), `CrossTenantIsolationIT`, cross-tenant cases per module |

Phase-1a special tests (TESTING_STRATEGY §12): tenant-context propagation, audit-chain tamper detection, same-transaction audit, hostile-file corpus, range downloads, MFA / lockout / revocation / step-up, rate limits — all built and green.

Generic exit items:
- Demo script: `demo.md` (E07). **The owner demo itself is carried.**
- STATUS: updated.
- ASVS: updated per epic and reviewed at close. Three rows had gone stale (V2 rate limiting, V6 authentication, V7 sessions still said "planned"); corrected with evidence.
- Usability: E01 wireframe review done (Direction A "Ledger" chosen, 2026-10-18). **The phase-end moderated walkthrough is carried.**
- Manual accessibility: axe runs on every screen in light/dark and LTR/RTL in CI. **The manual keyboard and screen-reader pass is carried.**

## What changed versus the plan
- **Operators have their own host, accounts and cookie** (Q6, ADR-0031), with a password minimum of 15 and no recovery codes: recovery is the `operator-invite` command on the server.
- **Dev runs ClamAV as an external service**; the Helm chart dropped its in-chart ClamAV and points at one (`clamd.env` shared by Compose, Helm and tests).
- **Many planned test classes were folded into fewer**, larger ones (each epic's test plan names where). The phase-close check mapped every planned test to a real one and built the one that was missing (reset and invitation links on another tenant's host).
- **Not built, carried as follow-ups:** `AuditPerformanceIT`, `NotificationPerformanceIT`, the corpus against the full ClamAV signatures.
- **Arabic stayed complete** in every new screen (Q8 said English only for new languages; Arabic is the RTL proof, and keeping it cost little).

## What the gates caught
- **E2E CSP fixture:** Vite inlined font subsets as `data:` URIs that `font-src 'self'` blocks (E01).
- **Hostile-file corpus:** ClamAV accepted a 200 MiB zip bomb (it scans a trimmed copy without alerting); the zip reader now refuses bombs from the central directory (E05).
- **Reading the code paths:** nginx response buffering would have written decrypted downloads to disk; a versioned bucket let a newer object shadow a record (E05).
- **Jazzer (nightly):** the JDK's StAX parser throws `MissingResourceException` on a crafted DOCTYPE, which escaped the refusal path as a 500 (fixed in PR #30).
- **Helm on kind (nightly):** the chart rendered the upload ceiling as `1.048576e+08`, so the backend never started on Kubernetes; CI now fails on numbers in exponent form (PR #30).
- **Trivy:** zlib in the distroless base (time-boxed exception to 2026-11-22), musl, libexpat, brotli, curl and libx11 in the web base (upgraded in the image).
- **Dev deploy:** a short Base32 secret took dev to 502, and the deploy did not roll back because health answered before start-up finished; `deploy.sh` now needs five consecutive UP checks (E03).
- **The E2E exit journey:** the signed-in devices table had no accessible name.
- **The phase-close review:** 20 audit actions recorded but never asserted (now asserted, and a test keeps it so), the missing cross-tenant link test, stale ASVS rows, and a planner-dependent assertion in `OrgPerformanceIT` that failed once on master.

## What slowed us down
- **Stacked PRs and one blocker for all:** E06 and E07 waited on merges, and a base-image CVE failed every PR until the owner chose an exception.
- **Master is not the PR:** the test JVM ran out of heap only on some master runs (E04), and `OrgPerformanceIT` failed once on master after passing on the PR. Lesson kept: watch the master run before calling an epic deployed.
- **Billing block on the private repository** stopped Actions until the owner made it public for a while (E03).
- **Local environment:** VS Code's Gradle holding the build lock (`MEMO_BUILD_ROOT`), Docker Desktop's clock drifting after sleep (E05).
- **detekt's default thresholds** (functions per class, throws, returns) forced several splits; the code is better for most of them.

## Carried
- **With the owner:** the demo (`demo.md`), the moderated usability walkthrough (login + MFA, org editing, tenant administration), the manual keyboard and screen-reader pass (including the E01 patterns gallery).
- **Dated:** the zlib exception in `.trivyignore` expires 2026-11-22; take the rebuilt distroless digest before then.
- **Follow-ups:** `AuditPerformanceIT`; `NotificationPerformanceIT`; the full-signature ClamAV corpus nightly; sample bodies for the remaining mutating matrix cells, so the runtime audit check reaches every endpoint (it reaches 14 of about 70 today; the source check covers every action); remove the `apk upgrade` lines, the Spring Session licence exceptions and the Guava pin when upstream catches up; check Spring Boot 4.2 GA.
- **Later phases:** operator recovery codes and a second operator, and a separate operator database role (Phase 7, ADR-0031); break-glass support access, tenant export and offboarding (Phase 1b).
- **Next:** Phase 1b (enterprise identity and organisation) and Phase 2 (workflow engine) may run in parallel (MASTER_PLAN). The next step is the Phase 1b overview for the owner's approval.
