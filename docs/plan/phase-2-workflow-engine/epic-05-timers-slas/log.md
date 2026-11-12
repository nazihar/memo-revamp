# E05 Timers, SLAs and escalation — log

## 2026-11-13
- Plan written under the delegated gate (Phase 2 Q1): research (R7 timers at volume, business calendars), ADR-0041, design, threat model, tasks T1–T8, test plan, UX review notes. Implementation starts with T1.
- Choices worth knowing:
  - **Timers:** one db-scheduler execution per timer, with a `workflow_timer` row as the source of truth. Firing re-checks the task, so a stale timer does nothing.
  - **Calendar:** one business calendar per tenant, with weekly periods, dated exceptions and a time zone. Changes apply to tasks created afterwards.
  - **Escalation:** runs through the engine as the workflow system actor, with the E04 resolution and separation-of-duties rules.
- Out of E05 scope, recorded for later:
  - several calendars per tenant (per branch or country);
  - pausing an SLA while a task waits for an answer;
  - SLA reports and dashboards (Phase 7 reporting);
  - recalculating due times when the calendar changes.
