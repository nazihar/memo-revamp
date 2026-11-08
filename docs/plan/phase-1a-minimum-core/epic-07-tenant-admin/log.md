# E07 Tenant lifecycle and administration — log

## 2026-11-08
- E06 PR #28 is CI-green and waits for the owner's merge go-ahead; E07 starts on `feat/1a-e07-tenant-admin`, stacked on E06 (it builds on the mail settings).
- Plan written: research (R17 terminology, R18 operator separation, suspension, audit export, branding contrast, language packs), ADR-0031 (operator path), ADR-0032 (terminology overrides), ADR-0033 (audit viewer and export), design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Decisions worth knowing:
  - Operators use the same application on their own host (`admin.<base domain>`), with their own accounts, cookie, session principal and filter chain. Their requests run only in platform scope, where every tenant table's RLS returns nothing; the one write into a tenant (the first admin's invitation) goes through a single class that refuses once the tenant has an active admin.
  - A dedicated database role for operators was considered and deferred to Phase 7: every install's DBA-run role script would change, and `SET LOCAL ROLE` can be reset by the code it guards against.
  - The first operator is created by a command mode of the backend image (`operator-invite`), which prints the enrolment link to its own output, not to the log.
  - Terminology overrides are term arguments with translator-owned defaults and Arabic gender through ICU `select`, not per-tenant message overlays.
  - Branding is its own module, because `files` already depends on `tenancy`.
  - Language packs stay split per locale (the English catalogue is 7 KB gzip); `i18n:verify` gets a size limit as the trigger for per-route splitting.
- T1 built: operator accounts (`identity_operator`, platform-scope RLS), enrolment link from the `operator-invite` command, password + forced TOTP enrolment, lockout (5 in a row, 30 minutes), step-up, operator cookie `MEMO_OPERATOR` chosen by host, a separate security chain for `/api/v1/platform/**`, host routing in the tenant resolution filter, a demo operator. The authorisation matrix now runs files with `host: platform` on the operator host with their own subjects (50 new cells, 542 in total).
- Findings:
  - Both hosts share one Spring Session table, so a tenant session id sent under the operator cookie name does load a session there. What keeps them apart is the principal attribute: on the operator host only `memo.operator` is read, on tenant hosts only `memo.principal`. `OperatorPathIT` sends each side's session id under the other's cookie name to prove it.
  - The per-account login rate limit (10 per 15 minutes) would answer 429 before a lock at 10 failures could ever be seen; operators now lock at 5.
  - Operator tables follow the module prefix rule (`identity_operator`, not `platform_operator` as first drafted); the one-time link is a hash on the operator row instead of a separate token table.
