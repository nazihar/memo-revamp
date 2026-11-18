# E03 Meetings and the agenda builder — log

## 2026-11-18
- **Plan written under the delegated gate** (Phase 4 Q1):
  - research: R1 versions; R2 from the help centres and reviews of eight portals (Convene, iBabs and Boardvantage publish almost nothing), the CGI and Board Intelligence 2026 board-reporting guidance, BoardSource and Board Intelligence on consent agendas, the bank terms of reference of Bank of Cyprus, Emirates NBD and HSBC, Basel 2015 and ORIC on conflicts, and WCAG 2.2 on dragging;
  - ADR-0060 (meetings and the agenda model) and ADR-0061 (meeting papers and the one access rule);
  - design, threat model, tasks T1–T9, test plan, and screen flows N–R for the owner with E02's flow M.
- **Choices that came from the research:**
  - A working draft with immutable published revisions and "What changed since you last looked" (Boardable, BoardPro, OnBoard).
  - Three levels, with items numbered continuously and renumbered only at publication.
  - The chair agrees the first publication (CGI, Emirates NBD §4.1.3), and the secretary may record it on the chair's behalf.
  - The paper deadline is five working days by default (CGI 2026, Bank of Cyprus §5.2), with E02's late modes enforced at placement.
  - Any member can lift a consent item without a reason or approval (BoardSource, Board Intelligence). No portal documents this inside the software.
  - "Only these people" restrictions on items and papers that survive replacement (against Diligent's re-permissioning complaint). Titles stay visible unless hidden (Admincontrol, Diligent Community).
  - Every move is a command before it is a drag (WCAG 2.2 SC 2.5.7, Diligent's "Move to").
  - dnd-kit 6.3.1 / sortable 10.0.0 pinned rather than the pre-1.0 `@dnd-kit/react`.
- **Changes from the overview, recorded in ADRs:**
  - **Members read the papers, not the memo** (ADR-0061). The overview assumed members would become memo readers through ADR-0050's reader table. That would give them the whole memo file (note sheet, every version, annexes left out of the pack) and put recusal into the memo rule. To report to the owner with the flows.
  - **Meetings live in the `meeting` module** (`AGENTS.md §4`), not in `forum`. The forum module keeps the agenda template on the type version (`forum/V5`) and publishes its events, `ForumPolicies` and `SubmissionPlacements`, so the dependency runs meeting → forum only.
  - The overview's meeting status "draft → scheduled → in progress → held → minutes approved → closed" is kept. E03 builds it up to held and cancelled.
- **Found while reading:**
  - The forum events (`ForumChanged`, `ForumTypePublished`, `ForumSubmissionChanged`) are in `forum.internal`, so no other module can listen to them yet. T1 moves them.
  - The web has no dnd-kit yet: the workflow builder uses React Flow.
  - `FilePurpose.mayRead` sees the file, not the version, so pinned paper versions are served through a new `FileServing` entry point behind the meeting rule.
