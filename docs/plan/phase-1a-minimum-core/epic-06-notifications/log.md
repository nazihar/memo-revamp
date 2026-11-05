# E06 Notifications — log

## 2026-11-05
- E05 merged (PR #27). Plan written: research, ADR-0030, design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Findings: no new dependency. Localised mail text uses Spring `MessageSource` bundles; ICU4J is out (Unicode-3.0 licence, not on the allow-list). In-app items are monthly partitions created and dropped by a migrator callback, the audit pattern, so the app role never needs DDL. Producers pass the recipient, so `notification` does not depend on `identity`.
- UX: the bell is a link to a notifications page rather than a popover (large target, nothing hidden; E01's slot). Screens reuse the approved E01 templates.
- T1–T6 built (one commit): items on monthly partitions with the callback, preferences, the Notifications API, MessageSource bundles (en, ar), tenant relays with a write-only encrypted password, delivery log columns, producers (sign-in alert, password changed, MFA reset, role granted), APIs, matrix (492 cells), web (bell, notifications page, settings, admin email page).
- Findings:
  - `notification` cannot depend on `identity` (identity sends mail): step-up for the relay settings goes through a new `common.authz.StepUpCheck` that `identity.StepUp` implements, and the actor's email through `RecipientLookup`, also implemented by `identity`.
  - Spring's `MailSendException` keeps per-message failures in `failedMessages`, and Angus nests the coded `SMTPAddressFailedException` in `nextException`: the SMTP code is found by a bounded search over all three, otherwise a 550 looked transient.
  - A test send holds no transaction while talking to the relay; with no relay at all it reports `NoRelayConfigured` instead of failing on an empty sender address.
  - The E05 and E06 response types did not mark non-null fields required (the E04 convention `@field:Schema(requiredMode = REQUIRED)`), so the web client saw every field as optional; fixed for both.
  - Master CI after the E05 merge failed once on `DownloadIT`: the test S3 client sent the SDK's default checksum next to Content-MD5, which SeaweedFS sometimes rejects. The test client now uses checksums only when required, as production does; the rerun was green and E05 deployed.
- T7: E2E smoke covers the screens (bell, notifications page, settings round trip with security locked, admin email page); the grant-to-notification flow is a backend test, because a grant in E2E would race the access spec on the same demo user.
