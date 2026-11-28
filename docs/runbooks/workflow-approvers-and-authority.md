# Workflow approvers: positions, queues, substitutes and the delegation of authority

**What it is:** workflow steps can go to people by their place in the organisation. Examples are the holder of a position, the initiator's supervisor, a unit head, the holders of a role, or a queue. Steps can also follow the **delegation of authority (DoA)**, the rules saying who must approve which memos, group by group. People are found when a step starts, and the memo records who was found and why (design E04, ADR-0040).

**Who:**
- **DoA:** authors hold `workflow.doa.manage` and reviewers hold `workflow.doa.approve`. A version is always reviewed by someone other than its author.
- **Queues:** `workflow.queues.manage`.
- **Substitutions and reassignment:** `workflow.tasks.reassign`.

Tenant administrators hold all of these by default.

## Changing the delegation of authority (in the app)
1. Administration → **Delegation of authority** → **New draft**. The draft starts as a copy of the version in force.
2. For each rule, set:
   - its scope: memo type, the initiator's unit (with or without the units below it), and a condition such as `item.classificationRank >= 2`;
   - its **approval groups** in order: each needs a number of approvals from the holders of positions or roles.

   Rules are tried from the top, and the first that fits applies, so put the most specific first.
3. Set **Takes effect on** (today or later), then **Save draft** and **Submit for review**.
4. A second person reviews it. **Approve and publish** needs a step-up; **Send back** needs a remark. The new version applies to memos started on or after its date. Memos already running keep the version they started with.
5. Before submitting, **Who approves this?** on the same page shows who a memo would go to under any version.

## When nobody can be found
A step whose position is vacant, or whose DoA group cannot be filled, does not skip. It waits with a task **Nobody found for this step**, and the people who reassign tasks are told. Administration → **Open tasks** → **Waiting for reassignment** → **Reassign** (see `workflow-reassignment.md`). Typical causes:
- **A vacant position:** assign someone in Organisation. Open steps for that position move to the new holder by themselves: at once for an assignment from today, at the start of its first day for one from a later date.
- **No DoA rule fits:** fix the rules and publish a new version. The memo keeps its old version, so reassign it.
- **Everyone was excluded by separation of duties:** they are the author, already approved in this round, or declared an interest. Reassign to someone else.

## Absences: substitutions
Administration → **Substitutions** → **Add substitution** names who acts for whom, from a date and until a date (not included), or open-ended.
- While a substitution is in force, new tasks for the person go to the substitute, who acts for them; the history shows "for …".
- A substitution never applies to a memo the substitute wrote or has an interest in.
- **Move open tasks** (step-up) also moves the tasks the person already has.
- **End today** stops it.
- A person has at most one substitute at any date.

## Staff changes
- **A position changes hands:** open steps resolved from that position move to the new holder. They wait as "nobody found" while the position is vacant, or go to the step's fallback if it has one.
  - **Changed with immediate effect** (an assignment edited so that it starts or ends today): the steps move at once, recorded as reassignments by the administrator who made the change.
  - **Recorded for a later date** (a successor from next Monday, a term that ends): the steps move at the start of that day in the organisation's time zone (`default.timezone`), within 15 minutes of midnight. They are recorded as reassignments by **Workflow**, and in the audit log as `workflow.task.reassigned` with the cause `HOLDER_CHANGED` by the system actor `workflow-position-holders`. Until that day, the current holder keeps the task.
  - **Shared steps:** a claim by someone still in the pool stays. The people who left lose their tasks.
  - **Action items** owned by a position follow the same way. With nobody in the position, the action has no holder until someone is assigned, and its owner's task waits for reassignment.
- **A person is deactivated:** their open tasks wait for reassignment, recorded by the administrator who made the change.

**Background work:** the platform task `workflow-position-holders-sweep` schedules, every 15 minutes, one `workflow-position-holders` per active tenant. A tenant's run does its work once a day, at the first run after midnight in its zone. It records the day in `workflow_holder_sync`. It moves only the open steps whose positions changed holder since the last run, and only when their recorded people differ from the holders that day. After a gap of more than 31 days, or on the first run, it checks every open position step.

**Troubleshooting: a task still with a former holder after the date.**
1. Check the organisation's time zone. The change applies at midnight there, not in UTC.
2. Check the assignment's dates. The end date is the first day the person no longer holds the position.
3. Check the scheduler: the failure metric `memo.jobs.failed` with `task=workflow-position-holders`, and the day in `workflow_holder_sync` (`synced_on`, one row per tenant; read it as the tenant).
4. If a run failed, the next sweep runs the day again. Steps already moved are not moved twice.
5. To move a task at once, reassign it (Administration → **Open tasks**).

## Refusals
| Refusal | What to do |
|---|---|
| `SAME_PERSON` | Another reviewer must decide. |
| `DRAFT_EXISTS` | Finish or send back the version in progress. |
| `EFFECTIVE_DATE_TAKEN` | Another version takes effect that day; choose another date. |
| `DOA_INVALID` | Fix the listed problems: unknown position or role, bad condition, empty group. |
| `OVERLAP` | The person already has a substitute for some of those dates. |
| `route:doa-missing` | At submit, the author's route does not reach a group's approvers. Shown with the names; add them to the route. |
| `route[i]:not-allowed` | The memo type limits who may approve. |
| `FORWARD_OUTSIDE_LIMIT` | The step only lets its holder forward within their unit or legal entity. |

**Not an operator task:** platform operators cannot see or change a tenant's DoA, queues or substitutions (ADR-0031).
