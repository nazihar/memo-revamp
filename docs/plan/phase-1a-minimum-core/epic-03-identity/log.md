# E03 Identity, sessions and step-up — log

## 2026-10-18
- Plan written (research, ADR-0022 to ADR-0025, design, threat model, tasks, test plan). Gate delegated (overview Q1).
- Findings: Spring Security 7.1 has a factor model (`FactorGrantedAuthority` with `issuedAt`, `validDuration`) that fits MFA and step-up, but its flows are redirect-based, so the SPA gets JSON endpoints; Spring Session's cleanup is a `@Scheduled` cron, replaced by a db-scheduler task; the session stores a small JSON principal of ours instead of a serialised `SecurityContext`.
- Open item for the owner (later in E03): the dev environment has no SMTP relay, so invitation and reset emails cannot be delivered there yet. Options: a mail catcher on the VM reachable only through an SSH tunnel, or a real relay.
