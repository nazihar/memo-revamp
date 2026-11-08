# E07 Tenant lifecycle and administration — research

All sources were accessed on **2026-11-08**.

## R1 — Versions, licences, maintenance

No new dependency. Everything E07 needs is in the build:

| Need | Component | Notes |
|---|---|---|
| Operator passwords, TOTP, sessions, rate limits | identity module (E03): Argon2id encoder, `Totp`, Spring Session JDBC, `RateLimiter` | Reused as is; operators get their own table and principal |
| Logo upload, scan, encryption | files module (E05), new purpose `tenant.logo` | PNG, JPEG, WebP only; SVG refused (script content) |
| Audit export | `java.util.zip.ZipOutputStream`, `MessageDigest` (JDK) | Streamed; no temp files |
| Colour contrast | WCAG 2.2 relative luminance formula, ~20 lines in Kotlin and TypeScript | No library needed |
| Terminology on the web | react-intl (`select` for gender, values for terms), already in the build | — |

## R17 — Terminology overrides with ICU messages

**Question.** A tenant renames product terms ("unit" → "department", "memo" → "note"). How do overrides reach UI messages and emails without breaking grammar, especially in Arabic (grammatical gender, six plural categories)?

### Options
1. **Per-tenant message overlays.** A tenant overrides whole messages by id. Handles any grammar, but every message containing the term must be overridden in every language, the overlay breaks when a message changes, and a tenant admin edits ICU syntax by hand. Rejected: unmaintainable and error-prone for admins.
2. **Search-and-replace on rendered text.** Replace "unit" in output. Breaks inflection (units, Unit), compounds and other languages; rejected.
3. **Term arguments** (chosen). Messages that mention a term take it as an argument: `"Add {unit}"`. The default value of each term lives in the message catalogue per locale (`term.unit.one`, `term.unit.other`, plus `term.unit.gender` where the language needs it), so translators control the default wording and its gender. A tenant override replaces the forms for one locale; messages that need agreement branch on the gender argument with ICU `select`:
   `"{unitGender, select, feminine {{unit} جديدة} other {{unit} جديد}}"`.
   This is the approach used by Mozilla's Fluent "terms" (`-brand-name` with attributes for gender and case) and by ICU guidance for inflected nouns; it keeps grammar in the translator's hands.

### Grammar limits we accept in 1a
- **Forms:** singular and plural (`one`, `other`) in sentence case as written by the admin, plus a capitalised form derived with `toLocaleUpperCase` on the first letter for languages with case. Arabic has no case.
- **Gender:** `masculine`, `feminine` (Arabic, and any later language that needs it). English ignores it.
- **Counts:** messages do not combine a count with an overridable term (Arabic needs dual and other count forms that a tenant admin should not have to supply). Counted phrases use neutral wording ("Units: 5" → "{Units}: 5").
- **Case** (grammatical case, as in German or Russian): not needed for English and Arabic; a later language pack would add case attributes the same way gender is added.

### Where terms apply in 1a
Memos do not exist yet, so 1a proves the mechanism on the org screens: **unit** and **position**. The catalogue also defines **memo** (unused until Phase 2) so the setting screen and the ADR cover the term the overview names. Emails: no 1a email mentions a term; the backend gets the same term lookup for `MessageSource` templates (`{term.unit}`), proved by a template test.

Sources: https://projectfluent.org/fluent/guide/terms.html, https://unicode-org.github.io/icu/userguide/format_parse/messages/#complex-argument-types, https://www.unicode.org/cldr/charts/latest/supplemental/language_plural_rules.html (Arabic: zero, one, two, few, many, other), https://formatjs.github.io/docs/core-concepts/icu-syntax/#select-format

## R18 — Platform operator separation

**Question.** How do platform operators manage tenants without any access to tenant data, enforced below the UI?

### Separation options
| Option | Isolation | Cost |
|---|---|---|
| Separate application (own process and port) | Strongest: separate code and credentials | Second deployable, second image to sign, scan and run; duplicated auth code |
| **Same application, separate host name, separate filter chain, cookie and account store** (chosen) | Strong: operator endpoints exist only on the operator host; tenant endpoints refuse that host; cookies are host-only so neither side ever receives the other's session | One deployable; reuses identity's hardened password, TOTP and session code |
| Operator role inside a tenant | Weak: operators become tenant users | Rejected (overview Q6) |

### Database layer
- Platform scope (ADR-0017) already sets `app.scope = platform` and **no** tenant id, so every tenant table's RLS policy returns zero rows and rejects writes. `PlatformScopeIT` proves this for every tenant table; the RLS meta-test forces every new tenant table into the same policy. Operator requests run **only** in platform scope, so a bug in operator code cannot read tenant data: the database refuses.
- What operators may write is granted by narrow policies on platform-scope: insert and update the registry row (`tenancy_tenant`), their own operator tables and the platform audit chain.
- **One exception, by design:** inviting a new tenant's first admin writes an account into that tenant. It runs in the new tenant's context through one service (`TenantProvisioning`) that refuses once the tenant has an active admin and returns nothing from the tenant. An ArchUnit rule allows only that class in the platform module to open a tenant transaction.
- **A separate database role** (`memo_operator` with grants on the registry only, entered with `SET LOCAL ROLE`) was considered. It adds defence against a code path that clears `app.scope`, but needs a change to the DBA-run role script on every install (`deploy/db/init/01-roles.sh`; Flyway's role cannot create roles), and `SET LOCAL ROLE` can be reset by the same code it guards against. Deferred to Phase 7 support tooling, with ADR-0006's planned read-only ops role.

### Accounts and authentication
- Operators are not tenant users: table `platform_operator` (platform table, RLS visible only in platform scope). Password (Argon2id, breached-password check), **TOTP always required**, no relaxation; idle timeout 15 minutes, absolute 8 hours; step-up (fresh TOTP, 5 minutes) for suspend, resume and first-admin invitation.
- Bootstrap: the first operator cannot be invited through a console that has no operator yet. The backend gets a command mode, `operator-invite <email>` (same image, run by the owner over SSH: `docker compose run --rm backend operator-invite owner@…`), which creates or resets the operator and prints a one-time enrolment link (24 hours) to its own standard output, never to the application log. Non-production installs seed a demo operator like the demo users.
- Lockout: same throttle and lock as tenant users, fixed (not tenant-configurable).

### How leaders describe operator access to regulated customers
"The platform team manages your organisation's record (name, address, status) on a separate administration site with separate accounts that always need two factors. The database itself refuses them access to your data, every action they take is recorded in a tamper-evident platform log, and the only thing they can create inside your organisation is the invitation for your first administrator before anyone from your organisation has signed in."

Sources: https://www.postgresql.org/docs/18/ddl-rowsecurity.html, https://www.postgresql.org/docs/18/sql-set-role.html, https://owasp.org/www-project-application-security-verification-standard/ (V8.2 operation-level authorisation, V8.4 multi-tenant isolation), https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances

## R19 — Suspension mechanics

- **Requests:** the tenant resolver caches the tenant's status with its id (1 minute, invalidated on change across nodes through the existing LISTEN/NOTIFY channel, ADR-0020). Every tenant API request to a suspended tenant gets 403 `tenant-suspended` from the resolution filter, before authentication: one place, so no endpoint can forget it.
- **Sessions:** suspension deletes every session whose principal index starts with the tenant id (Spring Session's principal index, ADR-0023), in the same transaction as the status change.
- **Jobs:** recurring per-tenant fan-out already lists active tenants only. One-off tenant tasks (mail, scans) for a suspended tenant are rescheduled an hour later without counting a failed attempt, so nothing is lost and nothing runs while suspended.
- **Login:** blocked by the filter (403), so the login form shows "This organisation is suspended", not a password error.
- Resume restores everything; sessions do not come back.

## R20 — Audit export with a verifiable manifest

- The E02 chain is `hash_n = SHA-256(hash_{n-1} || canonical_n)` from a genesis value derived from the tenant id, with anchors (tenant, seq, hash, time) in the object-locked records bucket every 15 minutes (ADR-0019).
- An export is verifiable by anyone if it contains, for a range of sequence numbers, each event's canonical text and hash, the hash before the first event, and a manifest with the range, counts, the last hash, the SHA-256 of the events file and the anchors that fall inside the range. Recomputing the chain from the first `prev_hash` must reproduce every hash and the last hash; an anchor (read separately from the records bucket) confirms the hashes were not rewritten since anchoring.
- Format: a ZIP stream with `events.ndjson`, `manifest.json` and `VERIFY.md` (the algorithm in plain words). JSON lines keep it streamable; no temp files.
- Limits: one year per export, 5 exports per hour per tenant; the export is audited (`audit.exported`) in the same chain.

Sources: https://www.rfc-editor.org/rfc/rfc8785 (JSON canonicalisation background), https://csrc.nist.gov/pubs/sp/800/92/final (log management)

## R21 — Branding colour within accessible contrast

- WCAG 2.2 SC 1.4.3 (text 4.5:1) and SC 1.4.11 (UI components 3:1). The tenant colour replaces the **primary** token (buttons, links, focus accents). Light theme: the colour must reach 4.5:1 against white button text and 3:1 against the page background (`#f6f7f9`). Dark theme uses a second colour, checked against the dark foreground (`#0d1726`, 4.5:1) and background (`#10151d`, 3:1); if the admin gives none, the default dark primary stays.
- The server validates (the source of truth) and the form shows the ratio live. Applied on the web by setting CSS custom properties through the CSSOM, which the strict CSP allows (no inline style attribute or element).

Sources: https://www.w3.org/TR/WCAG22/#contrast-minimum, https://www.w3.org/TR/WCAG22/#non-text-contrast, https://www.w3.org/TR/WCAG22/#dfn-relative-luminance, https://www.w3.org/TR/CSP3/#directive-style-src

## R22 — Language packs per route

- Today English is bundled and every other locale is a code-split chunk loaded on demand (E01). The English catalogue is 26 KB uncompressed (7 KB gzip) after E06.
- Splitting catalogues per route adds a loader per route and a build step that maps message ids to routes. At the current size it saves less than one round trip costs. Decision: keep per-locale chunks; `i18n:verify` (PR stage) fails when a catalogue passes 15 KB gzip, which is the trigger to split per route. The nightly Lighthouse budgets (`web/lighthouserc.json`) stay as they are. Recorded in the design; no ADR needed (reversible).
