# Test plan — E06 Notifications

## Scope and risks
New: in-app items (partitioned), preferences, tenant relays, localised templates, delivery log, producers, APIs and screens. Risks: users seeing others' notifications, mail through the wrong relay, secrets at rest or in subjects, security notices suppressed, unbounded table growth. See [design.md](design.md), [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Integration | `NotificationsIT` (channels by preference, mandatory security, rollback leaves nothing, item params), `NotificationPartitionIT` (create ahead, drop old, RLS on new partitions via the meta-test) | PR |
| T2 | Unit | `MailTemplatesTest` (every template in every bundle, no placeholder in subjects, HTML escaped and `dir="rtl"` for Arabic) | PR |
| T3 | Integration | `MailRelayIT` against Mailpit (as E03), using its chaos triggers for scripted SMTP error codes: tenant vs platform relay, 5xx permanent, 4xx retry, log columns, params cleared; `MailSettingsIT` (password write-only, encrypted, `NONE` refused, audit without password, test-send result and rate limit) | PR |
| T4 | Integration | `SecurityNotificationsIT` (sign-in alert, password change, MFA reset produce items and mails), `RoleGrantedNotificationIT` | PR |
| T5 | API / authorisation | `NotificationsApiIT` (own items only, keyset paging, unread count, read, read all, cross-tenant), `PreferencesIT` (409 `MANDATORY`), matrix `e06-notifications.yaml`, coverage meta-test, OpenAPI drift | PR |
| T6 | Component | bell (count, "9+", accessible name, polling paused when hidden), notifications list (unread state, activation marks read and navigates, mark all), preferences (security locked), email settings form (password placeholder, validation, step-up), mail log table | PR |
| T7 | E2E / a11y / performance | `notifications.spec.ts` journey; axe on every new screen (light/dark, LTR/RTL); `NotificationPerformanceIT` (design §7 budgets, 2x headroom) | PR (smoke), nightly (cross-browser) |

## Exit checklist
- [ ] All listed tests green in their stage
- [ ] ASVS rows (V8, V12, V14, V16) updated
- [ ] Demo of the journey to the owner at the epic review
