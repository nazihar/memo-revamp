# E05 Inbox, search and dashboards — log

## 2026-11-15 — Plan written
- **Research:** R7 (search) and R10 (inbox, team views, dashboards, watching, bulk actions, charts), by two research agents and checked by the lead (`research.md`). The R7 agent ran spikes on `postgres:18.6` with 200,000 rows.
- **Main finding:** under forced RLS, PostgreSQL does not use a GIN index for `@@`, because the operator is not leakproof (reproduced: 28 ms sequential scan vs 0.3 ms through a SECURITY DEFINER function). Hence ADR-0054: search tables of their own behind one audited definer function that applies the E03 rule, with an equivalence test.
- **ADR-0054** (full-text search) is accepted under the delegated gate. The search screen flows still go to the owner.
- **Design, threat model, tasks T1–T8 and test plan written.** Flows H (search) and I (team view) and six questions are in `ux-review.md` for the owner. T1–T5 (backend) can start; T6 and T7 wait for the review.
- **Versions to watch:**
  - PostgreSQL 18.7 (GIN fixes) is adopted on or after 2026-11-26;
  - PostgreSQL 19 (GA 2026-11-19) needs a search rebuild when adopted;
  - Recharts 3.10.1 is pinned (3.11 canaries skipped).

## 2026-11-15 — Owner's review
- The owner answered "approved as recommended. keep working". Flows H and I and the six recommended answers stand (`ux-review.md`). The web tasks T6 and T7 are unblocked.
