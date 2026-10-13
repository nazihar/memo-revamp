# Testing Strategy

> **For the coding agent.** This document is mandatory. Every epic's `test-plan.md` follows it, every task's definition of done references it, and every phase's exit depends on it. Tool versions must be re-verified at the start of each phase like any other dependency. Test tools are not shipped to customers, but they still go into `docs/dependencies.md` (marked *dev-only*) with their license.

---

## 1. Principles

1. **Tests are part of the feature.** A slice without its tests is not done.
2. **Test behaviour, not implementation.** Assert on outcomes (state, API responses, audit events, rendered output), not on private methods or call counts — except at true boundaries (external systems).
3. **Real dependencies over mocks.** PostgreSQL, SeaweedFS, ONLYOFFICE and ClamAV run in Testcontainers for integration tests. Mock only what we do not own and cannot run (e.g. a customer IdP — use a local test IdP instead where possible).
4. **Security and tenancy are tested on every change**, not only in pen tests.
5. **Deterministic or deleted.** No sleeps, no wall-clock time, no test-order dependence, no shared mutable data between tests.
6. **Fast feedback first.** The PR stage stays under ~15 minutes; heavier suites run nightly, weekly and at release.
7. **Every bug fix adds a regression test** at the lowest layer that reproduces it.
8. **No production or customer data** in tests or non-production environments. Synthetic data only; customer-provided sample documents (for fidelity tests) live in a restricted corpus with written permission.

---

## 2. Test layers overview

| Layer | What it proves | Primary tools | Runs in |
|---|---|---|---|
| Unit | Pure domain logic (state machines, rules, numbering formats, validation) | JUnit 5, AssertJ or Kotest assertions, MockK (boundaries only) | PR |
| Property-based | Invariants hold for many generated inputs and action sequences | jqwik or Kotest property testing | PR (short), nightly (long) |
| Module | A Spring Modulith module works in isolation; events published/consumed correctly | Spring Modulith `@ApplicationModuleTest`, Scenario API | PR |
| Architecture | Module boundaries and layering rules hold | `ApplicationModules.verify()`, ArchUnit | PR |
| Persistence & RLS | Queries, migrations, constraints and row-level security behave on real PostgreSQL | Testcontainers PostgreSQL, Flyway, jOOQ | PR |
| Authorization matrix | Every role × action × attribute combination is allowed or denied exactly as designed | Parameterized tests generated from the design's matrix | PR |
| API integration | Endpoints, errors (Problem Details), idempotency, optimistic locking, pagination | Spring Boot test + MockMvc/WebTestClient or REST Assured, Testcontainers | PR |
| Contract | Backend, OpenAPI spec and generated clients agree; API is robust to unexpected input | OpenAPI drift check, generated TS client compile, Schemathesis | PR (drift), nightly (fuzz) |
| Concurrency | No double actions, no lost updates, single job execution across nodes | Parallel test harness, guarded updates, multi-instance db-scheduler tests | PR (key cases), nightly |
| Document & file | Uploads, conversion, PDF assembly, rendering, signatures, hostile files | Malicious-file corpus, veraPDF, PDF render-and-compare, Jazzer fuzzing, ONLYOFFICE container | PR (core), nightly (fuzz, golden) |
| Web unit/component | Components, hooks, forms, i18n | Vitest, Testing Library, MSW | PR |
| End-to-end (web) | Real user journeys across the full stack | Playwright against Docker Compose stack + seeded demo tenant | PR (smoke), nightly (full, cross-browser) |
| Visual regression | Key screens don't change unintentionally (light/dark, LTR/RTL) | Playwright screenshots | Nightly |
| Accessibility | WCAG 2.2 AA | axe (component + e2e), manual keyboard/screen-reader checklist | PR (axe), per phase (manual) |
| i18n | No hard-coded strings; layouts survive long text, RTL and complex scripts | Pseudo-localization build, RTL screenshots, PDF golden tests | Nightly |
| Mobile | Shared logic, UI, offline/sync, platform security | KMP `commonTest`, XCTest/XCUITest, JUnit + Compose UI tests, Maestro | PR (unit), nightly (UI/e2e) |
| Security | Known vulnerability classes, secrets, dependencies, runtime behaviour | Semgrep CE, SpotBugs + FindSecBugs, eslint security rules, Trivy or OWASP Dependency-Check, gitleaks, OWASP ZAP, MobSF, MASTG procedures | PR, nightly, weekly |
| Performance | Latency and throughput budgets at realistic volumes | Gatling, `pg_stat_statements`, Lighthouse CI (web budgets) | Nightly (smoke), weekly/release (full) |
| Resilience | Behaviour under dependency failure, node loss and restore | Toxiproxy (Testcontainers), node-kill tests, backup/restore drills | Weekly, release |
| Migration & upgrade | Schema migrations and rolling upgrades are safe | Empty-DB migration, N-1 → N upgrade with seeded data, old-app-on-new-schema test | PR (empty), nightly (upgrade) |
| Mutation | Tests actually detect faults in critical modules | PIT (JVM) | Nightly |
| Acceptance, exploratory, usability, UAT | The product does what users need | Scripted UAT, exploratory charters, usability sessions | Per epic / phase / milestone |

---

## 3. Backend testing in detail

### 3.1 Unit and property-based
- Keep domain logic (workflow state machine, DoA rule evaluation, numbering formats, quorum rules, pass rules for votes, hash-chain computation) in plain Kotlin with no Spring context so it is unit-testable in milliseconds.
- **Property-based tests are required** for:
  - Workflow engine: random sequences of actions (approve/return/reject/withdraw/resubmit/reassign) never violate invariants — only the current assignee can act, approvals from earlier rounds never count, a memo is Approved only after the last approver of the current round, terminal states are terminal.
  - Numbering: concurrent allocations are unique and gapless per scheme and period.
  - Audit chain: any modification, deletion or reordering of entries is detected by the verifier.
  - Condition language / DoA rules: evaluation is total (never throws on valid input) and deterministic.
  - Serialization of workflow definitions: round-trip stable.
- Inject `java.time.Clock` everywhere; tests control time explicitly. Never call `Instant.now()` directly in domain code.

### 3.2 Module and architecture tests
- Each Modulith module has `@ApplicationModuleTest` tests verifying its public API and the events it publishes (use the Scenario API for event-driven flows).
- `ApplicationModules.of(App::class.java).verify()` runs in CI.
- ArchUnit rules (examples): controllers do not access jOOQ directly; no module touches another module's tables; no use of session-level `SET`; no `Instant.now()` in domain packages; no logging of types marked sensitive.

### 3.3 Persistence and tenant isolation
- All persistence tests run against PostgreSQL in Testcontainers with the **real Flyway migrations**, connecting as the **application role** (not the owner) so RLS is actually enforced.
- **RLS meta-test (mandatory, PR stage):** queries the catalog and fails if any table with a `tenant_id` column lacks `ENABLE` + `FORCE ROW LEVEL SECURITY` and a tenant policy, or if the app role owns any table or has `BYPASSRLS`.
- **Cross-tenant tests per module:** create data in tenant A, act as tenant B through every repository and endpoint — read, list, search, update, delete, export, and via joins/views/functions — and assert nothing is visible or modifiable.
- **Tenant-context tests:** a db-scheduler job and an async event listener each run with the correct tenant; the same code without context fails closed.
- Use a **fresh tenant per test class** (via the demo-tenant builder) so tests can run in parallel without cleanup races.

### 3.4 Authorization matrix
- Each epic's `design.md` contains an authorization matrix (role/attribute × action → allow/deny). Store it as a machine-readable file next to the tests and **generate parameterized tests** from it: every allowed cell gets a positive test, every denied cell a negative test.
- **Coverage meta-test:** every API endpoint must appear in some matrix; an endpoint without an authorization rule fails the build (deny-by-default enforced by tests).
- Include attribute cases: classification above clearance, outside unit scope, recused member, delegate acting outside the delegation window, deactivated user, suspended tenant.

### 3.5 API integration
- Run the full application with Testcontainers dependencies.
- For every endpoint: success, validation errors (Problem Details shape), authentication required, authorization denied, not-found vs forbidden behaviour (do not leak existence across tenants), optimistic-locking conflict, idempotent replay of mutating requests, pagination stability.
- Assert the **audit event** for every state-changing endpoint using a shared helper (`assertAudited(action, objectId, actor)`).
- Security headers and CSRF behaviour tested for cookie-authenticated endpoints.

### 3.6 Contract
- CI regenerates the OpenAPI spec and fails on unreviewed differences in `/contracts`.
- The generated TypeScript client must compile against the web app on every PR.
- **Schemathesis** runs nightly against a deployed environment, generating requests from the spec to find 5xx errors, schema violations and crashes.
- Breaking API changes require a new version path or a documented deprecation window (mobile apps in the field depend on old versions).

### 3.7 Concurrency
- Required tests: two approvers / two devices / double-click on the same task produce exactly one action; simultaneous resubmit and withdraw resolve deterministically; concurrent numbering allocations; concurrent book publish and late change; a db-scheduler task runs once across two application instances.
- Use a harness that releases N threads at once (latch/barrier) and asserts final state and audit trail.

### 3.8 Documents and files
- **Hostile-file corpus** (checked into a restricted test resources folder): EICAR test file, zip bomb, deeply nested archive, PDF with huge page count, malformed PDFs, polyglot files, wrong extensions, oversized files, documents with macros, documents with external references. Each must be rejected or neutralized within limits, without exhausting memory.
- **Fuzzing (nightly, time-boxed):** Jazzer on upload handling, content detection and any custom parsers.
- **ONLYOFFICE integration (Testcontainers):** editor config JWT signing, callback flow (save, force-save, error statuses), rejection of unsigned/forged callbacks, document-key/version behaviour, conversion API.
- **PDF golden tests:**
  - Generated PDFs (final memo rendition, minutes, board books) validate with **veraPDF** where PDF/A is required.
  - Text extraction of generated PDFs matches expected content.
  - **Render-and-compare:** render key pages to images and compare with approved golden images (tolerance-based) — mandatory for pages containing complex scripts (Indic such as Bengali, Arabic) in names, remarks, stamps and watermarks.
- **Signatures (Phase 5):** signed PDFs validate with EU DSS validation and open as validly signed in standard PDF readers; tampering after signing is detected; long-term validation data present at the configured level.

### 3.9 Mutation testing
- PIT runs nightly on critical modules: `workflow`, `authz`, `audit`, numbering, `voting`, `signature`. Target mutation score ≥ 70% (raise over time); a drop of more than 5 points fails the nightly run.

### 3.10 Migrations and upgrades
- PR: all migrations apply to an empty database.
- Nightly: restore the previous release's schema with seeded data, apply new migrations, run the smoke suite.
- **N-1 compatibility:** the previous app version's smoke tests run against the new schema (proves expand/contract discipline for rolling upgrades).

---

## 4. Web testing in detail

- **Unit/component:** Vitest + Testing Library; test what the user sees and does (roles, labels, text), not implementation details. Mock the network with **MSW** using the generated API types so mocks cannot drift from the contract.
- **Forms:** validation rules, error display, keyboard submission, disabled/pending states.
- **End-to-end:** Playwright against the full stack (Docker Compose) with the seeded demo tenant.
  - Authenticated sessions via storage-state fixtures per role (initiator, approver, secretary, director, tenant admin).
  - **Critical journeys** maintained as a list in this repo; at minimum: login with MFA; the Phase 2 first-slice memo scenario (create → route → approve → return → new version → resubmit → round 2 → approve); admin reassignment; search respects permissions; later phases add their journeys.
  - Each test creates its own data through the API, not through the UI, except for the behaviour under test.
  - PR: Chromium smoke set. Nightly: full set on Chromium, Firefox and WebKit.
- **Visual regression:** Playwright screenshots of key screens in light/dark and LTR/RTL; reviewed and approved like code.
- **Accessibility:** axe checks in component tests and every e2e page visit (zero serious/critical violations); a manual keyboard and screen-reader checklist per phase for the main journeys, including the director reading experience.
- **i18n:** pseudo-localization build (accented, 30–40% longer strings) in nightly e2e to catch hard-coded strings and truncation; RTL layout screenshots; ICU message syntax lint.
- **Performance budgets:** Lighthouse CI on key pages (bundle size, LCP, interaction latency); route-level code splitting verified.
- **Security:** CSP and security headers asserted in e2e; no tokens or sensitive data in `localStorage`/`sessionStorage` (automated check); XSS payload tests in rich-text remarks and file names.

---

## 5. Mobile testing in detail

- **Shared KMP core:** `commonTest` suites for sync engine, idempotent queueing, conflict resolution, domain rules and crypto abstractions (with platform fakes); run on JVM, iOS simulator and Android.
- **iOS:** XCTest unit tests; XCUITest for UI flows; PDFKit/PencilKit annotation behaviour tests on iPad simulators.
- **Android:** JUnit and Robolectric where useful; Compose UI tests; instrumented tests on emulators.
- **End-to-end:** Maestro flows against staging with the seeded demo tenant (approve/return on mobile in 6a; book download, annotate, vote, sign in 6b).
- **Offline and sync:** scripted network conditions (offline, high latency, flapping); queued actions replayed exactly once (idempotency keys); conflict scenarios for annotations; app killed mid-sync; clock skew between device and server.
- **Security (MASVS/MASTG):** map applicable MASVS controls to MASTG tests in `docs/security/masvs-checklist.md`; verify encrypted storage, no sensitive data in logs, backups, screenshots, app switcher snapshots, pasteboard or push payloads; certificate pinning and its rotation; remote wipe; jailbreak/root signals; MobSF static analysis on every release build.
- **Device matrix:** minimum and latest supported OS versions; iPad sizes first; at least one low-end Android device profile.

---

## 6. Security testing program

| Activity | Tooling | Cadence | Gate |
|---|---|---|---|
| SAST | Semgrep CE (custom rules for our patterns: no session `SET`, no presigned URLs to clients, no logging of sensitive types), SpotBugs + FindSecBugs, eslint security rules | Every PR | No new high findings |
| Dependency / license scan | Trivy or OWASP Dependency-Check; license checker against the allow-list | Every PR + daily | No known critical/high vulnerabilities without an accepted, time-boxed exception |
| Secret scanning | gitleaks (pre-commit + CI) | Every commit | Zero findings |
| Container & IaC scan | Trivy | Every PR + daily | No critical/high |
| DAST | OWASP ZAP baseline (passive) | Nightly against dev | No new high alerts |
| DAST (active, authenticated) | OWASP ZAP with authenticated contexts per role | Weekly against staging | Triage within 3 working days |
| Tenancy & authorization | RLS meta-test, cross-tenant suites, authorization matrix tests | Every PR | 100% pass |
| Threat-model tests | One test per STRIDE mitigation where testable | Per epic | Listed in `test-plan.md` |
| ASVS / MASVS mapping | `docs/security/asvs-checklist.md`, `masvs-checklist.md` linking requirement → test or evidence | Updated per epic | Reviewed at each phase exit |
| External penetration test | Independent firm | Before M1, before M3 (board features), before M5, and yearly | No open critical/high at milestone |

**Remediation SLAs:** critical 72 hours, high 7 days, medium 30 days, low next planned release. Exceptions are documented with owner and expiry.

---

## 7. Performance and resilience

- **Volumes:** a synthetic data generator (part of the demo-tenant tooling) produces realistic volumes per tenant, e.g. 1 million memos with history, 10,000 active workflow instances with timers, 5,000 users, 1,500-page board books, 10 years of audit events. Exact figures are confirmed in Phase 0 with the non-functional targets.
- **Gatling simulations** per critical journey (inbox load, memo open, approve, search, book open, publish), run as:
  - Smoke (nightly, small load) — catches gross regressions.
  - Load and stress (weekly and before each release) — verify budgets and find the breaking point.
  - Soak (8–24 hours, before milestones) — memory leaks, connection exhaustion, job backlog.
- **Database:** `pg_stat_statements` reviewed after each perf run; the slowest queries get explicit fixes; `EXPLAIN` plans for new list/search queries are reviewed against seeded volumes during development.
- **Release regression gate:** p95 latency of key endpoints must not regress by more than 10% against the previous release.
- **Resilience (weekly + release):** Toxiproxy-injected latency and failures for PostgreSQL, SeaweedFS, ONLYOFFICE, ClamAV and SMTP — the app degrades gracefully, jobs retry idempotently, nothing is half-written; kill an app node mid-job and verify db-scheduler resumes; database failover in the HA test environment.
- **Backup and restore drill** (automated, before every milestone and quarterly in production): restore to a clean environment, verify audit-chain integrity and object-lock state, measure against RPO/RTO.

---

## 8. Environments and test data

| Environment | Purpose | Data |
|---|---|---|
| Local | Development | Seeded demo tenant |
| CI ephemeral | PR and nightly suites | Fresh per run |
| Dev | Integration of merged work, nightly DAST | Demo tenant |
| Staging | Production-like; e2e, weekly DAST, resilience | Demo tenant + synthetic volumes |
| Performance | Load, stress, soak | Generated volumes |
| Pilot UAT | Customer acceptance | Customer-configured test tenant, no production data unless the customer explicitly authorizes it under their policy |
| Production | Live | Real data — never copied down |

- The demo tenant is defined as code and versioned with the application; it contains every role, a realistic org tree, memo types with templates, workflows (including the first-slice default), forums and sample documents in each supported language/script.

---

## 9. CI stages

| Stage | Contents | Target duration |
|---|---|---|
| Pre-commit | Formatting, lint, gitleaks | Seconds |
| **PR** | Compile, unit, short property runs, module + architecture tests, persistence/RLS (incl. meta-test), cross-tenant, authorization matrix, API integration, OpenAPI drift + client compile, key concurrency tests, core document tests, web unit/component, Playwright smoke (Chromium), axe, SAST, dependency/license, secret, container scans, migrations on empty DB | ≤ 15 min (parallelized) |
| **Nightly** | Full e2e cross-browser, visual regression, pseudo-localization, long property runs, mutation testing, Schemathesis, Jazzer fuzzing (time-boxed), PDF golden tests, mobile UI + Maestro suites, upgrade + N-1 migration tests, ZAP baseline, Gatling smoke, Lighthouse CI | ≤ 2 h |
| **Weekly** | Authenticated ZAP active scan, Gatling load/stress, resilience suite | — |
| **Release** | All of the above green; performance regression gate; soak (milestones); backup/restore drill; install and upgrade tests (Helm and Docker Compose, including air-gapped from Phase 7); manual exploratory charters; manual accessibility checklist; UAT sign-off for pilot milestones; SBOM and signed images published | — |

---

## 10. Test hygiene

- **Flaky tests are bugs.** A flaky test is quarantined (with a linked issue) within 24 hours and fixed or deleted within the next iteration. PR-stage tests may not use automatic retries; nightly e2e may retry once, and every retry is reported.
- **Isolation:** unique tenant per test class; no shared mutable fixtures; tests runnable in any order and in parallel.
- **Time:** controlled `Clock` in backend, fake timers in web tests; never `sleep` — wait on conditions with timeouts.
- **Naming:** test names state behaviour (`returned memo restarts round from first approver`), so failures read as specifications.
- **Coverage:** a signal, not a goal. Minimums: domain core modules ≥ 90% line coverage plus the mutation target above; overall backend ≥ 80%; web critical components covered by component tests. Coverage may not drop on a PR without justification.
- **Test code quality:** same review standard as production code; builders and helpers over copy-paste.

---

## 11. Acceptance, exploratory, usability and UAT

- Every task's Given/When/Then acceptance criteria map to at least one automated test (usually API integration or e2e); the mapping is listed in `test-plan.md`.
- **Exploratory testing:** each epic defines 2–4 time-boxed charters (e.g. "try to see a memo you should not see", "break resubmission with two tabs open") and records findings.
- **Usability:** wireframes validated before build; a short moderated usability session on the main journeys before each phase exit (pilot users or proxies), with findings triaged.
- **UAT:** for each pilot milestone (D1 demo, M1, M2, …), scripted UAT scenarios are written from the pilot onboarding inputs and signed off by the customer.

---

## 12. Mandatory special tests per phase

| Phase | Required beyond the standard layers |
|---|---|
| 0 | RLS meta-test and a deliberate negative test (RLS disabled ⇒ test fails); CI stages wired; demo tenant seeding; ZAP baseline running |
| 1a | Tenant-context propagation (jobs, async listeners) with fail-closed proof; audit-chain tamper detection; same-transaction audit (rollback leaves no audit and no change); hostile-file corpus; streamed download with range requests; MFA, lockout, session revocation, step-up; rate limits |
| 1b | SSO against Entra ID, ADFS, generic OIDC test IdP and LDAP (containerized test IdPs where possible); token issuer flows (PKCE, refresh rotation, device binding); key rotation and crypto-shredding; HR sync dry-run diff; break-glass audit visibility |
| 2 | **First-slice e2e scenario**; property-based workflow invariants; double-action concurrency; round counting; version binding; document lock during review; clearance re-check on assignment; timers/escalations with controlled clock; version pinning of in-flight instances; 10,000-instance load test |
| 3 | ONLYOFFICE integration and security (forged callbacks rejected, no outbound network); gapless numbering under concurrency; PDF/A validation; complex-script golden tests with the pilot's languages; permission-filtered search; legacy import reconciliation; **external pen test + ASVS L2 verification before M1** |
| 4 | Recusal enforced across API, search, notifications and exports; quorum rules; iCalendar invites accepted by common clients; resolution numbering under concurrency |
| 5 | 1,500-page book compile within budget; per-member watermark correctness; signature validation (DSS + common readers) and tamper detection; vote integrity and secret-ballot privacy; real-time updates across multiple nodes; external pen test of viewer, voting and signing before M3 |
| 6a | MASTG suite for approver app; offline approval queue replay exactly once; remote wipe; push payloads contain no sensitive data |
| 6b | Offline meeting cycle with annotation conflicts; large-book download/resume; annotation coordinate parity with web |
| 7 | Air-gapped install and upgrade from documentation only; DR and restore drills against RPO/RTO; soak; full external pen test; accessibility audit |
| 8 | AI evaluation harness (quality, hallucination); adversarial permission-leak tests (zero leaks); no customer data leaves the trust boundary (network egress test) |

---

## 13. `test-plan.md` template (per epic)

```markdown
# Test plan — <epic>

## Scope and risks
<what is new; highest risks; links to design.md and threat-model.md>

## Tests per slice
| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|

## Authorization matrix tests
<link to machine-readable matrix; count of allow/deny cases>

## State-machine tests
<every allowed and forbidden transition>

## Security tests
<STRIDE mitigation → test; ASVS/MASVS IDs covered>

## Performance
<budgets, volumes, Gatling scenarios>

## Accessibility and i18n
<axe scope; manual checklist items; languages/scripts covered>

## Exploratory charters
<2–4 charters>

## Acceptance mapping
<task acceptance criterion → automated test>

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS/MASVS checklists updated
```
