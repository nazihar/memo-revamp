---
status: accepted
date: 2026-11-13
deciders: Product owner (delegated gate, Phase 2 Q1), agent
---

# 0041 — Timers, SLAs, escalation and business calendars

## Context and problem statement
E03 definitions already carry `sla.due` and `sla.workingTime` per step, validated but not acted on. Banks expect a memo that waits too long to remind its holder, show as overdue, and escalate (to a supervisor, a queue or someone named) according to the step. "Too long" is counted in working time: the pilot's weekend is Friday and Saturday, holidays come from a gazette, and hours change during Ramadan (research, business calendars). At 10,000 active instances per tenant, timers must not slow the inbox (NFR P1) or the database (phase risk R4).

## Decision drivers
- The engine stays a pure function without a clock (ADR-0034); timers sit outside it, like resolution (ADR-0040).
- A timer never acts on a task that has moved on: firing re-checks the current state.
- Tenancy: scheduled work carries the tenant in its data and ids only (ADR-0018). Tenant data stays under forced RLS.
- Tests control time and run in milliseconds (TESTING_STRATEGY §12).
- No new infrastructure.

## Considered options
1. **One db-scheduler execution per timer, with a `workflow_timer` row as the source of truth** (chosen).
2. A periodic sweep over a due-time index. Simple, but it reads tenant tables across tenants in platform scope (or sweeps per tenant), and fires late by up to the sweep interval.
3. Timers inside the engine state (due times in `InstanceState`, checked on the next action). Nothing fires when nobody acts, which is exactly the case to handle.

## Decision outcome
Chosen option 1.
- **SLA in the definition** (schema 2, additive, on person steps):
  - `due` is an ISO 8601 duration in days, hours and minutes.
  - `workingTime` (default true) counts against the tenant's business calendar instead of calendar time.
  - `remind` takes `before` (one duration before due) and `after` (up to three durations after due).
  - `escalate` takes `after` (a duration after due) and an `action`:
    - `notify`: tell the escalation targets and keep the holder;
    - `reassign`: move the task to `to`, an assignee;
    - `nextLevel`: move it to the holder's supervisor by the reporting line.
- **When a task is created** on such a step, the runner computes the task's `due_at` and its timers (reminders, overdue, escalation) with the business calendar. It writes `workflow_timer` rows and schedules one db-scheduler execution each, in the action's transaction. When a task closes or moves, its pending timers are cancelled in that transaction. Reassignment, forwarding and claiming keep the step's due time; a step that runs again gets a new one.
- **Firing:** the execution carries `(tenantId, timerId)` only. It loads the row and does nothing unless the row is pending and its task is still open in the same round.
  - Reminders and overdue send notifications.
  - Escalation runs an engine command, `Escalate`, as the system. The action is recorded as done by the workflow (`ESCALATE`), and the event `WorkflowEscalated` is published.
- **Business calendar:** one per tenant, made of weekly working periods per weekday, dated exceptions (closed, working, or different hours) and the tenant's time zone. The arithmetic is pure and DST-safe: it works in local date-times per zone and is property-tested. With no calendar set, the tenant's weekdays and hours default to Sunday–Thursday 9:00–17:00 in its time zone, and the default is shown on the admin page.
- **Clock:** the runner and the timers take Spring's `Clock`, so tests move a `MutableClock` and run due timers directly through the same handler.

### Consequences
- Good: timers fire on time without anyone acting, cancel with the change that makes them moot, and never cross tenants.
- Good: due times and overdue state are columns, so the inbox can sort and flag without computing calendars per row.
- Bad: about three scheduler executions per open task with an SLA. This is measured by the V2 load test.
- Bad: changing the calendar does not move existing due times; it applies to tasks created afterwards. This is documented, and an administrator can reassign.
