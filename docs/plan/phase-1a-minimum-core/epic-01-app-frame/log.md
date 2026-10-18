# E01 App frame and visual design — log

## 2026-10-18
- Round 1 visual review: two directions on a canvas; the owner chose **Direction A (Ledger)** (`ux-review.md`).
- Plan written (research, ADR-0021, design, threat model, tasks, test plan). Gate delegated (overview Q1).
- Findings: TypeScript 7.0 is out but `typescript-eslint` supports `<6.1`, so we stay on 6.0.x; `eslint-plugin-jsx-a11y` does not support ESLint 10 and is unmaintained, so it is not adopted (axe covers accessibility).
