# E06 — UX review

The per-epic gate is delegated for Phase 2 (overview §10, Q1). The overview asks for a usability check of the builder (§5 "Generic phase-exit items"). The flows below are what the owner walks through at the phase demo. Findings and the manual keyboard and screen-reader pass are recorded here.

## Flows
1. **Open a definition (author or reviewer):** Administration → Workflows → "Credit facility". The version page shows three views of the same body:
   - **Diagram:** read-only for published versions;
   - **Steps:** an ordered list that mirrors the diagram, one row per step showing its type, who it goes to, its deadline and where it leads;
   - **JSON:** the expert view, as today.

   The Steps view is the keyboard and screen-reader path. Everything the diagram shows, it shows too.
2. **Build (author):** New draft → the builder opens on the draft.
   - Add a step from the palette (Review, Approve, Parallel, Delegation of authority, Branch, Notify, Route, Sub-workflow). In the diagram it is added after the selected step; in the Steps view, with "Add step after".
   - Connect it: choose "Leads to" in the panel, or drag from a handle in the diagram. Dragging is never the only way (WCAG 2.5.7).
   - Fill in the panel:
     - name;
     - who it goes to (a person, a position, the supervisor n levels up, a unit head, a role, a queue);
     - the actions offered and which need a remark;
     - forward limits;
     - deadline: due, working time, reminders, escalation;
     - for a Branch: conditions, built as attribute + comparison + value (e.g. "Classification rank ≥ 2"), with raw CEL for experts.
   - Problems appear as you edit: a badge on the step and a list under the diagram, e.g. "This step cannot be reached from the start" or "A reminder before the due time must be shorter than the due time". Choosing a problem selects the step and the field.
   - **Save draft** (If-Match). Leaving with unsaved changes asks first. **Undo** and **Redo** cover every change.
3. **Simulate (author or reviewer):** Simulate → sample values for the item:
   - classification rank and memo type (the attributes the definition declares);
   - the initiator (a person picker);
   - the route, if the definition has a route step.

   Run → the path is highlighted in the diagram and listed in the Steps view. Each step shows who would get it, by name, or why nobody can ("No one holds this position", "The only candidate is the initiator"), its deadline in words ("2 working days; reminder 4 hours before; escalates to the supervisor 1 day late"), and which branch was taken and why. A notify step shows who would be told.
4. **Submit and review (author, then a second admin):** Submit for review → the reviewer opens the version in review. **Changes** (the default view for a reviewer) shows a diff against the published version:
   - steps added (green), removed (red) or changed (amber) on the diagram and in a list;
   - each changed property is shown before → after, e.g. "Director · Deadline: 2 working days → 1 working day".

   The reviewer can simulate too. Then **Approve** (step-up) or **Send back** (with a remark).
5. **Exit journey:** a definition with a branch on classification, a parallel committee, an SLA with escalation and a notify step is built, simulated, approved by a second admin and published. A memo of a type using it routes through it.

## Accessibility notes (to check by hand at the demo)
- **Steps view:**
  - every action reachable by Tab and Enter;
  - steps in an ordered list, with headings per step;
  - problems announced in a polite live region after validation.
- **Diagram:**
  - nodes focusable in reading order;
  - arrow keys move the selection along the connections;
  - Enter opens the panel;
  - Delete asks before removing;
  - focus is never hidden under the panel (WCAG 2.4.11).
- **Arabic:** the diagram stays top-down; panels, lists and labels mirror (RTL). Step names keep their own direction (`dir="auto"`).

## Findings
- (To be filled at the phase demo and the manual keyboard and screen-reader pass.)
