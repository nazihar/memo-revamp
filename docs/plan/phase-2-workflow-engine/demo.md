# Phase 2 demo — the workflow engine

A 40-minute walk through Phase 2 on the dev environment (`https://dev.memo-revamp.naziharitsolution.com`) or locally (`pnpm dev`, then `http://localhost:5173`). It assumes the D1 demo (`epic-01-first-slice/demo.md`) has been seen; this one shows what came after it.

Every demo account uses the password and TOTP secret in `deploy/compose/.env` (`MEMO_DEMO_PASSWORD`, `MEMO_DEMO_TOTP_SECRET`). Use two or three browser windows (private ones) to switch people quickly.

| Account | Role in the demo |
|---|---|
| `admin@demo.example` | Builds a workflow; sets up the calendar and memo types |
| `reviewer@demo.example` | The second administrator, who approves what the first one wrote (maker-checker) |
| `user@demo.example` | Initiator |
| `head.credit@demo.example`, `director@demo.example`, `head.finance@demo.example` | Approvers |

## 1. Definitions, not code (E03)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 1 | A defined workflow | `user@` → New memo, type **Credit facility** | No route to build: the definition names every step |
| 2 | Opinion, send back, approval | `head.credit@` and `director@` on that memo | Ask for an opinion; recommend; the director sends it back to the review; after a second recommendation, approve. The history shows each action and the step it happened at |
| 3 | Versions stay put | `admin@` → Administration → Workflows → Credit facility | A version is written, reviewed by someone else, then published. Memos keep the version they started on; an administrator can move them, with a preview |

## 2. Who decides (E04)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 4 | A shared desk | `user@` → a **Credit under authority** memo, then `head.credit@` | The credit desk is a queue: one person takes it, the others see who holds it; releasing gives it back |
| 5 | Delegation of authority | `admin@` → Administration → Delegation of authority | Rules by item and amount, in groups; maker-checked like definitions; the simulator says who would approve |
| 6 | Substitution | `admin@` → Substitutions | While someone is away, their tasks go to a substitute, who acts "for" them, recorded as such |

## 3. Deadlines (E05)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 7 | Business calendar | `admin@` → Administration → Business calendar | Working hours per weekday in the organisation's time zone; holidays; "When is it due?" |
| 8 | Due and overdue | `head.credit@` → Inbox | A credit review is due in a working day; overdue tasks say so in the inbox, the memo and the open tasks |

## 4. The builder (E06)
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 9 | Diagram and steps | `admin@` → Workflows → Credit facility | Three views of one version: the diagram, the Steps view (the same in words, for keyboard and screen readers) and the JSON |
| 10 | Build | `admin@` → New workflow | In the Steps view, add a review, a branch on classification rank, a parallel committee and a director with a two-day deadline whose supervisor is told a day late. Problems appear as you go; undo works |
| 11 | Simulate | The draft → Simulate | Rank 0 is approved through the director; rank 2 stops at the committee, because nobody in the demo is cleared above rank 0. Nothing is saved |
| 12 | Review and publish | Submit, then `reviewer@` opens it | The reviewer approves with a step-up; the submitter could not. For a later version the reviewer lands on **Changes**: what was added, removed and changed, in words and on the diagram |
| 13 | Use it | `admin@` → Memo types → Add; then `user@` writes a memo of that type | The memo goes review → branch → director, with its due time, to Approved |
| 14 | Keyboard | The diagram | Tab into it, arrow keys follow the connections, Enter opens the panel beside it, Delete asks first |

## 5. Load and audit
| # | Show | Who, where | What to point out |
|---|---|---|---|
| 15 | Audit | `admin@` → Audit, filter `workflow.` | Definition changes, task actions, escalations, migrations; acting-for recorded |
| 16 | Load | GitHub → Actions → **load** (weekly) | The inbox under 10,000 active instances with timers, p95 target 300 ms |

**Automated evidence:** each journey above runs as Playwright on every pull request:

| Journey | Spec |
|---|---|
| The first slice | `memo.spec.ts` |
| A defined workflow | `workflow.spec.ts` |
| The desk and DoA | `authority.spec.ts` |
| The calendar and due times | `workflow.spec.ts` |
| The builder exit journey | `builder.spec.ts` |

The load test is `.github/workflows/load.yml`.
