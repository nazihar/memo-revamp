# E05 Timers, SLAs and escalation — tasks

| Task | What | Size | Needs |
|---|---|---|---|
| T1 | **Business calendar:** `workflow_calendar` and `workflow_calendar_day`; admin API (weekly periods, time zone, exceptions, ranges, preview) under `workflow.calendar.manage`; `BusinessCalendar` arithmetic, property-tested (DST, closures, empty weeks bounded) | M | — |
| T2 | **SLA model and due times:** schema 2 fields (`remind`, `escalate`), validator rules; `workflow_task.due_at` set when a task is created on an SLA step, kept on moves, new on re-runs; inbox and memo view carry `dueAt` / overdue | M | T1 |
| T3 | **Timers:** `workflow_timer`, the `workflow.timer` tenant task, scheduling in the action's transaction, cancel on close, move on reassign / forward / claim; firing re-checks; `WorkflowTimers.runDue()` for tests | L | T2 |
| T4 | **Reminders and overdue notices:** `WorkflowTaskReminder`, `WorkflowTaskOverdue`; memo notifications (in-app and email, en/ar, ids and reference only) to the holder and, when overdue, the initiator | M | T3 |
| T5 | **Escalation:** `Escalate` command (notify, reassign, nextLevel), system actor, `ESCALATE` action and `WorkflowEscalated`, resolver for the holder's supervisor, SOD and eligibility applied, audit | M | T3 |
| T6 | **Volume and load:** synthetic volume V2 (10,000 active instances with SLA tasks and timers) in the generator; Gatling inbox test against it (p95 < 300 ms); weekly load stage | M | T3 |
| T7 | **Web:** Business calendar admin (weekly hours, time zone, special days by month, ranges, preview); due and overdue in the inbox (sort, badge), memo view and open tasks (filter); SLA help in the definition editor; en/ar | M | T1, T2 |
| T8 | **Close:** E2E (calendar admin; a memo of the demo type shows its due time), demo SLA on the demo workflows, ASVS rows, runbook, STATUS, epic log | S | all |

T1 first; T2 then T3; T4, T5 and T6 can run in any order after T3; each task merges as its own PR when CI is green.

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:** Given Friday–Saturday weekends, 9:00–17:00 hours and a holiday on Sunday, a 1-day working SLA started Thursday 15:00 is due Monday 15:00 in Asia/Dhaka.
- **T3:** Given a task with an SLA, approving it cancels its pending timers; reassigning it moves them to the new task with the same times; a timer whose task closed meanwhile does nothing when it fires.
- **T5:** Given `escalate: nextLevel`, at the escalation time the task moves to the holder's supervisor, the history shows "Escalated by the workflow", and the event is published once even if the timer runs twice.
- **T6:** 10,000 active instances with timers: inbox p95 < 300 ms.
