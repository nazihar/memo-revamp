# Workflow deadlines: business calendar, reminders and escalation

**When:** a workflow step has a deadline (an SLA, design E05 §2). Its task shows **Due …** to the holder, reminders go out before and after the due time, and the task may escalate. Use this page when setting up the calendar, or when someone asks why a memo is overdue, why a reminder did or did not arrive, or why a task moved.

## The business calendar (organisation administrator, in the app)
1. Administration → **Business calendar**. Holders of `workflow.calendar.manage` see it (tenant administrators by default).
2. **Working hours**: one or more periods per weekday, in the organisation's time zone (shown on the page). Until someone saves them, the hours are Sunday to Thursday, 9:00 to 17:00. Save with **Save working hours**. If someone else saved in the meantime, the page reloads their hours and says so; check them and save again.
3. **Holidays and special days**: pick a month and add a day or a range. Each day is either closed or worked with its own hours, and can carry a note. Remove a day to return it to the weekly hours.
4. **When is it due?** previews a deadline (for example `P2D`, two working days, or `PT4H`, four working hours) counted from now.

Changes apply to tasks that start afterwards. Tasks already waiting keep their due times (ADR-0041), so moving a holiday does not shorten anyone's deadline. Every change is audited: `workflow.calendar.updated`, `.day_set` and `.day_removed`.

## How a deadline is counted
- In **working time** (the default), a day means a working day and hours count working hours. Closed days and time outside the hours are skipped. `"workingTime": false` counts calendar time instead.
- A task that is reassigned, transferred or escalated to someone else **keeps its step's due time and its timers**. A send-back or a new round starts new deadlines.
- The demo workflows show the shape:
  - "Credit facility": the credit review is due in one working day (reminder two hours before). The director is due in two (reminder four hours before; one day late, the director's supervisor is told).
  - "Credit under authority": the desk is due in four working hours. The authority is due in two working days.

## Who is told
- **Reminder** (`remind.before`, `remind.after`): the holder. The in-app notice and the email name the memo reference and the step only.
- **Overdue** (at the due time): the holder and the memo's initiator.
- **Escalation**: the history shows "Escalated by the workflow" and the audit log records `workflow.task.escalated`.
  - `notify` tells the target (default: the holder's supervisor), and the task stays where it is.
  - `reassign` moves the task to the named person.
  - `nextLevel` moves it to the holder's supervisor.

  A person who may not decide (the initiator, someone with a declared interest, someone not cleared) is never chosen. If nobody is eligible, the task is **blocked**: see [workflow-reassignment.md](workflow-reassignment.md).
- People who are not cleared for the memo are told nothing.

## Overdue tasks
Administration → **Open tasks** → filter **Overdue** lists every open task past its due time, with its **Due** column. Reassigning one there (with a reason and a step-up) moves its timers along with it.

## Operations
- Each timer is a `workflow_timer` row plus one scheduled execution of the job `workflow-timer`, which carries the tenant and timer ids only.
- When it fires, the job checks that the timer is still pending and the task still open, so a late or repeated run does nothing twice.
- If the app was down, due timers fire when it starts again.
- Failed runs retry with backoff. After the retry limit the job logs `Task workflow-timer failed … giving up` and increments `memo.jobs.failed`; the timer row stays `PENDING`.
- Nothing re-fires a dropped timer: that reminder or escalation is missed. The task still shows its due time, the **Overdue** filter still lists it, and the holder and initiator can still act. Fix the cause and record the incident. A sweep of pending timers past their time is a follow-up (E05 log, "Carried").
- Load: the weekly **Load** workflow (`.github/workflows/load.yml`) runs the inbox with 10,000 active instances that have timers (volume V2). The target is p95 < 300 ms.

**Not an operator task:** platform operators do not see tenant calendars or tasks (ADR-0031).
