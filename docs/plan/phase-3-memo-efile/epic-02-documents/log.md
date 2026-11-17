# E02 Documents — log

## 2026-11-14 — Plan
- **Research:** a research agent gathered sources on ONLYOFFICE 10, the conversion API, Tika 4, diff libraries, PDF.js and desktop Word (`research.md`). The lead checked the key facts directly: 10.0.0.1 was pushed on 2026-10-12, and all three deploy paths set `ALLOW_PRIVATE_IP_ADDRESS=true`.
- **Decisions:**
  - ADR-0045: renditions and text as background jobs, through ONLYOFFICE conversion and a separate Tika server.
  - ADR-0046: attachments with stricter-only classification and a per-round binding.
  - ADR-0047: ONLYOFFICE 10, edit rooms with session-bound keys, co-authors, spreadsheets and hardening. Desktop Word is not adopted. ADR-0037's key rule is amended.
- **Licence brief** for the owner's lawyer: `docs/legal/onlyoffice-licence-brief.md` (overview Q5c).
- **Gate:** delegated (Phase 3 Q1); E02 has no screen review on the owner's list. Planned and started the same day.
- **Found while planning:**
  - We switch off ONLYOFFICE's private-address filter today (`ALLOW_PRIVATE_IP_ADDRESS=true`). T1 replaces it with an allow-list.
  - Our Phase 2 key rule would split a co-editing session after a force-save. Not visible yet, because only the initiator edits.
