# E01 Forum types and forums — log

## 2026-11-18
- Plan written under the delegated gate (Phase 4 Q1): research (industry practice from vendor help centres, published bank charters and Basel CGO10; quorum building blocks), ADR-0056 (forum types, forums, membership) and ADR-0057 (quorum rules), design, threat model, tasks T1–T6, test plan, and screen flows J, K and L for the owner.
- Choices that came from the research:
  - Voting is separate from role (BoardPro).
  - Membership drives access (OnBoard reviews).
  - Term reminders at 42 and 1 days (BoardPro), with nothing removed automatically.
  - A structured quorum builder instead of CEL. Every charter found is a conjunction from a small vocabulary.
  - Recused members counting toward quorum is a setting (Exim counts them; the UK model articles do not).
  - Outcome kinds are fixed, with editable labels (Hyland).
  - No maker-checker on membership (none found); secretaries are limited instead.
