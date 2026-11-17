# E04 Final rendition (PDF/A) — log

## 2026-11-15 — Plan written
- Research R1, R3, R8 and records practice by two research agents, checked by the lead (`research.md`).
- ADR-0052 (generated pages: one interface, PDFBox with Noto Sans for English) and ADR-0053 (one PDF/A-2u record per approved memo, validated by veraPDF, locked and re-hashed), both accepted under the delegated gate.
- Design, threat model, tasks T1–T7 and test plan written. No screen-flow review is needed for E04 (overview §10 Q1); the decisions taken under the delegation are listed in `ux-review.md` for the owner.
- **Changes against earlier plans:**
  - ADR-0048's "second fill" at approval is dropped. Re-filling would change the document the stamps bind (ADR-0051).
  - The overview's PDF/A-3 option for attachments that cannot be converted is replaced by placeholder pages naming the locked originals by hash.
