# E06 Notifications — research

All sources were accessed on **2026-11-05**.

## R1 — Versions, licences, maintenance

| Component | Current | Decision |
|---|---|---|
| Jakarta Mail API / Eclipse Angus Mail | 2.1.5 / 2.0.5, managed by Spring Boot 4.1.1, already in the build (E03) | Reuse. Angus Mail supports STARTTLS (`mail.smtp.starttls.required`), implicit TLS (`mail.smtp.ssl.enable`) and server identity checks (`mail.smtp.ssl.checkserveridentity`) |
| Spring `MessageSource` (`ResourceBundleMessageSource`) | Spring Framework 7, in the build | **Adopt** for localised email text: UTF-8 property bundles per locale, our `{name}` placeholders. No new dependency |
| ICU4J | 78.x, Unicode-3.0 licence | Not adopted: the licence is not on the allow-list (AGENTS §3.1) and the emails need named placeholders, not plurals |
| Thymeleaf / FreeMarker | Apache-2.0, active | Not adopted for 1a: system-defined templates are text with placeholders plus one HTML layout; a template engine arrives with tenant-editable templates (1b), where sandboxing matters |

Sources: https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html, https://docs.spring.io/spring-framework/reference/core/beans/context-introduction.html#context-functionality-messagesource, https://github.com/unicode-org/icu/blob/main/LICENSE

## R16 — Email delivery without third-party services

### Per-tenant SMTP relay
- Banks run their own relay and require mail from their own domain (SPF, DKIM and DMARC are the relay's job, not ours). Settings per tenant: host, port, TLS mode (`STARTTLS` required, or implicit `TLS`), optional username and password, from address and display name. The platform relay (`MEMO_SMTP_*`) is the fallback when a tenant has none.
- `NONE` (plaintext) is allowed only when the installation permits it (`memo.mail.allow-insecure`, for a local mail catcher); production installs refuse it.
- The relay password is a secret: stored encrypted with `DataKeys` (AES-256-GCM, ADR-0024) bound to the tenant id as associated data, never returned by the API, never logged or audited. A per-tenant key for small secrets is a 1b KMS concern; the binding to the tenant prevents moving a ciphertext to another tenant.

### Retries and bounces
- SMTP replies tell transient (4xx) from permanent (5xx) failures; Angus Mail surfaces them as `SMTPSendFailedException` / `SMTPAddressFailedException` with the return code. Transient failures and connection errors retry with the jobs' backoff (8 attempts over about a day, E03); a permanent failure stops at once and is recorded.
- Asynchronous bounces (a DSN mailed back later) need an inbound mailbox: not in 1a. The delivery log keeps status, attempts, the last SMTP code and an error class (never the server's free text, which may echo addresses).

### Templates and languages
- 1a templates are system-defined (tenant-editable in 1b, overview). Each template has a subject and a body per locale in `notification/messages_<locale>.properties`; English is complete, Arabic proves RTL (Q8). The HTML part is one layout with `dir` and `lang` from the locale, the escaped text, and links as buttons; inline styles only (mail clients drop `<style>`).
- The recipient's locale is the user's `locale`, else the tenant default (`locale.default`, E07), else English.
- Subjects never carry sensitive content (AGENTS §3.4): they are fixed per template and take no parameters.

### How others do it
- Slack lets each user choose which kinds of activity notify them and on which channel, while workspace-level notices stay on. We adopt the same split: an in-app centre as the source of truth, emails that link into the app rather than carry content, per-category preferences per channel, and security notices that cannot be turned off. Digests come with the workflow notifications (Phase 2).

Sources: https://www.rfc-editor.org/rfc/rfc5321#section-4.2.1, https://www.rfc-editor.org/rfc/rfc3463, https://slack.com/help/articles/201355156-Configure-your-Slack-notifications

## In-app notification storage
- Notifications are many and short-lived: every workflow step will add some. AGENTS §4 requires time partitioning for large append-only tables. Notifications change once (read), otherwise append-only.
- **Monthly partitions** created ahead by a Flyway `afterMigrate` callback on the migrator connection, the same pattern as the audit store (ADR-0019); retention by **dropping** partitions older than the retention (13 months), also in the callback, so the app role never needs DDL and no `DELETE` scans large tables.
- Hot queries: the unread count per user and the newest page. Index `(tenant_id, user_id, created_at desc, id)` with a partial index on unread rows; keyset pagination on `(created_at, id)`.
- Real-time delivery is Phase 5; 1a polls the unread count (TanStack Query `refetchInterval` 60 s, paused when the tab is hidden).

## Push payload (design note only)
- APNs and FCM are third parties: payloads carry no content, only a count ("You have 3 new items") and no ids, as AGENTS §3.1 requires. The device fetches details over the authenticated API. Sending arrives in Phase 6a; E06 records the payload shape and the preference channel (`PUSH`) so the model does not change later.
