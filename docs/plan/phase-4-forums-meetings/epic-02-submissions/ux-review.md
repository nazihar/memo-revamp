# E02 Submissions and the escalation link — UX review

> Phase 4 Q1: the secretary's queue goes to the owner before build, together with E03's agenda builder and meeting page. Structural wireframes; the existing look and components.

## Flow M — Submissions

**M1. Committee page → Submissions tab** (secretaries, chair, administrators)
```
Board Risk Committee (BRC)        Members │ Submissions (3) │ History │ Policy
──────────────────────────────────────────────────────────────────────────────
[ Waiting (2) ] [ Accepted (1) ] [ Deferred ] [ Closed ]
┌──────────────┬──────────────────────────────┬──────────┬──────────┬────────┬────────────────┐
│ Purpose      │ Memo                         │ From     │ Waiting  │ Flags  │                │
├──────────────┼──────────────────────────────┼──────────┼──────────┼────────┼────────────────┤
│ For decision │ CR/HO-CR/2026-27/0042        │ R. Khan  │ 2 days   │        │ [ Triage ▾ ]   │
│              │ Term loan, Delta Textiles    │          │          │        │                │
│ For info     │ RSK/2026/0007 (restricted)   │ N. Islam │ 7 days ⚠ │ late   │ [ Triage ▾ ]   │
└──────────────┴──────────────────────────────┴──────────┴──────────┴────────┴────────────────┘
⚠ waiting longer than 5 working days
Triage ▾ : Accept… · Defer… · Return for more information… · Decline… · Refer to another committee… · Edit cover sheet
```
- **Accept**: optional target meeting date. The date is checked against the paper deadline: "Late: the Chair's approval will be needed when it goes on the agenda."
- **Return, Decline, Refer**: a reason is required. The text says what happens: "The memo goes back to its author to complete."

**M2. Memo page → Committee panel** (the submitter and anyone who reads the memo)
```
Committee
  With the Management Credit Committee — secretary: Demo Company Secretary
  ● 18 Nov  Sent by the route after Head of Credit's review
  ● 18 Nov  Accepted for the meeting of 12 Dec
  ○ Next: decided at the meeting
```

**M3. Inbox and tracking:** where an approver would be named, a waiting memo shows "With the Management Credit Committee".

**M4. Workflow builder:** the "Escalate to committee" step gains a switch.
```
Committee   [ Management Credit Committee ▾ ]
[x] Wait for the committee's decision
    Off: the memo is sent for information and its route continues.
```
