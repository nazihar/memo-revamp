# The workflow builder

**What it is:** the editor for workflow definitions in Administration → Workflows. It shows each version three ways: a **Diagram**, a **Steps** view (the same content in words, and the keyboard and screen-reader path) and the **JSON**. For a version in review it adds a fourth, **Changes**. It writes ordinary definition versions (ADR-0038, ADR-0042), so everything in `workflow-definitions.md` still applies: drafts, review by someone else, publishing, pinning and migration.

**Who:**
- Holders of `workflow.definitions.manage` build drafts and submit them.
- Holders of `.approve` review, approve (with a step-up) and send back.
- Both can simulate.
- Tenant administrators hold both, but never approve their own submission.

## Building
1. **New workflow:** a key (which never changes) and a name. The draft starts with one route step.
2. **Change steps:**
   - Use **Change** in the Steps view, or select a step in the diagram; its panel opens beside the diagram.
   - **Add a step at the start** or **Add a step after this one** (the palette has every step type except signature).
   - **Remove this step** asks first. What led to the step then leads where it led, when that is one place.
3. **Panels:**
   - who the step goes to (a person, a position, the supervisor, a unit head, a role, a queue);
   - what the holder can do, and which actions need a remark;
   - forwarding limits;
   - a parallel step's people and mode;
   - a branch's conditions (a simple form, or CEL; a condition the form cannot show stays as written);
   - the deadline: due after, working time, reminders, and what happens when late;
   - a notice's recipients and message key.
4. **Problems** show half a second after each change, on the step and in the list under the views. **Undo** and **Redo** cover every change. **Save draft** stores it.
5. **Simulate:**
   - Choose who starts it and sample values (for example the classification rank).
   - It shows each step that would be reached, who would get it, and why it would stop: not cleared, inactive, nobody found.
   - Nothing is saved or sent.
6. **Submit for review.** The reviewer opens on **Changes** against the published version, or picks any other version, then approves or sends back.

## When something goes wrong
| What the builder says | Why | What to do |
|---|---|---|
| "Someone else saved this draft while you were changing it" | Another author saved first (412) | **Copy my changes as JSON**, then **Reload the saved version** and reapply, or agree who continues |
| "This body cannot be drawn" | The JSON has no `start` or a step lacks an id or type | Fix it in the JSON view; the other views come back once it reads |
| "Too many simulations. Try again in a minute." | 30 simulations a minute per person | Wait a minute |
| "The workflow has problems. Fix them, then simulate again." | Simulation runs only on a valid body | Fix the problems listed under the views |
| A step names "Unknown (…)" | A person, position, unit or role it names was removed | Choose another one. Submitting refuses positions, units, roles and queues that no longer exist; an inactive person blocks the task when it is reached |
| The simulation stops: "Not cleared for this classification" | That person's clearance is below the sample's rank | Raise their clearance, or route higher ranks elsewhere |
| A role or queue is missing from the list | It does not exist in this organisation | Create it (Access → Roles, Administration → Queues), then reopen the panel |

## Operations
- **Server calls:**
  - `GET /api/v1/admin/workflow-builder/catalogue`;
  - `POST …/workflow-definitions/{key}/versions/{n}/simulate`, rate limit `WORKFLOW_SIMULATION_PER_USER`, 429 with `Retry-After`;
  - the existing `validate`, which the builder calls as you type.

  Simulation and the catalogue are reads: nothing is audited or written. Saves, submits and approvals are audited as `workflow.definition.*`.
- **Web:** React Flow and dagre load only with the definition pages. The builder's texts are their own catalogue part, `builder`.
- **Accessibility:** the manual keyboard and screen-reader checklist is in `phase-2-workflow-engine/epic-06-visual-builder/ux-review.md`.
