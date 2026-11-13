# E05 Timers, SLAs and escalation — design

Decisions: ADR-0041 (timers, SLAs, escalation, business calendars). Builds on ADR-0018 (jobs), ADR-0034 (engine), ADR-0035 (data), ADR-0038 (definitions), ADR-0040 (resolution).

## 1. Module boundaries
- **`workflow`** owns business calendars, SLA computation, timers and escalation. The engine gains one command (`Escalate`) and stays clock-free. The runner computes due times and timers around transitions.
- **`memo`** shows overdue and due times, and sends reminder, overdue and escalation notifications in response to workflow events.
- **`common.jobs`** is unchanged: the timer task is built with `Jobs.tenantTask`.

## 2. Definition additions (schema 2, additive)
`sla` on `review`, `approve`, `parallel`, `doa` and `route` steps:

```json
{ "due": "P2D", "workingTime": true,
  "remind": { "before": "PT4H", "after": ["PT4H", "P1D"] },
  "escalate": { "after": "P1D", "action": "notify" | "reassign" | "nextLevel", "to": { assignee } } }
```

Durations are ISO 8601 with days, hours and minutes, at most 999 days. Validation rules:
- `remind.before` is shorter than `due`;
- `remind.after` holds at most 3 entries;
- `escalate.to` is required for `reassign`; for `notify` it is optional (default: the holder's supervisor);
- `escalate.to` is a person, position, role or queue that exists (the E04 checks).

## 3. Data (migration `workflow/V11`)
- **`workflow_calendar`** `(tenant_id PK, time_zone, week jsonb, version, updated_at)`. `week` holds working periods per ISO weekday, e.g. `{"7":[["09:00","17:00"]],…}`.
- **`workflow_calendar_day`** `(tenant_id, day date, kind CLOSED|WORKING|HOURS, periods jsonb, note ≤200)`, with primary key `(tenant_id, day)`.
- **`workflow_timer`** `(id, tenant_id, instance_id, step_id, task_id, kind REMIND_BEFORE|DUE|REMIND_AFTER|ESCALATE, ordinal, fires_at, state PENDING|FIRED|CANCELLED, fired_at)`, indexed on `(tenant_id, task_id) WHERE state = 'PENDING'`.
- **`workflow_task.due_at timestamptz`**, indexed `(tenant_id, due_at) WHERE state = 'OPEN'`, for inbox sorting and overdue flags.

All new tables have forced RLS and are covered by `RlsMetaTest`.

## 4. Behaviour

### 4.1 Business calendar
`BusinessCalendar.plus(start, duration)` adds working minutes. It walks the local dates in the tenant's zone, skips closed days, and uses the exception's periods or the weekday's. A start outside working hours begins at the next working period. Calendar time (`workingTime: false`) is plain `Instant.plus`. A sanity bound applies: at most 3 years of days are walked.

### 4.2 Due times and timers
On each `TaskCreated` effect of a decision task whose step has an SLA, the runner:
- sets `due_at` once per step activation and shares it across the step's tasks;
- creates timers: `REMIND_BEFORE` at `due − before`, `DUE` at `due`, each `REMIND_AFTER` at `due + after[i]`, and `ESCALATE` at `due + escalate.after`, with working time for each when the SLA says so.

Effects that close tasks (`TaskClosed`) cancel those tasks' pending timers. Moving a task to a new holder (reassign, forward, claim by another candidate) moves its pending timers to the new task, keeping their times. A step that runs again (send-back, resubmit) gets new timers.

### 4.3 Firing
The `workflow.timer` tenant task loads the timer under the instance lock. It returns unless the timer is `PENDING`, its task is `OPEN`, and the round is unchanged. It then marks the timer `FIRED` and acts:
- **`REMIND_*`:** publishes `WorkflowTaskReminder(taskId, kind)`. The memo notifies the holder: in-app always, by email per the user's preferences, with ids and the reference only.
- **`DUE`:** publishes `WorkflowTaskOverdue(taskId)`. The memo notifies the holder and the initiator.
- **`ESCALATE`:** runs `Escalate(system, taskId, action, to)` through the runner:
  - **`notify`:** adds no task. It records `ESCALATE` with the targets and publishes `WorkflowEscalated`, which tells the targets.
  - **`reassign` / `nextLevel`:** resolves the target (E04 resolvers; `nextLevel` is the supervisor of the **holder's** position) and moves the task like an administrator's reassignment. Exclusions (SOD) and eligibility apply. Nobody found gives a blocked `UNRESOLVED` task (E04).

The action's actor is the **workflow system actor**, a fixed id that is never a user and shows as "Workflow" in the history. The audit records the system actor. Retries follow the `Jobs` conventions. A tenant that is suspended pauses its timers.

### 4.4 Overdue in the product
- **Inbox:** items carry `dueAt` and an overdue flag, and can be sorted by due time.
- **Memo view:** the current step shows "Due in 4 working hours" or "Overdue by 1 day".
- **Admin open tasks:** an overdue filter.

### 4.5 Test clock
`WorkflowTimers.runDue()` fires every pending timer with `fires_at ≤ clock.now()` through the same handler. It is used by tests only; nothing in the API exposes it. Integration tests move a `MutableClock`.

## 5. API
| Endpoint | Purpose | Permission |
|---|---|---|
| `GET/PUT /api/v1/admin/workflow/calendar` | time zone and weekly periods (If-Match) | `workflow.calendar.manage` (new, privileged) |
| `GET /api/v1/admin/workflow/calendar/days?from&to`, `PUT …/days/{date}`, `DELETE …/days/{date}`, `POST …/days` (a range) | exceptions | same |
| `POST /api/v1/admin/workflow/calendar/preview` | "due when?" for a start and a duration | same |

## 6. Audit
`workflow.calendar.updated`, `workflow.calendar.day_set`, `workflow.calendar.day_removed`, `workflow.task.escalated`. Ids and codes only.

## 7. Performance
- Timers: about 3 future executions per SLA task. Executions per second stay far below the 1,000 at which db-scheduler suggests `lock-and-fetch`, so the default polling stays; the V2 load test checks the polling query time.
- Due computation: one calendar load per action, cached per tenant and version.
- **V2 volume:** the synthetic generator creates 10,000 active instances with SLA tasks and timers in the volume tenant. The Gatling inbox test runs against it with p95 < 300 ms (NFR P1), in the weekly load stage.

## 8. UX (ux-review.md)
Admin → **Business calendar**:
- the weekly hours per weekday;
- the time zone;
- holidays and special days: a list by month, adding one day or a range, closed, working, or other hours;
- a preview: "a 2-day SLA started now is due on …".

The inbox and memo view show due and overdue states. The definition JSON editor documents the SLA fields; E06's builder edits them.
