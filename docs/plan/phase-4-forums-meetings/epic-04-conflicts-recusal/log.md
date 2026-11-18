# E04 Conflicts of interest and recusal — log

## 2026-11-18
- **Plan written under the delegated gate** (Phase 4 Q1; E04's screens are not among those the owner reviews):
  - research: R1 (nothing new to add) and R6 from the Basel Committee's 2015 principles, EBA/GL/2021/05, the Companies Act 2006 s.177 and s.185, the UK model articles, the UK Code 2024, the Charity Commission, ORIC, the ABA (2025), CGI's *Minute taking* guidance and factsheet, DWPV on directors' information rights, a Bangladeshi bank's code of conduct and Bangladesh Bank's 2025 dissent directive, and the help centres or product pages of eScribe, OnBoard, Diligent, Boardvantage, Convene, Admincontrol, BoardPro, iBabs and BoardEffect;
  - ADR-0062 (per-item declarations and one recusal rule);
  - design, threat model, tasks T1–T9, test plan with the recusal leak suite, and flows S–U recorded as delegated.
- **Choices that came from the research:**
  - **Leave the room by default.** Basel ¶83 and EBA ¶115(c) ask for abstention; EBA ¶125, ORIC, the ABA and the Charity Commission go further (no papers, no discussion, no decision). A bank product takes the stricter reading.
  - **A ruling by the chair** for a member who thinks the interest is immaterial (EBA ¶119, CGI §5.3.6, ORIC), with the papers withheld until then (fail-closed).
  - **The declaration is the access change.** Every portal studied treats a declaration as a record and restricts access by hand; none documents a declaration that withholds papers, notifications and the decision.
  - **Carry-over** from the memo's interests (SOD-4) and from an earlier placement of the same paper, and a declaration on a submission item recorded back on the memo, so a director never declares the same matter twice and never approves it later.
  - **One review confirmation per meeting**, re-asked only for new items (ABA's disclosure statement; CGI warns against boilerplate).
  - **The quorum warning** before the meeting from the committee's own rule (CGI §5.2.1: the secretary must know whether the meeting is quorate; recusals are the usual reason for doubt).
- **Change from the overview, recorded in ADR-0062, to report to the owner:** the recused member's minutes show the item's heading, their declaration and the decision, not the discussion. The overview said "a version without the items they were recused from". CGI §6.4 found financial-services boards lean to redaction, with a recognised middle view that the director "is entitled to know the decision, but not any detail of the discussion". E06 builds it.
- **Confirmed against the research:** Phase 4 Q6 (per item now, a standing register later) is consistent with every source; per-matter disclosure is the core duty, and a register is good practice on top (EBA ¶114, s.185, CGI).
- **Found while reading:**
  - Memo interests (`memo_interest`) are internal to the memo module; T3 adds a published `MemoInterestLinks`.
  - The rule needs each revision item's parent (sub-items inherit the parent's recusal). E03 T3 must keep `parent_item_id` on `agenda_revision_item`; to note on the T3 branch.
  - E03 T2 (`meeting/V2`) and T3 (`meeting/V3`) are in progress, so E04's migration is `meeting/V4`.
- **Uncertain, no owner input needed:** whether a recused member's alternate stands in for that item is left to E05 (no source found); "stays but does not vote" is not offered in Phase 4 and is revisited with electronic voting in Phase 5.
