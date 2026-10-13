# AGENTS.md — Standing instructions for the coding agent

Read this file fully before every session. It defines what we are building, the locked technical decisions, the rules you must never break, and **how you plan each phase before writing code**.

Companion documents:
- `docs/plan/MASTER_PLAN.md` — the phase roadmap (what to build, research questions, exit criteria).
- `docs/plan/TESTING_STRATEGY.md` — mandatory testing layers, tools and practices.
- `docs/plan/STATUS.md` — where work currently stands. **Read it at the start of every session.**

## 0. Session protocol

You lose context between sessions. To keep work continuous:

1. **Start of session:** read this file, then `docs/plan/STATUS.md`, then the current phase/epic folder (`docs/plan/phase-<N>-<slug>/`). Do not start new work until you know the current phase, epic, slice, open blockers and agreed next step.
2. **During the session:** work on the slice named in `STATUS.md`. If you need to deviate, record why.
3. **End of session (or before stopping for approval):** update `STATUS.md` — what was done, what is in progress (with branch/PR), what is next, blockers, decisions taken, and questions waiting for the human. Keep it short and current; move history into the phase folder's `log.md`.
4. Never assume something was done in a previous session — verify in the code, tests and `STATUS.md`.

---

## 1. What we are building

A multi-tenant SaaS (also deployable single-tenant and on-premise) that manages an organization's **decision pipeline** end to end:

```
Memo raised → approval chain (positions + delegation-of-authority rules)
  → escalated to a forum (committee / board) → agenda item
  → meeting with a published board book → minutes + numbered resolution
  → tracked action items → follow-up memos (loop closes)
```

Two product families are unified here:

- **E-office / memo management** (day-to-day): memos as files with an append-only note sheet, routing, approvals, delegation, numbering, archive.
- **Board portal** (governance): agendas, board books, secure reading and annotation, voting, resolutions, minutes, signatures, action tracking.

Target customers are large organizations of any sector. The first pilot customer is a **bank**, so security, auditability and on-prem deployability are first-class requirements, not add-ons. **Never build bank-specific code paths. Everything that differs between organizations is tenant configuration.**

---

## 2. Locked technical decisions

These are decided. Do not change them without writing an ADR (see §5, Step 2) and getting explicit human approval.

| Area | Decision |
|---|---|
| Backend | Kotlin + Spring Boot 4.x, organized as a **modular monolith with Spring Modulith** |
| JVM | Current Java LTS supported by the chosen Spring Boot and Kotlin versions (verify; Java 25 at time of writing) |
| Database | PostgreSQL. Tenant isolation with **Row-Level Security (ENABLE + FORCE)**. Migrations with Flyway (per Modulith module) |
| Data access | jOOQ (typed SQL) — verify license edition for PostgreSQL before adopting |
| Background jobs / timers | db-scheduler (PostgreSQL-backed). **No Redis** unless an ADR proves it necessary |
| Auth | Spring Security: OIDC, SAML 2.0, LDAP; MFA; per-tenant identity-provider configuration |
| Web sessions | Server-side sessions in PostgreSQL (Spring Session JDBC), `HttpOnly` + `Secure` + `SameSite` cookie, CSRF protection. **No tokens in browser storage** |
| Mobile tokens | Spring Authorization Server inside the backend: OAuth 2.1 authorization code + PKCE, federating to the tenant IdP; short-lived access tokens; rotating, device-bound refresh tokens |
| Caching / coordination | Caffeine in-process caches; cross-node invalidation and real-time fan-out via PostgreSQL `LISTEN/NOTIFY`; stateless app nodes |
| Rate limiting | Bucket4j with a PostgreSQL-backed store (verify) |
| Object storage | S3 API only (AWS SDK for Java). Default server: **SeaweedFS**. Bucket object lock enabled at creation for record buckets. Clients never talk to storage: **downloads stream through the backend** (decrypt + HTTP range); no presigned URLs to browsers or apps |
| Office documents | **ONLYOFFICE Docs** (self-hosted) for in-browser .docx/.xlsx/.pptx editing and conversion; integrated via its Docs API (JWT-signed configs + callback handler) as a separate service |
| PDF | Apache PDFBox (assembly, bookmarks, stamping, watermarking); Apache Tika (text extraction, type detection) |
| Digital signatures | EU DSS (PAdES); internal CA (step-ca or EJBCA Community — decide by ADR) |
| Web | React + TypeScript (strict), **TanStack Router (SPA)**, TanStack Query/Table/Form/Virtual, shadcn/ui on Radix, React Flow (workflow builder), dnd-kit (agenda builder), PDF.js (viewer) |
| API contract | REST, OpenAPI 3.1 generated from the backend (springdoc). Web client generated with `@hey-api/openapi-ts` (TanStack Query plugin) — pin the version, it is pre-1.0 |
| Mobile | **Kotlin Multiplatform** shared core + native UI: SwiftUI (iPad/iPhone, PDFKit + PencilKit) and Jetpack Compose (Android) |
| KMP ↔ Swift interop | Objective-C export + SKIE. Swift export is experimental — do not adopt until stable (re-check each phase) |
| Deployment | Containers. Helm chart for Kubernetes; Docker Compose profile for small on-prem installs. Same artifacts for SaaS, dedicated and on-prem |

---

## 3. Non-negotiable rules

### 3.1 Dependencies and licensing
- **No runtime dependency on third-party SaaS** (no hosted auth, no hosted e-signature, no hosted storage, no hosted AI). Exceptions only by ADR, and only as *optional* tenant-enabled connectors (e.g. calendar sync). Mobile push (APNs/FCM) is unavoidable: push payloads must carry **no sensitive content**, only "you have N pending items".
- Allowed licenses for libraries linked into our code: Apache-2.0, MIT, BSD, ISC, EPL-2.0, MPL-2.0, LGPL (as unmodified library). **AGPL and GPL software may only run as a separate, unmodified service** (e.g. ONLYOFFICE, ClamAV) and must be recorded in the license register with the obligations noted.
- Every new dependency → add a row to `docs/dependencies.md`: name, version, license, purpose, maintenance status (last release date, maintainer activity), alternatives considered. Reject libraries that are abandoned, pre-release (unless pinned and justified), or single-maintainer critical-path without an ADR.
- Generate an SBOM (CycloneDX) in CI. Scan dependencies and container images on every build.

### 3.2 Multi-tenancy
- Every tenant-owned table has `tenant_id`, RLS **ENABLED and FORCED**, and a policy using `current_setting('app.tenant_id', true)`.
- The application connects as a non-owner role without `BYPASSRLS`. A separate migration role owns the schema.
- Tenant context is set with **`SET LOCAL`** (or `set_config(..., true)`) **inside every transaction**, never with session-level `SET`. Because `SET LOCAL` only works inside a transaction, **every database access — reads included — runs inside a transaction** opened by the tenant-aware transaction layer.
- Tenant context is **explicitly propagated** into db-scheduler jobs, async Spring Modulith event listeners, executors and any coroutine/thread hop. Every job payload and every event carries `tenantId`. Code that touches tenant data without a context must **fail closed** (exception), never fall back to "all tenants".
- Write RLS policies so the setting is evaluated once per query: `USING (tenant_id = (select current_setting('app.tenant_id', true))::uuid)`.
- Every module has an automated **cross-tenant access test** (tenant A cannot read, update or delete tenant B data, including through joins, views and functions).
- Platform-level (cross-tenant) operations use a separate, audited admin path.

### 3.3 The record is sacred
- Note sheets, approvals, votes, published book versions, minutes and resolutions are **append-only**. Corrections are new entries that reference what they correct.
- The audit log is append-only and **hash-chained per tenant** (each entry stores the hash of the previous entry in that tenant's chain). Periodically anchor chain heads into an object-locked bucket.
- Audit records are written **in the same transaction as the business change** (transactional outbox via Spring Modulith's event publication registry). A business change without its audit record must be impossible.
- Sensitive reads are audited at **document-open granularity**, not per page or per request.
- Every state-changing action emits an audit event: who (user, acting-as/delegation), what, which object, before/after where relevant, when (server UTC), from where (IP, device, client).
- Never hard-delete business records. Retention and legal hold are explicit policies.

### 3.4 Security baseline
- Target **OWASP ASVS 5.0 Level 2** for all web/API work from Phase 1; Level 3 for authentication, authorization, cryptography and audit modules. Mobile targets **OWASP MASVS** (with MASTG tests).
- Authorization is **deny-by-default** and checked server-side for every request: role + forum membership + document classification + conflict-of-interest recusal (ABAC). Never rely on the UI hiding things.
- Encryption: TLS everywhere; at rest via storage encryption plus **per-tenant envelope encryption** for documents (tenant data key wrapped by a key-encryption key held in a KMS/HSM; self-hosted option e.g. OpenBao — decide by ADR).
- Secrets never in code, config files, logs, or test fixtures.
- Uploaded files: size limits, type detection by content (Tika), malware scan (ClamAV as a service) **before** encryption, stored under random object keys, never served from the app origin without `Content-Disposition` and strict headers.
- **Hostile-file limits** everywhere a file is parsed: max size, page count, decompression ratio, nesting depth and processing time in Tika, PDFBox (file-backed memory mode, never fully in-heap for large files) and conversion. Conversion services (ONLYOFFICE, any renderer) run with **no outbound network access**, CPU/memory limits, macros and plugins disabled.
- **Step-up authentication** (fresh MFA within a configurable window) for approve, sign, vote and security-setting changes, configurable per memo type / forum.
- **Rate limiting** on login, OTP, password reset, upload, search and export.
- **No standing operator access** to tenant data. Support access only through the break-glass flow (customer-approved, time-boxed, audited, visible to the tenant).
- Never put sensitive content in URLs, push payloads, email subjects or logs.
- Security-relevant decisions are documented in the phase threat model (STRIDE).

### 3.5 Configuration over code
- Memo types, numbering schemes, workflow definitions, forum types, templates, classification levels, retention policies, notification rules and terminology are **tenant data**, editable by tenant admins, versioned, and (for sensitive ones) protected by maker-checker.
- If you find yourself writing `if (tenant == X)` or a customer-specific branch, stop and model it as configuration.

### 3.6 Internationalization and time
- All UI strings externalized (ICU message format) from day one. Support multiple languages and right-to-left scripts in layout.
- Store timestamps in UTC; render in the user's time zone. Meeting times carry an explicit time zone.

### 3.7 Accessibility
- Web meets **WCAG 2.2 AA**. Automated axe checks in CI plus manual keyboard and screen-reader checks for key flows. The director reading experience must work for older, non-technical users: large targets, minimal chrome, no hidden gestures.

---

## 4. Engineering conventions

### Repository layout (monorepo)
```
/backend            Gradle (Kotlin DSL), Spring Boot app, one package per Modulith module
/shared             KMP module(s) shared by mobile (and JVM where useful): domain models, validation, sync protocol
/web                pnpm + Vite + React app
/mobile/ios         Xcode project (SwiftUI) consuming the shared KMP framework
/mobile/android     Android app (Compose) consuming the shared KMP module
/contracts          Generated OpenAPI spec (committed; CI fails on unreviewed drift)
/deploy             Helm chart, Docker Compose profiles, ops runbooks
/docs               plan/ (MASTER_PLAN.md, TESTING_STRATEGY.md, STATUS.md, phase-<N>-<slug>/), adr/, research/, security/ (ASVS/MASVS checklists), dependencies.md, threat-models/, runbooks/
CLAUDE.md           One-line pointer to this file (for agents that read CLAUDE.md)
```

### Backend
- One Spring Modulith module per bounded context (e.g. `tenancy`, `identity`, `org`, `authz`, `audit`, `files`, `workflow`, `memo`, `forum`, `meeting`, `book`, `voting`, `minutes`, `actions`, `signature`, `notification`, `search`). Modules talk through published APIs and **application events**; `ApplicationModules.of(...).verify()` runs in CI.
- Kotlin style enforced by ktlint + detekt. JSpecify nullness respected.
- REST conventions: `/api/v1/...`, errors as RFC 9457 Problem Details, cursor pagination, optimistic locking (ETag/If-Match or version column), **idempotency keys** on all mutating endpoints called by mobile/offline clients.
- Tests: follow `docs/plan/TESTING_STRATEGY.md`. JUnit 5 + Testcontainers (PostgreSQL, SeaweedFS, ONLYOFFICE where needed). No mocking of the database for persistence tests.

### Database patterns
- `tenant_id` is the **first column of every composite index** on tenant tables; every foreign key has an index.
- Primary keys: UUIDv7 (time-ordered) generated by the application.
- Optimistic locking with a `version` column on mutable aggregates; state transitions (approve, return, vote) use a **guarded update** (`UPDATE … WHERE id = ? AND state = ? AND version = ?`) and treat 0 rows as a conflict — this, plus idempotency keys, prevents double actions.
- **Gapless business numbers** (memo and resolution numbers) come from a counter row locked with `SELECT … FOR UPDATE` inside the business transaction — never from a PostgreSQL sequence.
- Denormalize what hot screens need (e.g. current assignee and current step on the memo row for the inbox); keep the normalized history as the source of truth.
- Large append-only tables (audit, note sheet, notifications) are **partitioned by time**; plan retention per partition.
- Migrations are **backward-compatible (expand → migrate → contract)** so a release can roll out with old and new app versions running together. Never rename or drop a column in the same release that stops using it.
- No N+1 queries: list endpoints are written as set-based queries; review `EXPLAIN` for every new list/search query against seeded volumes.
- Use keyset (cursor) pagination; never `OFFSET` on large tables.

### Performance rules
- Anything slower than ~200 ms or touching files (conversion, text extraction, PDF compilation, watermarking, email) runs as a **background job** with progress status; the API returns immediately.
- Convert and extract documents **at upload time**, not when they are first needed.
- Generate per-user artifacts (e.g. watermarked board-book copies) **once per publish**, not per view.
- Cache read-mostly configuration (memo types, workflow definitions, org tree, permissions) in Caffeine with event-driven invalidation; never cache document content in the application.
- Stream files; never load a whole document into memory to serve it.

### Test data and fixtures
- One shared, seeded **demo tenant** (org tree, users in each role, memo types, workflows, forums) defined as code, used by integration tests, end-to-end tests, local development and demos.
- Test-data builders for every aggregate; no copy-pasted JSON fixtures. Never use real customer data in tests or in non-production environments.

### Web
- TypeScript `strict`. ESLint + Prettier (or Biome — pick one by ADR and stick to it).
- Server state only through the generated TanStack Query options; no hand-written fetch calls to our API.
- Unit/component tests with Vitest + Testing Library; end-to-end with Playwright; accessibility with axe.
- Strict Content Security Policy; no inline scripts.

### Mobile
- Shared KMP core owns: API client (Ktor), offline store, sync engine, crypto/key handling abstraction, domain rules. Platform code owns UI, secure key storage (Keychain/Secure Enclave, Android Keystore), PDF rendering and annotation capture.
- Evaluate the encrypted local database option by ADR (e.g. SQLDelight or Room-KMP with SQLCipher). **Do not adopt brand-new, low-adoption drivers on the security path.**

### Observability
- OpenTelemetry traces/metrics/logs; Micrometer; structured JSON logs with tenant id and correlation id; **never log document content, tokens or personal data beyond ids**.

### Git and CI
- Small, reviewable PRs, one vertical slice each. Conventional commit messages.
- CI gates: build, tests, Modulith verify, lint, type-check, OpenAPI drift check, dependency/license scan, SBOM, container scan, secret scan, axe checks, migrations apply cleanly on an empty and on a seeded database. Stages (PR / nightly / release) are defined in `TESTING_STRATEGY.md`.
- All versions pinned (Gradle version catalog `gradle/libs.versions.toml`, pnpm lockfile). Automated update PRs (Renovate or Dependabot) must pass every gate; security updates are merged within the SLA set in `TESTING_STRATEGY.md`.

---

## 5. How you plan a phase (MANDATORY before writing code)

For every phase in `docs/plan/MASTER_PLAN.md`, produce a **low-level plan** first, in `docs/plan/phase-<N>-<slug>/`. Do not start implementation until a human has reviewed and approved it.

**Plan in two levels.** A whole phase is too large to plan in detail at once:
1. **Phase overview** (`overview.md`): break the phase into **epics** (2–6 weeks each), their order and dependencies, the phase-level research and ADR list, and how the phase's exit criteria map to epics. Get approval.
2. **Epic plans** (`epic-<NN>-<slug>/`): for each epic, just before starting it, run Steps 1–7 below in full. Earlier epics' learnings feed later epic plans.

Keep `STATUS.md` pointing at the current phase, epic and slice.

### Step 1 — Research (`research.md`)
- Re-verify **current** stable versions, licenses and maintenance status of every library/tool the phase touches, from official sources (release pages, changelogs, repositories). Your training data is outdated; do not trust remembered versions.
- For each open question listed in the phase's "Research questions", compare at least two options with pros, cons, license, maturity and risk, and recommend one.
- Study how industry leaders handle the phase's features (public product docs, help centers, release notes, reviews) and record what to adopt and what to do better.
- Cite every source with a URL and access date.

### Step 2 — Decisions (`docs/adr/NNNN-<title>.md`)
- One ADR per significant decision (context, options, decision, consequences). Use the MADR format.

### Step 3 — Design (`design.md`)
- Domain model (entities, invariants, lifecycle/state machines) and ERD (Mermaid).
- Module boundaries and the events each module publishes/consumes.
- API contract sketch (endpoints, payloads, errors, permissions per endpoint).
- Authorization matrix (who can do what, under which attributes).
- Audit events emitted.
- Tenancy and RLS policies for every new table.
- Migration and backward-compatibility notes (expand → migrate → contract).
- **Performance budget** for each new endpoint/screen (target latency, expected data volumes) and the indexes/queries that meet it.
- **Background work**: which operations are async jobs, their retry/idempotency rules and how tenant context is carried.
- UX flows for the main user journeys (wireframe-level, linked to the screens to build), **validated with at least one pilot user or the product owner before implementation**; record feedback in `ux-review.md`.

### Step 4 — Threat model (`threat-model.md`)
- STRIDE over the new data flows; map mitigations to ASVS/MASVS requirement IDs.

### Step 5 — Task breakdown (`tasks.md`)
- Ordered **vertical slices** (each delivers working, tested behaviour from DB to UI). Each task has: goal, files/modules touched, acceptance criteria (Given/When/Then), tests to write, and estimated size (S/M/L; split anything larger than L).
- Mark dependencies between tasks and what can run in parallel.

### Step 6 — Test plan (`test-plan.md`)
- Follow the template and mandatory layers in `docs/plan/TESTING_STRATEGY.md`. For each slice, list the tests per layer (unit, property, integration, persistence/RLS, authorization matrix, contract, end-to-end, security, performance, accessibility, i18n, resilience/migration where relevant), the seed data needed, and which CI stage runs them.
- Derive authorization tests from the design's authorization matrix and state-machine tests from the design's state diagrams — every allowed **and** every forbidden transition gets a test.

### Step 7 — Review gate
- Summarize the plan, decisions, risks and open questions for the human. Wait for approval. Record approval in the phase folder.

### During implementation
- Work slice by slice. Write or update tests with each slice. Keep the design docs true — if reality differs, update `design.md` and add an ADR if a decision changed.
- If a slice reveals a missing requirement or a conflict with these rules, stop and ask rather than improvising.

### Phase exit
A phase is done only when every item in its **Exit criteria** in the master plan is met, CI is green (including nightly and release stages), the phase's test-plan coverage is complete, a usability check of the main journeys has been done, docs are updated, a demo script exists in the phase folder, `STATUS.md` is updated, and a short `retrospective.md` records what changed versus the plan.

---

## 6. Definition of done (every task)

- Behaviour matches acceptance criteria; tests at the layers required by `TESTING_STRATEGY.md` cover the happy path, permission failures, cross-tenant access, concurrency (double submit) and key edge cases. No skipped or quarantined tests added without a linked issue.
- Audit events emitted and verified by a test.
- Performance budget met against seeded volumes for any new list/search query.
- API documented in OpenAPI; generated web client updated.
- UI strings externalized; accessibility checks pass.
- No new lint/type/security warnings; dependency register updated.
- Docs updated (design, ADRs, runbooks as relevant).

---

## 7. When unsure

Prefer the simpler design that keeps the record immutable, the tenant isolated and the behaviour configurable. If two options are both acceptable, choose the one with fewer moving parts for an on-prem install. If a decision affects security, data model, licensing or customer-visible behaviour, write it down as an ADR and ask.
