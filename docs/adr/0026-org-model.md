---
status: accepted
date: 2026-10-29
deciders: product owner (delegated), agent
---

# 0026 — Org model: units tree, positions, effective-dated assignments

## Context and problem statement
Phase 1a needs an org directory that Phase 2 routes memos through: legal entities, a tree of units, positions in units, people assigned to positions, and reporting lines. The directory must answer "who held this position on date D" and support permission checks scoped to a unit subtree, filtered in SQL. Research: `epic-04-org-authz/research.md §R12, §R14`.

## Decision drivers
- Subtree checks are on the hot path of every scoped permission check and list filter.
- No PostgreSQL extensions beyond the core (on-prem DBAs may restrict `CREATE EXTENSION`).
- "As of D" questions on assignments and reporting lines must be single indexed queries.
- Records (note sheets, approvals) must not depend on how the directory looks later.

## Considered options
1. Adjacency list + `WITH RECURSIVE` per query
2. `ltree` materialised paths
3. Closure table
4. **Adjacency list + derived `uuid[]` ancestor array with a GIN index**

## Decision outcome
Chosen option: **4**, with valid-time ranges where the business asks "as of D".
- `org_unit.parent_id` is the source of truth; `ancestors uuid[]` (root first, excluding self) is rebuilt by the service in the same transaction when a unit is created or moved (one `UPDATE` over the moved subtree). A move into its own subtree is rejected. A consistency check (test + verification query) compares the array with `parent_id`.
- Units and positions are never deleted: they are **closed** (`valid_to`), and a closed unit cannot have open positions or children.
- Assignments (`org_assignment`, type `PRIMARY` in 1a; `ADDITIONAL` and `ACTING` arrive in 1b) and reporting lines carry half-open date ranges `[valid_from, valid_to)` in the tenant's time zone. At any date a position has at most one primary holder and a user at most one primary position; the service enforces this under `FOR UPDATE` locks on the position and the user (no `btree_gist` exclusion constraint).
- Reporting lines are position → position, one solid line per position at a time (a `kind` column is ready for dotted lines); cycles are rejected.
- Attribute changes (rename, code, legal entity details) are not effective-dated: they are audited with before/after, and the history view reads the audit trail.
- Later phases snapshot position title and unit name onto the record at action time.
- The org tree is cached per tenant (ADR-0020) and evicted on every org change.

### Consequences
- Good: no extension; subtree filters are indexed (`ancestors @> array[:unit]`) or in-memory; "as of D" is one range predicate.
- Good: the seat model matches how e-office systems route work (to positions).
- Bad: the ancestor array is derived data that must stay consistent; mitigated by doing every tree change through one service and testing it.
- Bad: renames are not queryable "as of D" (by design; the record carries its own snapshot).

### Confirmation
- `OrgTreeIT` (create, move, cycle rejected, ancestor consistency), `AssignmentIT` (overlaps rejected, "as of D"), `OrgPerformanceIT` at 1,500 units and 5,000 users.
