# E06 Notifications — log

## 2026-11-05
- E05 merged (PR #27). Plan written: research, ADR-0030, design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Findings: no new dependency. Localised mail text uses Spring `MessageSource` bundles; ICU4J is out (Unicode-3.0 licence, not on the allow-list). In-app items are monthly partitions created and dropped by a migrator callback, the audit pattern, so the app role never needs DDL. Producers pass the recipient, so `notification` does not depend on `identity`.
- UX: the bell is a link to a notifications page rather than a popover (large target, nothing hidden; E01's slot). Screens reuse the approved E01 templates.
