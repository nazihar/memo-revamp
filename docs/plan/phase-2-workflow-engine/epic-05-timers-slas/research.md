# E05 Timers, SLAs and escalation — research

All sources were accessed on **2026-11-13**. Research items come from the phase overview §6: R7 (timers at volume), and the business-calendar part of E05. R11 (the benchmark, E03) is reused for escalation behaviour. "Not found" means a search was made and no primary source turned up; nothing below is filled in from memory.

## R1 — Libraries this epic adds

None. Timers run on **db-scheduler**, already in the platform (ADR-0018, `common.jobs.Jobs`). Business-calendar arithmetic is plain `java.time`. The load test reuses the platform's Gatling setup (Phase 1a NFR P1).

## R7 — Timers at volume

### db-scheduler (README, github.com/kagkarlsson/db-scheduler)
- Throughput: "Tested to handle 2k - 10k executions / second."
- Polling: `fetch` (the default; fetched without a lock, locked on execution) or `lock-and-fetch` (`select … for update skip locked`, "less overhead"). The README recommends `lock-and-fetch` above about 1,000 executions per second, with `0.5, 1.0` for normal use.
- One-time tasks: an instance id unique within the task, data serialised with the instance, and `schedule`, `reschedule` and `cancel` on the `SchedulerClient`.
- Cluster: "Guarantees execution by single scheduler instance". Heartbeats every 5 minutes; an execution is dead after 6 missed heartbeats.
- Precision depends on the polling interval (default 10 s).
- Volume: an index on `(execution_time, priority)` is suggested when many executions are due or in the future. No table-size limit is given.
- In this codebase, scheduling through `SchedulerClient` joins the caller's Spring transaction (ADR-0018; `notification.Mail` relies on it). A rolled-back business change therefore schedules nothing.

### The two designs

| | One db-scheduler execution per timer | A periodic sweep over a due-time index |
|---|---|---|
| Exactness | Fires at its time, within the polling interval | Within the sweep interval |
| Cancel and move | `cancel` / `reschedule` by instance id in the same transaction | Update the row; the sweep reads the current state |
| Tenancy | The tenant travels in the task data (ADR-0018). The scheduler table is a platform table holding ids only | A sweep across tenants needs platform-scope reads of tenant tables, or one sweep per tenant |
| Load at 10,000 instances | About 30,000 future executions (due, a reminder, an escalation), far below the stated 2k–10k executions per second; the polling query uses the `execution_time` index | One query a minute per node over an indexed table |
| Failure | Retries with backoff (the `Jobs` conventions); the handler re-checks the timer's row | One failing timer must not stop the batch |

**Chosen:** one execution per timer. The `workflow_timer` row (tenant data, forced RLS) stays the source of truth. The execution carries `(tenantId, timerId)` only, and the handler fires nothing unless the row is still pending and its task still open. A sweep would need platform-scope reads of `workflow` tables, which ADR-0017 avoids.

## Business calendars

### How products count working time
- **ServiceNow SLA definitions** (servicenow.com docs, "SLA duration types", "Create an SLA definition"):
  - A duration is counted against a **schedule**; without one, the SLA runs 24×7.
  - With an 8-hour-a-day schedule, "1 Day" breaches three business days later. A 16-hour SLA on an 8–5 weekday schedule breaches after two business days.
  - Relative durations (such as "end of next business day") are scripts, and pause conditions do not apply to them.
- **Camunda** (docs.camunda.io timer events; forum.camunda.io thread 4293): timers take ISO 8601 durations or dates. No built-in business calendar was found. The forum's working-hours answers compute the due date in a process variable. Completing a user task cancels its boundary timers.
- **SAP Flexible Workflow deadlines:** not found in the sources read.

### The pilot's calendar (Bangladesh)
- **Weekly holidays:** banks close on **Friday and Saturday**. Reports of Bangladesh Bank notices for 2025–2026 confirm it, including the February 2026 election closure (Prothom Alo, UNB, Dhaka Tribune).
- **Holiday list:** Bangladesh Bank publishes a yearly bank holiday list based on the Ministry of Public Administration's gazette: **28 bank-closure days for 2026**, excluding weekly holidays (The Financial Express, Nov 2025). The dates themselves were not found.
- **Working weekends:** the central bank can open a weekly holiday. In October 2025 banks opened on a Saturday to offset the Durga Puja closure (Prothom Alo).
- **Hours:** opening hours change for periods. During Ramadan banks work 9:30–14:30, and the weekend stays Friday and Saturday (The Daily Star, Ittefaq).
- **Time zone:** Asia/Dhaka, UTC+6, with no daylight saving. Other tenants may use zones that have it, so the arithmetic must be DST-safe.

### What the model needs
1. Working periods per weekday, more than one a day allowed.
2. Dated **exceptions**: a closure (holiday), an extra working day, or different hours on a date. A range is entered once and stored per date.
3. The tenant's time zone.
4. Per-step choice of working time or calendar time.
5. One calendar per tenant is enough for the pilot. Several calendars (per branch or country) are recorded as later.
