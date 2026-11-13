# E05 Timers, SLAs and escalation — threat model

STRIDE over business calendars, timers, reminders and escalation. ASVS 5.0 references in brackets.

| # | Threat | Flow | Mitigation | ASVS |
|---|---|---|---|---|
| S1 | A timer acts for the wrong tenant | firing | The execution carries `tenantId` and `timerId` only; the handler binds the tenant from the data (ADR-0018) and reads the timer under forced RLS; an unknown tenant drops the execution | V8.4.1 |
| T1 | A stale timer moves a task that already moved on | firing | The `workflow_timer` row is the source of truth; firing requires `PENDING`, the task `OPEN` and the same round, under the instance lock | V2.1 |
| T2 | Calendar edited to pull due times forward and force escalations | calendar | `workflow.calendar.manage` is privileged; changes are audited; existing due times do not move | V8.1.1 |
| R1 | An escalation cannot be traced | escalation | `ESCALATE` action by the workflow system actor with action, target and rule; `workflow.task.escalated` audit; `WorkflowEscalated` event | V16.2.1 |
| I1 | Reminder and overdue emails reveal memo content | notifications | Emails name the reference and the step only; recipients not cleared for the memo are told nothing | V14.2 |
| D1 | Huge numbers of timers or long calendars slow the system | timers | Three to five timers per task at most; calendar walks bounded to 3 years; V2 load test weekly | V2.4 |
| D2 | A failing timer is retried forever | firing | `Jobs` retries with backoff and a cap; permanent failures dropped and counted | V16.5 |
| E1 | Escalation hands a task to someone excluded (initiator, interested) | escalation | The same resolution and SOD rules as E04; nobody eligible → blocked `UNRESOLVED`, never the excluded person | V8.2.2 |
