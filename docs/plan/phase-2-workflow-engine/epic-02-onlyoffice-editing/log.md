# E02 In-browser editing — log

## 2026-11-11
- Plan written under the delegated gate (Phase 2 Q1): research (R1, R8), ADR-0037, design, threat model, tasks, test plan.
- Findings while planning: ONLYOFFICE 9.4.0.1 is already pinned in Compose with JWT on and an internal network (Phase 0), and the backend has a health probe for it, but nothing uses it yet. Sub-path hosting (`/office/`) is not officially documented; T1 verifies it before anything depends on it, with a dedicated host as the fallback. 10.0 is out (2026-10-10); E02 stays on 9.4 until the bake period ends, as the dependency register says.
- Dependency on the owner: the dev VM upgrade to 16 GB (Phase 2 Q2) before ONLYOFFICE runs on dev. Everything else runs on the local full stack; the D1 demo does not need E02.
