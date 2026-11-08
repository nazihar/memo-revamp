---
status: accepted
date: 2026-11-05
deciders: product owner (delegated), agent
---

# 0030 — Notifications: in-app centre, email through tenant relays, localised templates

## Context and problem statement
E03 added transactional email through one platform relay with English text templates. Phase 1a needs an in-app notification centre, per-tenant SMTP relays (banks send from their own domain), localised templates, a delivery log, per-user preferences and a content-free push design (overview E06). Every later phase adds notification kinds, so the shape chosen now must hold. Research: `epic-06-notifications/research.md`.

## Decision drivers
- No third-party services; the customer's own relay.
- Nothing sensitive in subjects, push payloads or logs.
- Producers in any module, in their own transaction; no lost or duplicate mails.
- Large, time-ordered data must be partitioned (AGENTS §4) without DDL rights for the app role.
- No new dependencies, nothing whose licence is outside the allow-list.

## Considered options
1. **One notification API per kind** (`Notifications.notify(recipient, kind, params, link)`): writes the in-app item and queues the email per the recipient's preferences, in the caller's transaction; monthly-partitioned items; `MessageSource` templates
2. Event-driven fan-out: producers publish domain events, a notification module listener maps them to notifications asynchronously
3. A template engine (Thymeleaf or FreeMarker) with templates in the database from the start

## Decision outcome
Chosen option: **1**.
- **Kinds and categories in code:** each `NotificationKind` has a category (`SECURITY` is mandatory on every channel; others follow preferences), an email template and an in-app message key. Producers pass a `Recipient` (user id, email, display name, locale) so `notification` never depends on `identity`.
- **Same transaction:** the in-app item and the mail outbox row are written in the producer's transaction, and delivery is scheduled in it (db-scheduler joins Spring transactions, ADR-0018). A rolled-back change notifies nobody. Option 2 stays possible for producers that prefer events; they call the same API from their listener.
- **In-app items:** `notification_item`, partitioned by month on `created_at`, partitions created ahead and dropped after 13 months by a Flyway `afterMigrate` callback on the migrator connection (the audit pattern, ADR-0019). The app role reads, inserts and marks read; it has no DDL and does not delete. Parameters are display data only, never secrets or links with tokens.
- **Email:** per-tenant relay settings (host, port, `STARTTLS` or `TLS`, optional credentials, from address and name) with the platform relay as fallback. `NONE` only where the installation allows it. The password is encrypted with `DataKeys` bound to the tenant and never returned, logged or audited. Changing settings needs step-up and is audited; a test-send action reports the relay's answer.
- **Delivery log:** the outbox row records relay used, attempts, last SMTP code and error class, sent or failed time. Permanent SMTP failures (5xx) stop at once; transient ones retry with backoff. Parameters are removed once sent or failed.
- **Templates:** subject and body per locale in `notification/messages_<locale>.properties` (English; Arabic for RTL), `{name}` placeholders, one HTML layout with `lang` and `dir`, inline styles. Subjects take no parameters. Tenant-editable templates (1b) bring a sandboxed engine.
- **Preferences:** per user, category and channel (`IN_APP`, `EMAIL`; `PUSH` reserved). Mandatory categories cannot be switched off.
- **Push:** payload design only: `{"badge": n, "title": "You have n new items"}` with no ids or content; sending is Phase 6a.

### Consequences
- Good: one call per notification; atomic with the business change; no new dependency.
- Good: partitions keep the unread queries fast and retention cheap.
- Bad: producers must load the recipient's email and locale; acceptable, they already hold the user.
- Bad: 1a templates change only with a release; 1b makes them tenant data.
- Follow-ups: digests and escalation reminders (Phase 2), real-time delivery (Phase 5), push sending (Phase 6a), inbound bounce processing (later, if a customer needs it).

### Confirmation
- Integration tests: an item and an email per channel and preference; mandatory categories ignore opt-outs; a rolled-back producer leaves neither; cross-tenant isolation; the partition callback creates and drops partitions.
- Delivery tests against a local SMTP server: tenant relay used when set, platform otherwise; permanent failure stops, transient retries; nothing sensitive in subjects (template check).

## Pros and cons of the options
### One API per kind, same transaction
- Good, because it is atomic and simple to test.
- Bad, because producers depend on the notification module's API.

### Event-driven fan-out
- Good, because producers only publish events.
- Bad, because a second asynchronous hop adds a failure mode and the mapping from events to notifications lives far from the producer.

### Template engine with database templates now
- Good, because tenants could edit templates in 1a.
- Bad, because it is scope 1b moved forward, with a sandboxing problem to solve for user-editable templates.

## More information
- https://www.rfc-editor.org/rfc/rfc5321 (accessed 2026-11-05)
- https://www.rfc-editor.org/rfc/rfc3463 (accessed 2026-11-05)
- https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html (accessed 2026-11-05)
