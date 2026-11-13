# E05 Timers, SLAs and escalation — test plan

| Task | Layer | Tests |
|---|---|---|
| T1 | Unit + property | `BusinessCalendarTest` (examples: weekends, holidays, extra working days, other hours, a start outside hours, multiple periods a day); `BusinessCalendarProperties` (adding is monotonic, additive over splits, never lands outside working periods, DST zones such as Europe/London never skip or repeat working minutes) |
| T1 | Integration | `BusinessCalendarIT` (API: If-Match, ranges, preview, default calendar, audit, RLS); matrix rows |
| T2 | Unit + integration | validator cases for `remind` and `escalate`; `SlaDueIT` (due set on creation, kept on reassign, new after send-back, calendar vs working time) |
| T3 | Integration | `TimersIT` with a `MutableClock`: scheduled in the action's transaction (rollback leaves none), cancelled on approve, moved on reassign, a stale timer does nothing, a suspended tenant pauses |
| T3 | Property | invariant 23: every open task on an SLA step has exactly its pending timers; no pending timer belongs to a closed task |
| T4 | Integration | `ReminderIT`: reminders and overdue notices to the right people, en/ar, no memo content in emails |
| T5 | Unit + integration | `EscalationTest` (engine `Escalate`); `EscalationIT` (notify, reassign, nextLevel, SOD exclusion, nobody found → blocked, idempotent under a double run, audit) |
| T6 | Load | Gatling inbox against V2, weekly stage, p95 < 300 ms; the polling query plan uses the index |
| T7 | Unit (web) | calendar page (weekly hours, ranges, preview), inbox overdue badge and sort, memo view due; axe on each |
| T8 | E2E | calendar admin journey; a memo of the demo workflow shows its due time; a11y |
| T8 | Matrix | `p2-e05-timers.yaml`: every new endpoint |
