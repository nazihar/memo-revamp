# E06 Notifications — threat model (STRIDE)

## Data flows
1. Producer modules → `Notifications` (in-process, same transaction).
2. Browser → backend: notifications, preferences, admin mail settings and log.
3. Backend → SMTP relay (tenant's or platform's).
4. Backend ↔ PostgreSQL: items (partitioned), preferences, relay settings, outbox.

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | A user reads or marks another user's notifications | 2, 4 | Every query filters on the actor's user id; other users' ids are 404; tenant RLS below | V8.2.x | `NotificationsApiIT` (other user, other tenant) |
| S2 | A tenant admin points mail at a relay that impersonates the platform, or a tenant's mail is sent through another tenant's relay | 3 | Relay chosen by the outbox row's tenant inside its tenant context; settings RLS-protected; from address is the tenant's own | V8.4.1 | `MailRelayIT` (two tenants, two relays) |
| T1 | Mail parameters (one-time links) or relay passwords read from the database | 4 | Parameters and password encrypted with `DataKeys` bound to tenant (and message); parameters cleared after delivery | V11.x, V14.x | `MailOutboxIT`, `MailSettingsIT` (row does not contain the password) |
| T2 | Downgrade: relay credentials sent in clear | 3 | `STARTTLS` required or implicit `TLS`; `NONE` only where the install allows it; server identity checked | V12.x | `MailSettingsIT` (NONE refused), sender configuration test |
| R1 | Denial of a relay change that diverted mail | 2 | Step-up, audit with before/after (no password) | V16.3.x | `MailSettingsIT` audit |
| I1 | Sensitive content in subjects, push payloads or logs | 3 | Subjects fixed per template without parameters (template test); push design without content; logs carry ids and error classes only, never SMTP free text | V14.x, V16.x | `MailTemplatesTest` |
| I2 | The delivery log leaks other tenants' recipients | 2, 4 | RLS; admin permission | V8.x | matrix, `MailLogIT` |
| I3 | Notification parameters carry secrets into the in-app list | 1 | API contract: display data only; reset and invitation links stay mail-only | V14.x | review; `NotificationsIT` (security kinds have no link params) |
| D1 | Test-send abused to spam or probe hosts | 2, 3 | Step-up, 5 per hour per tenant, sends only to the actor's own address, 10 s timeout | V2.4.x | `MailSettingsIT` rate limit |
| D2 | Item table growth slows the badge query | 4 | Monthly partitions, partial unread index, partitions dropped after 13 months | — | `NotificationPartitionIT`, performance test |
| E1 | A non-admin changes the relay | 2 | `notification.settings.manage` (privileged) + step-up | V8.x | matrix |
| E2 | A user disables security notices to hide an attack | 2 | `SECURITY` mandatory on every channel; 409 `MANDATORY` | V6.x | `PreferencesIT` |

## Residual risks
- No inbound bounce processing: a mailbox that silently drops mail is not detected beyond SMTP replies.
- Tenant relay passwords are protected by the install's data key (KMS/HSM in 1b, ADR-0028).
