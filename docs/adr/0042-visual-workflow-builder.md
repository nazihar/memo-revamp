---
status: accepted
date: 2026-11-13
deciders: Agent (delegated per-epic gate, Phase 2 overview §10 Q1); owner review at the phase demo
---

# 0042 — Visual workflow builder: React Flow over the same definition body, server-side validation and simulation

## Context and problem statement
Phase 2 E06 must let a tenant admin build, simulate, get approval for and publish a workflow without a developer (the phase exit criterion). E03 already gives the JSON definition (schema 2, ADR-0038), the maker-checker lifecycle and a validation endpoint; E04 gives assignee resolution and a DoA simulator. We must choose:
- how the builder holds and lays out the graph;
- where validation and simulation run;
- how a reviewer sees what changed;
- how a canvas editor meets WCAG 2.2 AA.

Research: `docs/plan/phase-2-workflow-engine/epic-06-visual-builder/research.md`.

## Decision drivers
- One source of truth for what a valid definition is: the server's checks (E03).
- No second copy of the engine in the browser.
- WCAG 2.2 AA, including 2.1.1 Keyboard and 2.5.7 Dragging Movements (overview risk R7).
- Allowed licences, small bundle, maintained libraries (AGENTS §3.1).
- Diffs a reviewer can trust: what is approved is what was read.

## Considered options
1. **React Flow with automatic layout and no stored positions; server validation and simulation; a hand-written structural diff; a Steps view that mirrors the canvas.**
2. React Flow with positions stored in the body (`layout`), dragged by hand.
3. A form-only editor (an ordered list of steps, no canvas).
4. ELK for layout instead of dagre.
5. Client-side validation and simulation, with the JSON Schema and a TypeScript port of the engine.

## Decision outcome
Chosen option: **1**, because it keeps the body as the only source of truth, keeps one validator and one engine, and gives a keyboard path that does not depend on the canvas.

The parts of the decision:
- **Canvas:** `@xyflow/react` 12.12.x (MIT). We keep its attribution badge; nothing we use is behind React Flow Pro.
- **Layout:** `@dagrejs/dagre` 3.1.x (MIT, 15.5 kB, synchronous), top-down, recomputed after every structural change. **Positions are not stored.**
- **Steps view:** a structured list that mirrors the canvas and opens the same panels. It is the complete keyboard and screen-reader path. On the canvas, nodes are focusable, arrow keys follow connections, and connecting works by choosing a target in the panel or with `connectOnClick`; dragging is never required.
- **Validation:** the existing `POST …/validate` endpoint, called after edits (debounced). Problems map by JSON pointer to steps and fields.
- **Simulation:** a new server endpoint runs the pure engine (ADR-0034) in memory. It uses sample attributes and the organisation as it is now, takes each step's positive path, and writes nothing.
- **Diff:** hand-written over two stored bodies, steps keyed by id, connections compared as step properties. It is shown by default to a reviewer of a version in review.
- **Tests:** `fast-check` 4.10.x with `@fast-check/vitest` 0.5.x (MIT, dev-only) for property tests of the model round trip, edits and diff.

### Consequences
- **Good:**
  - The builder writes ordinary schema 2 bodies, so the JSON import and export, migrations and the runtime are untouched.
  - Diffs carry no layout noise.
  - Validation and simulation can never disagree with submit and the runtime.
  - The canvas needs no dragging, which meets WCAG 2.5.7.
- **Bad:**
  - Authors cannot arrange nodes by hand. A large definition's layout may have crossings that nobody can fix; ELK stays the fallback.
  - Validation needs a round trip, so feedback takes about half a second, not a keystroke.
  - Simulation follows only the positive path. Returns and send-backs are not simulated.
- **Follow-ups:**
  - Warnings (such as overlapping conditions) as a second severity level.
  - Saved, rerunnable simulation scenarios.
  - React Flow 13 once stable.
  - ELK, if crossings become a complaint.

### Confirmation
- Property tests: `toBody(toGraph(b)) == b`; any edit followed by its undo gives back the start; `diff(a, a)` is empty.
- `DefinitionSimulationIT`: the demo definitions take the expected paths, and nothing is written.
- axe and a keyboard-only E2E on the Steps view; the manual screen-reader pass in `ux-review.md`.
- The builder exit journey (Playwright) on every PR.
- Dependency rows in `docs/dependencies.md`, and the licence scan in CI.

## Pros and cons of the options
### 1. Automatic layout, server validation and simulation, hand-written diff, Steps view
- Good, because the body stays the single source, and one validator and one engine serve everything.
- Good, because the keyboard and screen-reader path is a real editor, not a read-only outline (the Jira Text/Diagram model, research §Accessibility).
- Bad, because authors cannot place nodes, and validation needs the network.

### 2. Stored positions, dragged by hand
- Good, because authors control the picture.
- Bad, because the body gains presentation data, every move is a change to review, diffs need to ignore it, and dragging needs a non-drag alternative anyway.

### 3. Form-only editor
- Good, because it is simplest and accessible by construction.
- Bad, because it misses the overview's scope (a visual editor), and branches and parallel steps are harder to see in a list alone.

### 4. ELK
- Good, because it has edge routing and more control.
- Bad, because it is about 28 times larger (437 kB against 15.5 kB), asynchronous, and harder to support. React Flow recommends dagre for tree-shaped flows.

### 5. Client-side validation and simulation
- Good, because feedback is instant and works offline.
- Bad, because it means two validators and two engines that can drift. Assignee resolution needs the organisation and the DoA anyway, which are server-side, tenant-scoped data.

## More information
- Research R1 and R9, accessed 2026-11-13: React Flow accessibility guide (https://reactflow.dev/learn/advanced-use/accessibility), React Flow layouting guide (https://reactflow.dev/learn/layouting/layouting), WCAG 2.2 Understanding pages for 2.1.1, 2.5.7, 2.4.11, 2.5.8, 1.4.11, 4.1.2 and 4.1.3.
- Related: ADR-0034 (own, pure engine), ADR-0038 (definitions), ADR-0039 (CEL), ADR-0040 (assignees and DoA), ADR-0041 (SLAs).
