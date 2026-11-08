# E06 Notifications — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Items and partitions:** `notification_item` (monthly partitions, RLS via partition function), `notification_preference`; partition callback (create ahead, drop after retention); `Notifications.notify` with kinds, categories, preferences, `Recipient` | M | — |
| T2 | **Templates and locales:** `MessageSource` bundles (en, ar), HTML layout with `lang`/`dir`, outbox `locale`; E03 templates move into the bundles; template completeness test | S | — |
| T3 | **Tenant relays and delivery log:** `notification_smtp` (password via `DataKeys`), per-tenant sender cache, relay choice in `MailDelivery`, permanent vs transient failures, outbox log columns; `notification.settings.manage` permission | M | T2 |
| T4 | **Producers:** sign-in alert, password changed, MFA reset through `Notifications` (security, mandatory); role granted (administration) from `GrantsChanged` | S | T1, T2 |
| T5 | **APIs:** my notifications (list, unread count, read, read all), preferences, admin mail settings (get, put, delete, test) and mail log; matrix file `e06-notifications.yaml`; OpenAPI and client | M | T1–T4 |
| T6 | **Web:** bell with badge in the top bar, notifications page, preferences on My account, admin email page (settings, test, log); messages en + ar; component tests | L | T5 |
| T7 | **E2E, performance, docs:** Playwright journey (an admin grants a role → the user sees the badge and the item, marks it read; security notice cannot be turned off), axe; notification performance at 1,000 items per user; ASVS rows; STATUS | M | T6 |

T1 and T2 in parallel; T3 after T2; T4 after T1 and T2.

## Acceptance criteria (selection)
- **T1:** Given a user who turned off email for administration, when a role is granted, then an in-app item exists and no mail is queued; given the same user, a sign-in alert still mails. Given a producer transaction that rolls back, then neither an item nor a mail exists. Given the callback runs, then partitions exist three months ahead and partitions older than the retention are dropped.
- **T3:** Given a tenant relay, its mails go through it and the log says `TENANT`; without one, `PLATFORM`. Given the relay answers 550, then the mail is `FAILED` after one attempt with code 550 and its parameters are cleared; given 451, it is retried. Given `NONE` and an install that does not allow it, then 400.
- **T5:** Given user A's item id, when user B marks it read, then 404. Given a stale step-up, changing the relay is 401 `step-up-required`. The password never appears in any response.
- **T6/T7:** The bell's accessible name includes the unread count; the security row's toggles are disabled; axe finds nothing on the new screens in light/dark and LTR/RTL.
