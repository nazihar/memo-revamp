# E04 Org directory and authorisation — log

## 2026-10-29
- E03 merged (PR #20). Plan written: research, ADR-0026 (org model) and ADR-0027 (authorisation model), design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Findings: no new runtime dependency is needed. The units tree uses an adjacency list with a derived ancestor array (GIN), not `ltree` or a closure table, so no PostgreSQL extension is required on-prem. Effective dating covers assignments, reporting lines and unit/position existence; renames are audit-only history, so Phase 2 records must snapshot position and unit names.
- Authorisation is an in-code policy service over a code-defined permission catalogue (jCasbin and Cedar compared; neither translates rules into SQL filters, which list endpoints need). Deny by default is enforced twice: an access annotation on every handler at runtime, and the endpoint-coverage meta-test in the build.
- E03's `tenant_admin` flag becomes a role grant (expand → migrate → contract): the flag is still written for one release and dropped later.
- Screens reuse the E01 templates and tree view approved in the E01 UX review; the owner sees them at the epic demo.
