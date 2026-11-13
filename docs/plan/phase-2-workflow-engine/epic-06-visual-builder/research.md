# E06 Visual workflow builder — research

All sources were accessed on **2026-11-13**. Research items come from the phase overview §6: R1 (libraries this epic adds) and R9 (visual builders and keyboard accessibility of node editors). The accessibility section feeds risk R7 in the overview. "Not found" means a search was made and no primary source turned up; nothing below is filled in from memory.

## R1 — Libraries this epic adds

Our allowed licences: Apache-2.0, MIT, BSD, ISC, EPL-2.0, MPL-2.0, LGPL (unmodified). The web app runs React 19.3.0 and Vitest 5.0.4 (`web/package.json`).

### `@xyflow/react` (React Flow 12)
- **Version:** 12.12.1, published 2026-11-02; 12.12.0 on 2026-09-24 (npm registry, https://registry.npmjs.org/@xyflow/react). Prereleases 13.0.0-next.0 to next.3 appeared from 2026-10-11 to 2026-11-08; no 13.0.0 stable yet.
- **Licence:** MIT (https://registry.npmjs.org/@xyflow/react/latest). "React Flow is open-source MIT-licensed software, and it will be forever." (https://reactflow.dev/pro)
- **Maintenance:** 11 stable releases from 2026-02-19 to 2026-11-02 (registry `time`). npm maintainers `peterkogo` and `webk1d` (webkid). Release notes: https://github.com/xyflow/xyflow/releases.
- **React 19:** peer dependency `react >=17`, `react-dom >=17` (registry `latest`). No core-package statement on React 19 was found. The React Flow UI components (shadcn-based, a separate collection) "have been updated to support … React 19 and Tailwind 4" (https://reactflow.dev/whats-new/2025-10-28).
- **Size:** 58.6 kB min+gzip for 12.12.1 (https://bundlephobia.com/package/@xyflow/react). Dependencies: `zustand`, `classcat`, `@xyflow/system`.
- **Accessibility** (https://reactflow.dev/learn/advanced-use/accessibility, https://reactflow.dev/api-reference/react-flow):
  - "By default, all nodes and edges are keyboard-focusable and operable." Focusable elements get `tabIndex={0}` and `role="group"` by default.
  - `nodesFocusable` / `edgesFocusable`: focus "can be cycled with the `Tab` key and selected with the `Enter` key". Enter or Space selects; Escape clears.
  - Arrow keys move selected nodes when `nodesDraggable` and `nodesFocusable` are true; Shift moves faster. `disableKeyboardA11y` turns this off but keeps focus and selection.
  - `autoPanOnNodeFocus` (default `true`) pans the canvas to a focused node.
  - `ariaRole` overrides the node wrapper's role; `domAttributes` passes `aria-roledescription` and other attributes; edges take an `aria-label`.
  - `ariaLabelConfig` overrides or localises built-in strings (node and edge descriptions, controls, minimap, handles). This is where react-intl messages plug in.
  - An `aria-live="assertive"` region announces node moves: "Moved selected node {direction}. New position, x: {x}, y: {y}".
  - `connectOnClick` lets a user "click or tap on a source handle to start a connection", a non-drag way to connect (WCAG 2.5.7).
  - The guide says the features help to meet WCAG **2.1** AA "when implemented correctly". It claims no conformance by itself, and says nothing about 2.2.
- **Pro (paid):** no component or API is paid. The subscription covers Pro examples, prioritised issues and support (https://reactflow.dev/pro). Pro-only **examples** include Auto Layout, Undo and Redo, Copy and Paste, Helper Lines and Expand and Collapse (https://reactflow.dev/examples). The attribution badge may only be hidden with a subscription: "Only remove this attribution, if you are subscribed to React Flow Pro." (https://reactflow.dev/learn/troubleshooting/remove-attribution). We keep the badge; auto layout and undo are ours to write.

### Auto-layout: `@dagrejs/dagre` vs `elkjs`
React Flow's layout guide compares both (https://reactflow.dev/learn/layouting/layouting): "If you need to organize your flows into a tree, we highly recommend dagre." On ELK: "the most configurable option available, but it's also the most complicated", and "We don't often recommend elkjs because it's complexity makes it difficult for us to support folks". The guide notes an open dagre issue with sub-flow nodes connected to nodes outside their sub-flow.

| | `@dagrejs/dagre` | `elkjs` |
|---|---|---|
| Version and date | 3.1.3, 2026-10-24 (https://registry.npmjs.org/@dagrejs/dagre) | 0.12.1, 2026-10-28; 0.13.0 was published 10 minutes earlier and is deprecated (https://registry.npmjs.org/elkjs) |
| Licence | MIT | "EPL-2.0 OR GPL-3.0-or-later"; we take EPL-2.0, which is allowed |
| Size | 15.5 kB min+gzip (https://bundlephobia.com/package/@dagrejs/dagre) | 436.8 kB min+gzip, measured for 0.13.0 (https://bundlephobia.com/package/elkjs) |
| Maintenance | 2.0.0 (2025-11-20) to 3.1.3 in 11 months; maintainers cpettitt, lutzroeder, davidnewell; ESM build since 2.0.0 (https://github.com/dagrejs/dagre/releases) | 0.11.0 (2025-09-15), 0.11.1, 0.12.0, 0.12.1; maintainers from the Kiel `kieler` group (https://github.com/kieler/elkjs/releases) |
| Algorithm | Layered layout of directed graphs; `rankdir` default `TB`; `ranker` network-simplex, tight-tree or longest-path (https://github.com/dagrejs/dagre/wiki) | "ELK's flagship is a layer-based layout algorithm", "particularly suited for node-link diagrams with an inherent direction and ports" (https://github.com/kieler/elkjs) |
| Sync or async | No async API documented; the wiki reads coordinates right after `dagre.layout(g)` | `layout()` "returns a `Promise`"; "Web Workers are supported out of the box" |
| Edge routing | No | Yes (React Flow guide) |

**Fit:** approval flows are top-down, with condition branches and parallel splits that join. Both do layered top-down layout. A definition has tens of steps, not thousands, so a synchronous layout is fine and a worker brings nothing.

**Recommendation:** `@dagrejs/dagre` 3.1.3. It is 28 times smaller, MIT, recommended by React Flow for this shape, and synchronous. Do not use React Flow sub-flows (parent nodes), because of the open dagre issue. Our definitions do not need them: a parallel step is one step with several assignees (E03), drawn as one node. Revisit ELK only if edge crossings in real tenant flows become a complaint.

### Version diff: library vs hand-written
- **`jsondiffpatch` 0.7.7**, 2026-10-29, MIT, one maintainer (https://registry.npmjs.org/jsondiffpatch). "min+gzipped ~ 16KB". Arrays match by position unless an `objectHash` function is given. Move detection is on by default. Formatters for HTML, annotated JSON and JSON Patch (RFC 6902). Depends on `@dmsnell/diff-match-patch` (https://github.com/benjamine/jsondiffpatch).
- **`microdiff` 1.6.0**, 2026-08-02, MIT, one maintainer, zero dependencies, "<1kb minified" (https://registry.npmjs.org/microdiff). Output is `CREATE`, `REMOVE` and `CHANGE` with a `path`. The README does not say how arrays are matched; paths use array indexes.
- **What the review needs:** "step X added", "step Y removed", "assignee of step Z changed from A to B", and connections added or removed. Steps carry stable ids (E03's definition model). Connections have no ids of their own: they are a step's `next`, `branches[i].next` and `otherwise`, so they compare as properties of their step.
- **Recommendation:** hand-write the diff. Index steps by id, compare old and new, and for a changed step compare its known fields (name, type, assignee rule, actions, SLA, condition). It is about a hundred lines, has no dependency, and yields labels in our own vocabulary through react-intl. `microdiff` adds little beyond the field compare; `jsondiffpatch` adds 16 kB and a delta format we would translate anyway. Camunda ignores layout-only changes in its diff (below). We store no positions at all (design §2), so there are none to ignore.

### `fast-check` (dev dependency, with Vitest)
- **Version:** 4.10.2, published 2026-09-19 (https://registry.npmjs.org/fast-check). Licence MIT. One dependency, `pure-rand`.
- **Maintenance:** 18 releases in the 4.x line from 4.0.0 (2025-03-10) to 4.10.2. Maintainer `ndubien`. 4.10.0 is titled "New plugin API and deprecations ahead of v5" (https://github.com/dubzzz/fast-check/releases).
- **`@fast-check/vitest` 0.5.0**, 2026-09-11, MIT, same maintainer. Peer `vitest ^4.1.0 || ^5.0.0`, which covers our Vitest 5.0.4 (https://registry.npmjs.org/@fast-check/vitest/latest). It adds `test.prop([...])` / `it.prop({...})` and per-test seeds, for example `{ seed: 4242 }`, to replay a failure.
- **Worth it:** yes, as a thin wrapper. Properties fit the builder well: layout never overlaps nodes, the diff of a definition with itself is empty, applying the list-view edits and the canvas edits gives the same definition, and validation never throws.

## R9 — How visual builders handle validation, simulation, diff and review

### ServiceNow Workflow Studio / Flow Designer
- **Testing:** Test runs a flow with supplied test data and skips its trigger conditions; for a record trigger, you pick or create a record. "Testing a flow bypasses the flow trigger conditions to run it with the test data you provide." Results show in the flow execution details. Because testing "creates or changes records on the instance", a non-production instance is advised (https://www.servicenow.com/docs/r/HGWDpzjgkfvaJWTdMzNy2w/16GAKmUMqo2akmwgAuG1TA, release "Australia").
- **Error checking:** a Workflow Studio validation page was not found. The legacy Workflow Editor has three levels: INFO, WARN, CRITICAL. "Workflows at this validation level cannot be published or run in production." Validation runs on publish and on demand; examples are disconnected transitions, multiple End activities and missing subflows (https://www.servicenow.com/docs/r/zurich/build-workflows/legacy-workflow/c_WorkflowValidation.html).
- **Compare versions:** the flow history compare view shows two entries side by side. Added items are green, removed red, changed blue, each with an icon; expanding a changed item lists its changed inputs. Any entry can be restored or saved as a copy (https://www.servicenow.com/docs/r/build-workflows/workflow-studio/flow-history-compare-view.html, release "Brazil").
- **Adopt:** severity levels where only errors block; colour plus icon for diff status; expand a changed step to its changed fields.
- **Do better:** a simulation that writes nothing. Their test touches real records.

### Microsoft Power Automate
- **Flow checker:** "always active, appearing in the command bar". It opens on save if there are errors or warnings, lists them by action, and marks the action card in red; selecting an issue opens the action (https://learn.microsoft.com/en-us/power-automate/error-checker).
- **Test flow:** manual (perform the trigger) or automatic (reuse a recent trigger or repeat a run). Static results mock an action's output; a beaker icon marks a mocked action (https://learn.microsoft.com/en-us/power-automate/guidance/coding-guidelines/test-cloud-flows).
- **Drafts and versions:** save a draft "even with errors"; Publish puts it live. Version history lists versions by day with *Published* and *Past published* markers; restore creates a new draft. "A side-by-side comparison of versions isn't available at this time." Drafts cannot be tested: "flow changes need to be published and runnable to be tested." (https://learn.microsoft.com/en-us/power-automate/drafts-versioning)
- **Adopt:** an always-visible issue count; issues link to the step; drafts may be invalid but publish may not; restore makes a new draft rather than overwriting.
- **Do better:** simulate a draft without publishing, and show a real diff. Power Automate has neither.

### SAP Build Process Automation
- **Validation:** an official help.sap.com page on process validation was not found (help.sap.com pages render empty to the fetcher). A tutorial says: "Before you run the process, ensure that the process is saved and that there are no errors showing in the Design Console." (https://developers.sap.com/tutorials/spa-dox-run-process.html). "Any errors during the deployment will be shown in the Design Console." (https://developers.sap.com/tutorials/spa-run-process.html). Whether errors block release was not found.
- **Release and deploy:** "Releasing a project creates a version or snapshot of the changes." Versions are x.y.z; the first is 1.0.0. Only a released version can be deployed, to a chosen environment, and "at a given time there can be multiple deployed versions of the same project." Released projects are read-only until made editable (https://developers.sap.com/tutorials/spa-run-process.html).
- **Version diff:** not found.
- **Adopt:** an immutable released snapshot, separate from the editable draft, with a release note.
- **Do better:** show what changed between snapshots.

### Camunda Modeler
- **Linting:** both modelers use `@camunda/linting` (bpmnlint plus `bpmnlint-plugin-camunda-compat`). It "creates error overlays to be shown on canvas" and feeds errors to the properties panel; rules depend on the target engine version (https://github.com/camunda/linting). Custom rules are bpmnlint plugins in `.bpmnlintrc` (https://docs.camunda.io/docs/components/modeler/desktop-modeler/custom-lint-rules).
- **Token simulation:** play, pause and step through a process; toggle with `T`. "Token simulation is a BPMN learning tool, not a fully featured BPMN engine." "The modeling features will not work while you are in token simulation mode." (https://docs.camunda.io/docs/components/modeler/web-modeler/token-simulation)
- **Play / Test mode:** the current page calls it Test mode, a "Zeebe-powered testing environment" that "executes real process logic". Paths are saved as named test cases with assertions; coverage is the share of flow nodes reached, shown in blue (https://docs.camunda.io/docs/components/modeler/web-modeler/validation/play-your-process/).
- **Version diff:** a visual view and an XML code view. "Only differences that affect the execution of the BPMN process are highlighted." A Changes sidebar lists each change; selecting one highlights it on the diagram. Added elements are green with a plus. Any two versions can be compared (https://docs.camunda.io/docs/components/modeler/web-modeler/versions/).
- **Adopt:** errors on the canvas and in the property panel together; a list of changes linked to the diagram; ignore layout-only changes; saved simulation scenarios that can be rerun.
- **Do better:** our simulation evaluates the real conditions and assignee rules against sample attributes, with no engine and no side effects.

### Nintex Automation Cloud (palette and conditions)
- **Approval step:** "Assign a task to multiple users" offers task form or Express approval. Outcomes (Approve and Reject by default, up to 30) become branches on the canvas. Completion rules: all respond (majority wins), first response, a specific response, or a percentage. It has due dates with escalation or auto-complete, and reminders (https://help.nintex.com/en-US/nwc/Content/Designer/Actions/AssignaTasktoMultipleUsers.htm).
- **Conditions:** "Branch by condition" has two branches, Yes and No. Each condition is *When* (a variable) + *Operator* (filtered by the variable's type) + *Value*, combined with **all** or **any**. "You can add a maximum of ten conditions." (https://help.nintex.com/en-us/nwc/Content/Designer/Actions/Branchbycondition.htm)
- **Parallel:** "Run parallel paths" waits for all branches unless a completion condition says otherwise; at most 20 branches (https://help.nintex.com/en-us/nwc/Content/Designer/Actions/RunParallelPaths.htm).
- **Zapier Paths**, for comparison: each rule is field + condition + value, joined by AND or OR; one **fallback** branch per group runs when no other matches; rules are tested "based on the sample data in the fields used in your rules" (https://help.zapier.com/hc/en-us/articles/8496288555917).
- **Adopt:** a structured condition row (attribute, operator by type, value), all/any grouping, an explicit default branch, outcomes drawn as labelled edges.
- **Do better:** validation that flags overlapping conditions and branches with no default.

## Accessibility of node editors

### WCAG 2.2 criteria that bite (normative text from w3.org)
- **2.1.1 Keyboard (A):** "All functionality of the content is operable through a keyboard interface without requiring specific timings for individual keystrokes", except where the function depends on "the path of the user's movement and not just the endpoints" (https://www.w3.org/WAI/WCAG22/Understanding/keyboard.html).
- **2.5.7 Dragging Movements (AA):** "All functionality that uses a dragging movement for operation can be achieved by a single pointer without dragging, unless dragging is essential or the functionality is determined by the user agent and not modified by the author." The Understanding text gives "tap one item, then tap its counterpart" for connecting, and says keyboard support alone "does not automatically meet this success criterion" (https://www.w3.org/WAI/WCAG22/Understanding/dragging-movements.html).
- **2.4.11 Focus Not Obscured (Minimum) (AA):** "When a user interface component receives keyboard focus, the component is not entirely hidden due to author-created content." (https://www.w3.org/WAI/WCAG22/Understanding/focus-not-obscured-minimum.html). Property panels and the minimap can cover focused nodes.
- **2.5.8 Target Size (Minimum) (AA):** "The size of the target for pointer inputs is at least 24 by 24 CSS pixels", with exceptions Spacing, Equivalent, Inline, User Agent Control and Essential (https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html). Connection handles are the usual failure.
- **1.4.11 Non-text Contrast (AA):** "The visual presentation of the following have a contrast ratio of at least 3:1 against adjacent color(s)": user interface components, and "Parts of graphics required to understand the content" (https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html). This covers edges, handles, node borders and diff colours.
- **4.1.2 Name, Role, Value (A):** "For all user interface components … the name and role can be programmatically determined; states, properties, and values that can be set by the user can be programmatically set; and notification of changes to these items is available to user agents, including assistive technologies." (https://www.w3.org/WAI/WCAG22/Understanding/name-role-value.html)
- **4.1.3 Status Messages (AA):** "In content implemented using markup languages, status messages can be programmatically determined through role or properties such that they can be presented to the user by assistive technologies without receiving focus." (https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html). This covers the validation count, simulation progress and save results.

### The React Flow guide
Covered under R1. It gives focus, selection, arrow-key moves, a live region and localisable labels. It does not give a reading order that follows the flow, a way to connect nodes from the keyboard, or a list alternative. Those are ours.

### A structured list as the keyboard and screen-reader path
- **Jira Cloud workflow editor:** a Text/Diagram toggle. Text shows statuses with their transitions as a list; selecting one edits it "in the sidebar, the same way you would in the diagram" (https://support.atlassian.com/jira-cloud-administration/docs/work-in-text-mode/). This is the closest match to our plan: two views, one property panel.
- **Microsoft Visio:** "Screen readers navigate the diagram shapes in the order in which they were added to the diagram." The Diagram Navigation pane reorders them (https://support.microsoft.com/en-us/topic/make-your-visio-diagram-accessible-to-people-with-disabilities-e2c847a9-f010-4fef-af65-16e252829d44). Data Visualizer builds a flowchart from an Excel table, a table-first path to a diagram (same page).
- **W3C WAI images tutorial:** flow charts are complex images needing a long description, which can be "headings, text, and a table"; it advises to "show the description as part of the main content" (https://www.w3.org/WAI/tutorials/images/complex/).
- **Drupal Modeler:** claims "All modeler functionality is accessible via keyboard", with `aria-live` updates, 44×44 px targets, and testing with VoiceOver and NVDA. It does not describe a list alternative to the canvas (https://project.pages.drupalcode.org/modeler/accessibility/).
- **GoJS accessibility sample:** keyboard focus inside the canvas plus a live region describing the focused object; no mirrored list (https://gojs.net/samples/Accessibility.html).
- **Lucidchart, Miro, draw.io:** a documented list or outline view was not found.

## Recommendations

1. Use `@xyflow/react` 12.12.x (MIT) for the canvas. Keep the attribution badge; we do not subscribe to Pro. Track 13.0.0 and upgrade only after it is stable.
2. Use `@dagrejs/dagre` 3.1.x (MIT) for auto-layout, top-down, synchronous, on the main thread. No React Flow sub-flows; a parallel step is one node.
3. Write the definition diff by hand, keyed by step id, with connections compared as step properties. Show added, removed and changed with colour **and** an icon or text, and list the changes in a sidebar linked to the canvas.
4. Add `fast-check` 4.10.x and `@fast-check/vitest` 0.5.x (MIT) as web dev dependencies for property tests of layout, diff, validation and list/canvas equivalence.
5. Build the **list view** as a first-class editor, not a read-only outline: steps in flow order, branches nested, each row opening the same property panel as the canvas (the Jira model). Every action has a button there: add, delete, move, connect, set default branch. This is the keyboard path for 2.1.1 and the single-pointer path for 2.5.7.
6. On the canvas, keep React Flow's focus and selection. Positions come from the layout, so nodes are not draggable and React Flow's arrow-key moves are off; arrow keys instead move the selection along connections (ours to write); turn on `connectOnClick`; feed `ariaLabelConfig` from react-intl; give each node an accessible name stating its type, name and assignee.
7. Validate live. The server's checks (E03) are all errors today; a warning level (such as overlapping conditions) is later work. Drafts may save with errors; submitting for review requires none (E03 already works this way). Show the count in a `role="status"` region (4.1.3), mark the step on the canvas and in the list, and focus the step when an issue is selected (Power Automate, Camunda).
8. Edit conditions as structured rows: attribute, an operator filtered by type, a value; all/any grouping; an explicit default branch (Nintex, Zapier). E03's schema already requires `otherwise`; warning on overlapping conditions is later work.
9. Simulate against sample attributes in the browser or through a dry-run API, with no side effects and on the draft. Show the path taken, the assignee each step resolves to, and each step's deadline in words. Saved, rerunnable scenarios (Camunda test cases) are later work. Power Automate cannot test drafts; we should.
10. Check 2.4.11 (panels must not hide the focused node; pan to focus), 2.5.8 (handles and toolbar targets of at least 24×24 px) and 1.4.11 (edges, handles and diff colours at 3:1) in the E06 UX review, plus a manual screen-reader pass of the list view.
