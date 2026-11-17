# E01 Memo types, metadata and numbering — log

## 2026-11-14
- **Plan written** under the delegated gate (Phase 3 Q1): research, ADR-0043 (memo-type versions, metadata fields, attributes), ADR-0044 (numbering), design, threat model, tasks T1–T8, test plan, and UX review.
- **Deviation from the overview:** field validation has **no admin-defined patterns** (regular expressions), only lengths, bounds and choices. Patterns from tenant data are a ReDoS risk (threat model D3), and no use case needs them yet.
- **Owner review needed before T6 and T7:** the screen flows in `ux-review.md` (memo-type admin, create form) and three questions. Backend tasks T1–T5 proceed meanwhile.
