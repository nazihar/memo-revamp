# E06 Visual workflow builder — test plan

| Task | Layer | Tests |
|---|---|---|
| T1 | Unit + property (web) | `model.test.ts`: the demo definitions and one body per step type become the expected nodes and edges. Property (fast-check, generated valid bodies): `toBody(toGraph(b))` equals `b`, steps in order, unknown fields kept. `layout.test.ts`: top-down ranks; 100 steps laid out in under 200 ms |
| T1 | Component (web) | Version page: Diagram, Steps and JSON show the same steps; Steps view as an ordered list with headings; axe on each view; `labels` used for names, "Unknown (id …)" when missing |
| T1 | Integration | `DefinitionsAdminIT`: `labels` names people, positions, units and roles in the body, and nothing outside the tenant (RLS) |
| T2 | Unit + property (web) | `edit.test.ts`: every command and its undo restore the exact previous body; removal re-points single successors. Property: a random sequence of commands, then undoing all of them, gives back the start |
| T2 | Component (web) | debounced validation (one request in flight, stale answers dropped, MSW); a problem's pointer lands on its node, row and field; Save with If-Match; 412 keeps the graph and offers reload or copy; the unsaved-changes blocker; JSON parse errors keep the JSON view |
| T3 | Integration | `BuilderCatalogueIT`: attributes per subject type, roles, queues, published fragments only, step types without `sign`; tenant isolation. Matrix: `.manage` and `.approve` allowed, everyone else 403 |
| T3 | Component (web) | assignee panel for each kind writes the right JSON (person, position, supervisor levels, unit head, role in unit, queue); parallel n bounded by assignees; an author with `.manage` only can pick roles and queues |
| T4 | Unit (web) | `conditions.test.ts`: simple rows ↔ CEL for each type and comparison; an unreadable condition is left byte for byte; a property: rows → CEL → rows is the identity |
| T4 | Component (web) | deadline panel: due, working time, reminders (at most 3 after), escalation target only for reassign; the server's `SLA_REMIND_BEFORE` shows on the field |
| T5 | Unit | `DefinitionSimulatorTest` over the pure engine: positive path, branches by attributes, parallel all/any/n, DoA groups, blocked at a step nobody can hold, the 500-command limit |
| T5 | Integration | `DefinitionSimulationIT`:<br>• "Credit facility" with rank 2 goes through the committee, with rank 0 through the director;<br>• substitutes show as acting for;<br>• the initiator is never a candidate;<br>• an invalid body returns 422 with problems;<br>• nothing is written (row counts of `workflow_*`, `audit_event` and the event registry unchanged);<br>• the 429 rate limit;<br>• RLS. |
| T5 | Matrix + contract | `p2-e06-builder.yaml`: `catalogue`, `simulate`. OpenAPI drift; the generated client |
| T5 | Performance | the simulation of a 30-step definition over a 500-person organisation is under 300 ms p95 (`DefinitionSimulationPerformanceIT`, nightly) |
| T6 | Component (web) | sample form follows the declared attributes; route entry only with a route step; the path is highlighted on the diagram and the Steps view; blocked and branch reasons in words; axe |
| T7 | Unit + property (web) | `diff.test.ts`: added, removed and changed steps; property paths for assignee, SLA, branches, next; labels used. Property: `diff(a, a)` is empty; applying the reported changes to `a` gives `b` for the paths it reports |
| T7 | Component (web) | the Changes view is the default for a reviewer of a version in review; colours always come with an icon and a word; any two versions can be compared |
| T8 | Accessibility | axe on every builder view in English and Arabic; keyboard-only E2E (Steps view: add, connect, set assignee and SLA, save, without the mouse); the manual screen-reader pass (NVDA + Firefox, VoiceOver + Safari) recorded in `ux-review.md` |
| T8 | i18n | `i18n:verify`; pseudo-locale screenshots of the builder; RTL layout of panels and lists |
| T9 | E2E | builder exit journey, the critical-journey list:<br>1. tenant admin creates a definition;<br>2. builds review → branch (rank ≥ 2) → parallel committee \| director, adding an SLA with escalation and a notify step;<br>3. simulates both branches;<br>4. submits;<br>5. a second admin (org admin) reviews the diff and approves with a step-up;<br>6. a memo type uses it;<br>7. a memo routes through the committee branch to Approved. |
| T9 | Security | threat-model rows checked: maker-checker refusal, If-Match 412, no HTML in names (an XSS payload in a step name renders as text in the diagram, the Steps view and the diff) |

**Seed data:** the demo tenant's definitions (E03, E04, E05) and its organisation; for the exit journey, the existing demo admin and org admin as the two admins. No new seeders.

**CI stages:**
- **Every PR:** unit, property, component, integration, matrix, contract, E2E.
- **Nightly:** performance and PIT mutation testing on `workflow` (the simulator is included).
