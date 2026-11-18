# E04 Conflicts of interest and recusal — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-18 unless stated. Phase research items covered here: R1 (versions: nothing new) and R6 (conflict-of-interest practice). Sources marked **[snippet]** were read only from a search result, because the site blocked automated reads (legislation.gov.uk, Azeus Convene, the FRC's code page) or the text was only in a listing. The eScribe article was read through its public Zendesk article API; the normal article URL is given.

## 1. Starting point (code and plans, 2026-11-18, `master` at `06747cd`)
- **What E03 prepared** (plan merged; T1 built, T2 in progress on another branch, T3–T9 to come):
  - **One paper-access rule** (ADR-0061, E03 design §1.9): `PaperAccess` decides who sees an item, its title and its papers, for the member's agenda, paper opens, "What changed", notifications, the preview and the `meeting.paper` purpose.
  - **The hook:** `PaperExclusions` with two methods, `excluded(user, item)` and `excludedFromPapers(user, item)`, applied to everyone, officers included. E03 ships `NoExclusions`. ADR-0061 says E04 implements them from declarations.
  - **The surface registry and the leak-suite skeleton** (E03 test plan): `PaperSurfaceRegistry` lists every read path, `PaperLeakIT` probes each one for a user outside the rule, and a meta-test fails when a `meeting` controller method is missing from the registry. "E04 adds the recused-member column and its own surfaces."
  - **The standing item kind `DECLARATIONS`** ("Declarations of interest") is in every agenda preset. E03 research §3.2 says E04 fills it.
  - **A place on the meeting page:** E03 flow P keeps "Declare an interest" per item for E04, and the builder's side sheet already tells the secretary "A member who declares a conflict will not see the papers (from the next release)".
  - **Cloning never copies declarations** (E03 design §1.7, research §3.2).
- **Built on `master`:**
  - `meeting/V1__meetings.sql` (T1). E03 T2 adds `meeting/V2__agenda.sql` and T3 adds `meeting/V3__revisions.sql`. E04's migration is therefore `meeting/V4`.
  - `forum/V1`–`V5`. E04 needs no forum migration: the quorum rule already carries `recusedCount`.
  - `MeetingAccess.relation` (OFFICER, MEMBER, GUEST, ATTENDEE, READER), the materialised `meeting_audience`, and `Meetings` as the published reader for other modules.
- **Quorum (ADR-0057, E01):**
  - `Quorum.evaluate(rule, roster, attendance)` is pure and published by `forum`.
  - `QuorumPresence.recused` already exists ("Recused from the item being decided (E04)").
  - `QuorumRule.recusedCount` defaults to `false` (the UK model articles: conflicted members do not count). Exim's charter counts them, so it is a setting (E01 research §3.3, the forum type's Quorum tab: "Members who declared a conflict on an item count for that item").
  - E05 evaluates quorum live and per item. E04 supplies the recusals.
- **Interests on memos (Phase 3, SOD-4):**
  - `memo_interest (memo_id, user_id, declared_by, reason, created_at)` holds people with a declared interest in a memo: listed by the initiator while the memo is editable, or declared by an approver who stands aside on their task (`POST /api/v1/tasks/{taskId}/recuse`, the workflow's `RECUSE` action).
  - `WorkflowSubject.interested()` keeps them out of the memo's steps. The reason stays in the table and never in the audit log ("business content").
  - The store is internal to `memo`; no published reader exists yet.
- **Search (ADR-0054):** `search_memo`, `search_note` and chunks behind `SECURITY DEFINER` functions that apply the memo read rule themselves. No meeting content is in search, and E03 adds none. E08 adds `search_decision`, "with the recusal and forum-access predicates" (overview E08).
- **Audit export (ADR-0033):** a verifiable ZIP of the tenant's chain for `audit.read` holders. Entries carry ids and structured details, never business text such as a memo interest's reason.
- **Exports:** Apache POI 6.0.0 is already in the catalog (poi-tl, the import report workbook), so an XLSX export needs no new dependency.

## 2. Versions (R1)
E04 adds no library and no service. It reuses jOOQ, the notification module, db-scheduler (through E03's `meeting-sweep`) and Apache POI 6.0.0 (already pinned for ADR-0048) for the register export. Nothing needs re-verifying for this epic. The Spring Boot 4.2 re-check stays with E05, as E03 recorded.

## 3. What supervisors and the law expect (R6)

### 3.1 Bank supervisors
- **Basel Committee, *Corporate governance principles for banks* (July 2015)** (<https://www.bis.org/publications/201507-guidelines-corporate-governance-principles-banks.pdf>):
  - ¶80: conflicts arise "between the interests of the bank or its customers and those of the bank's board members or senior managers (eg where the bank enters into a business relationship with an entity in which one of the bank's board members has a financial interest)".
  - ¶83: the board "should have a formal written conflicts-of-interest policy", including "a member's duty to promptly disclose any matter that may result, or has already resulted, in a conflict of interest" and "a member's responsibility to abstain from voting on any matter where the member may have a conflict of interest".
  - ¶27: related-party transactions "are reviewed to assess risk and are subject to appropriate restrictions".
  - ¶60: the board "should maintain appropriate records (eg meeting minutes or summaries of matters reviewed, recommendations made, decisions taken and dissenting opinions)", available to the supervisor.
- **EBA, *Guidelines on internal governance under Directive 2013/36/EU* (EBA/GL/2021/05, 2 July 2021)** (<https://www.eba.europa.eu/sites/default/files/document_library/Publications/Guidelines/2021/1016721/Final%20report%20on%20Guidelines%20on%20internal%20governance%20under%20CRD.pdf>):
  - ¶113: "Staff should have the duty to promptly disclose internally any matter that may result, or has already resulted, in a conflict of interest."
  - ¶114: the policy "should differentiate between conflicts of interest that persist and need to be managed permanently and conflicts of interest that occur unexpectedly with regard to a single event (e.g. a transaction …) and can usually be managed with a one-off measure."
  - ¶115(c): "the responsibility of the members of the management body to abstain from voting on any matter where a member has or may have a conflict of interest".
  - ¶117: "institutions should document the measures taken, including the reasoning on how those are effective to ensure objective decision-making."
  - ¶119: "All actual and potential conflicts of interest at management body level … should be adequately documented, communicated to the management body, and discussed, decided on and duly managed by the management body."
  - ¶125 (loans and transactions with members and related parties): the member "benefitting from such a material loan or other material transaction or the member who is related to the counterparty, should not be involved in the decision-making."
  - ¶59: committees "should document the agendas of committee meetings and their main results and conclusions."
  - The EBA consulted in 2025 on amending these guidelines (<https://eba.europa.eu/sites/default/files/2025-09/f356de1e-bced-474d-b7da-8032ea5d8797/2025%2009%2005%20%20Public%20hearing%20EBA%20GL%20on%20internal%20governance%20%281%29.pdf> **[snippet]**). The 2021 text is the one in force for this plan. The joint ESMA and EBA suitability guidelines are referred to from footnote 36 for conflicts that affect independence of mind.
- **Bangladesh:**
  - No Bangladesh Bank circular text on recusal at board meetings could be read. BRPD Circular No. 11 (27 Oct 2013) covers the formation and responsibilities of bank boards (<https://www.scribd.com/document/467462717/Board-oct272013brpde11> **[snippet]**).
  - A 2025 Bangladesh Bank directive requires that "any differing opinion, observation, or dissent" on agenda items be "clearly included in the meeting minutes" (The Financial Express, 5 Aug 2025, <https://thefinancialexpress.com.bd/trade/all-dissents-observations-must-be-recorded-in-board-meeting-minutes>).
  - Practice in a Bangladeshi bank: NRBC Bank's code of conduct for the chairman, directors and CEO (2024 governance compliance report) requires directors to "Disclose any actual or potential conflict of interest in advance" and "Abstain from deliberation or voting in matters where a conflict exists", and points to sections 26Ga, 27, 28 and 109 of the Bank Company Act 1991 on loans and advances (<https://www.nrbcommercialbank.com/assets/forms/investor/nrbcb-Corporate-Governance-Guidelines-Status-updated_2024.pdf>).

### 3.2 Company law and codes
- **UK Companies Act 2006:**
  - s.177: a director interested in a proposed transaction "must declare the nature and extent of that interest to the other directors", before the company enters into it. It may be declared at a meeting, by notice in writing (s.184) or by general notice (s.185) (<https://www.legislation.gov.uk/ukpga/2006/46/section/177> **[snippet]**; <https://www.fsp-law.com/directors-conflicts-of-interest-how-sections-175-and-177-of-the-companies-act-2006-work/>). s.182 is the same duty for existing transactions.
  - s.175 covers situational conflicts, which the board may authorise under the articles (same fsp-law source).
  - **The general notice (s.185)** is a standing declaration ("I am a director of X"). It is the legal root of a standing register; declarations against a specific transaction remain the core duty.
- **UK model articles for private companies, art. 14:** a conflicted director "is not to be counted as participating in the decision-making process for quorum or voting purposes" (<https://www.legislation.gov.uk/uksi/2008/3229/schedule/1>, cited in E01 research §3.3).
- **UK Corporate Governance Code 2024, Provision 7:** "The board should take action to identify and manage conflicts of interest, including those resulting from significant shareholdings, and ensure that the influence of third parties does not compromise or override independent judgement" (<https://euronext.com/sites/default/files/2024-05/irish_code_v_uk_code_blacklined_copy.pdf> **[snippet]**; FRC: <https://www.frc.org.uk/docs/6709/html/>).
- **Charity Commission, *Managing conflicts of interest in a charity*:** "identify and declare conflicts at the start of meetings - have this as a standard agenda item"; "Do this early, before discussions or decisions happen"; as a minimum the conflicted trustee "leaves relevant discussions", "does not take part in the decision or vote" and "is not counted in the quorum"; keep "a register of interests" (<https://www.gov.uk/guidance/managing-conflicts-of-interest-in-a-charity>).
- **ORIC (Australia):** a conflicted director should "Ask not to be given any board or internal papers that discuss the matter" and "declare your interest and leave the room"; the minutes record "why and when you left and when you rejoined" (<https://www.oric.gov.au/node/8287>, <https://www.oric.gov.au/node/8202>; cited in E03 research §3.6).
- **American Bar Association, *10 Tips for Handling Conflicts of Interest* (Francis, Chryssikos, John and Romain, 15 May 2025)** (<https://businesslawtoday.org/2025/05/10-tips-for-handling-conflicts-of-interest-the-year-in-governance/>):
  - "recuse that director, immediately";
  - "Exclude the director from deliberations (the meeting), decisions (the voting), and information … although you can provide a redacted version";
  - after the director leaves the room, restrict what reaches them by "monitoring what is placed on the director's portal" or revising access controls;
  - "place a brief disclosure statement at the top of every meeting agenda";
  - "The minutes should reflect these efforts in order to record your compliance."
- **A director's right to information is the limit:** Davies Ward Phillips & Vineberg note that Canadian statutes "generally grant directors a blanket right to inspect board and committee minutes", yet "the interested director's access to information may compromise the board's ability to freely investigate", so "Managing access to information can thus become an essential governance tool" (<https://www.dwpv.com/en/Insights/Publications/2024/Governance-Insights-Protecting-Information>). Restrictions should be tied to the matter, not general.
- **Robert's Rules:** a member should not vote on a question in which they have a "direct personal or pecuniary interest" not shared by others, but cannot be compelled to abstain (<https://www.cityethics.org/node/817>, <https://issuu.com/parliamentarians/docs/nap_np82-4k-www/s/13094184> **[snippet]**). Bank rules (Basel, EBA, art. 14) are stricter, so this is not the model for banks.

### 3.3 What must be minuted
- **CGI (then ICSA), *Minute taking* guidance note (April 2017)** (<https://cgi.org.uk/media/mpjeexxj/minute-taking.pdf>):
  - §5.3.6: "generally, the director should declare any interest before the matter is discussed". It "will, more often than not, be appropriate" that:
    - "the director should be identified in the minutes";
    - "the nature of the conflict, the decision as to whether or not the director should attend the section of the meeting in which he/she is conflicted and any other action taken by the board to address the conflict should be recorded";
    - "whether or not the director concerned leaves the room, the minutes should make it clear that he/she took no part in that section of the meeting and, where applicable, could confirm that the meeting remained quorate."
  - §5.2.1: quorum need only be mentioned where there might be doubt, "For example, if one or more directors have to absent themselves owing to a conflict of interest". It is "the responsibility of the company secretary to be aware whether the meeting is quorate at all times".
  - §5.2.2: companies "tend to record conflicts of interest only when one is specifically raised … in which case it is noted under the relevant agenda item". Boilerplate at every meeting "might … look to regulators … that the board was being complacent". A register "maintained by the company secretary" is helpful even where not required.
  - §6.4, access to minutes by the conflicted director: 38 respondents said do not redact, 22 said redact, 15 "it depends". "Another took the view that the director is entitled to know the decision, but not any detail of the discussion. There was a strong bias towards redaction from larger companies, particularly those in the financial services sector." Multiple redacted sets "would create an excessive administrative burden" when done by hand.
- **CGI factsheet, *What are minutes and why do we need them*:** "Conflicts of interest, which must be declared at the start of the meeting, and actions taken to manage conflicts should be clearly noted. The maintenance of a conflict of interests register by the company secretary ensures any conflicts are properly documented" (<https://cgi.org.uk/media/io3naipz/cgiuki-factsheet-what-are-minutes-and-why-do-we-need-them.pdf>).
- **BoardEffect:** the minutes should state whether the board voted on the matter and whether the conflicted director voted or abstained (<https://www.boardeffect.com/blog/conflict-of-interest-policy-for-nonprofit-boards/> **[snippet]**).

**Taken from §3:**
- **Disclosure is per matter and before discussion** in every source (Basel ¶83, EBA ¶113, s.177, CGI §5.3.6, Charity Commission). A per-item declaration is the minimum every source asks for. A standing register (EBA ¶114 "persist", s.185 general notice, CGI, the Charity Commission, BoardPro) is good practice on top, not instead. Phase 4 Q6 (per item now, a register when a customer has the data) is consistent with all of them.
- **The effect banks need is leave-the-room, not only abstain.** Basel and EBA ¶115(c) ask for abstention from voting; EBA ¶125 says the related member "should not be involved in the decision-making"; ORIC, the ABA and the Charity Commission add leaving the discussion and not receiving the papers. A bank product should default to the stricter reading: no papers, no discussion, no decision.
- **Somebody decides how a declared interest is managed** (EBA ¶119 "decided on … by the management body", CGI "the decision as to whether or not the director should attend", ORIC "the board decides the extent"). In practice this is the chair. The product needs a ruling step for interests the member thinks are immaterial.
- **The minutes record:** who declared, the nature, the decision on attendance, that the member took no part, and that the item stayed quorate (CGI §5.3.6). E06's template tags need exactly these fields from E04.
- **Quorum:** the UK model articles and the Charity Commission leave conflicted members out of the item's quorum; Exim counts them. ADR-0057's `recusedCount` setting already covers both.
- **The conflicted director's minutes:** practice is split, with financial-services boards leaning to redaction, and a middle view that the director may know the decision but not the discussion (CGI §6.4). Doing it by hand is the burden CGI mentions; software removes it.

## 4. How board portals handle conflicts (R6)
| Product | Declaration | Effect on access | Record | Source |
|---|---|---|---|---|
| eScribe | "Conflicts may be added for any voting Participant and can be associated with any agenda item": a reason and optional attachments; edited or removed later; optional "Conflicts of Interest" category in the agenda listing every declaration | None documented; "There are no notifications sent to the Participant who submitted the conflict or to the Administrator" | A "Conflict" label on the meeting's navigation | <https://help.escribemeetings.com/hc/en-us/articles/43666631650957-Participants-Declaring-a-Conflict-within-a-Meeting> |
| OnBoard | Not in the product docs found | "Every item in your board book can be quarantined for those pesky conflicts of interest"; "a board administrator can simply remove access for those individuals (redact), omitting any materials or preventing their access to votes, approvals, and actions associated with any conflict"; per-section **Exclude** role (E03 research §3.6) | — | <https://www.onboardmeetings.com/board-portal/features>, <https://www.onboardmeetings.com/board-portal-glossary/board-meeting-conflict-of-interest/> |
| Diligent Boards | Not documented for the book; Diligent markets the portal as "the best way to document the actions that the board has taken to disclose and resolve conflicts" | Per document "Show document to selected users" or "Hide document from selected users", set by an administrator; reviewers say permissions "need to be redone" after a new version (E03 research §3.6) | — | <https://help.highbond.com/helpdocs/boards/en-us/Content/boards-web-admin/books/managing-document-visibility-bwa.htm>, <https://www.diligent.com/insights/corporate-secretary/board-directors-conflict-interest-policy> |
| Nasdaq Boardvantage | Not documented | Folder and file permissions, "limiting information to the intended users"; G2 reviewers call access-rights management limited | Permission reports | <https://www.nasdaq.com/solutions/boardvantage/features> **[snippet]**, <https://g2.com/products/nasdaq-boardvantage/reviews?page=5> **[snippet]** |
| Azeus Convene | Not documented | "the administrator can remove and restrict the concerned board member from all materials related to the conflict" | — | <https://www.azeusconvene.com/board-portal-glossary/what-is-a-conflict-of-interest> **[snippet]** |
| Admincontrol | — | Folder restriction "for conflict of interest"; "The restricted users will only see the agenda item, but without attachments" (E03 research §3.6) | — | <https://admincontrol.com/en/help/board-management-solution/folder-access-permissions> |
| BoardPro | Interest register per director, kept by the director or an administrator; past interests kept | Cannot restrict specific papers (E03 research §3.6) | "When the agenda is published, BoardPro pulls a snapshot of your interest register into the board pack" so conflicts "can be acknowledged before decisions" | <https://boardpro.com/features/interest-register> |
| iBabs, BoardEffect | Nothing documented on conflicts; BoardEffect publishes only policy articles | Ordinary permissions | — | <https://info.ibabs.com/try-ibabs>, <https://www.boardeffect.com/blog/avoiding-conflicts-of-interest-on-a-board-of-directors/> |

**What the products share:** a declaration, where it exists, is a **record**, and access is a **separate administrator action**. eScribe records conflicts per item and changes nothing else. OnBoard, Diligent, Convene, Admincontrol and Boardvantage restrict access by hand, per document or section. BoardPro has the register and the snapshot but cannot restrict a paper. **No product documented that a declaration itself withholds the papers, keeps the member out of notifications, or feeds quorum and the minutes.**

**What users and advisers criticise:**
| Problem | Source | What we do |
|---|---|---|
| Permissions redone by hand when a paper is replaced (Diligent) | Capterra **[snippet]**, E03 research §3.9 | Recusal sits on the item, not the document, so new papers and replacements are covered at once |
| Conflicts handled by an administrator editing access, separately from the declaration (OnBoard, Diligent, Convene) | the vendor pages above | The declaration is the access change: declare, and the papers are withheld at once, fail-closed |
| A declaration with no effect and no notification (eScribe) | eScribe article | The secretary and chair are told; the declaration withholds papers, notifications and the decision |
| Multiple redacted sets of minutes are "an excessive administrative burden" | CGI §6.4 | The recused member's minutes are produced by the same rule, not by hand (E06) |
| Access management "limited" (Boardvantage) | G2 **[snippet]** | One rule shared by every surface, with a leak test for each |
| Boilerplate declarations at every meeting look complacent | CGI §5.2.2 | A per-meeting confirmation records an actual review against the agenda, not a sentence in the minutes |

## 5. Options

### 5.1 Per-item declarations or a standing register (Q6)
1. **Per item only** (Phase 4 Q6 answer): a member declares against an agenda item. Recusal applies to that item.
2. **Per item plus a standing register** of directorships and related parties that flags items automatically.
3. **Register only**, with a manual link to items.

- Option 2 needs reliable counterparty data on memos (borrower, group, related parties). No customer has given it, and false matches would hide papers from people entitled to them, or miss real conflicts.
- Option 3 does not satisfy s.177, EBA ¶113 or CGI §5.3.6, which are about the specific matter.
- **Recommendation: option 1**, as approved, with three additions that the research supports and that cost little:
  - **carry-over:** an interest already declared on the memo (SOD-4) and a recusal on an earlier placement of the same submission (deferred, then placed again) become declarations on the new item, so a director never has to re-declare the same matter;
  - **a per-meeting confirmation** ("I have reviewed the agenda and declared my interests"), so the register shows who has reviewed, not just who has declared (ABA's disclosure statement, the Charity Commission's standard item, without CGI's boilerplate problem);
  - **the data model leaves room for the register:** a declaration has a `source`; a later `REGISTER` source can add flagged declarations without changing the rule.

### 5.2 What a declaration does
1. **Record only** (eScribe): access is changed by hand.
2. **Abstain only** (Basel ¶83, EBA ¶115(c)): no vote; the member keeps the papers and the discussion.
3. **Leave the room** (ORIC, ABA, Charity Commission, EBA ¶125): no papers, no discussion, no decision; title kept so the member knows when to leave.

- **Recommendation: option 3 as the effect of a recusal**, and a **ruling** for a member who believes the interest does not prevent them from taking part:
  - The member chooses "I will leave the room for this item" (the default and the recommended choice) or "I ask the chair to decide whether I may take part".
  - Until the chair rules, the interest is treated as a recusal (**fail-closed**). A bank cannot let papers reach a conflicted member while a ruling is pending.
  - The chair (the vice-chair when the chair is the declarant) rules "may take part" or "must leave the room". The secretary can record the chair's ruling given by email or in person, recorded as such (the E03 pattern for the chair's agreement).
  - A member allowed to take part keeps full access. The declaration and the ruling are still minuted (CGI §5.3.6, EBA ¶117).
- A middle option ("stays for the discussion but does not vote") is not offered in Phase 4. It is in Basel's and Exim's wording, but it adds a third state for every surface, and no bank source in this research prefers it over leaving the room. Decisions are recorded by the secretary in Phase 4 (overview A3), so "did not vote" can be minuted by hand. Revisit with electronic voting in Phase 5.

### 5.3 Where the rule is enforced
1. **In each screen or endpoint**, as products do with permissions.
2. **In `PaperAccess`'s SQL conditions** through E03's `PaperExclusions` hook, plus a small published `Recusals` API for the modules that need the facts (quorum, decisions, minutes, search rows).

- **Recommendation: option 2.** One anti-join on declarations inside the conditions every read path already uses. Officers are subject to it too (E03 threat E2). The surfaces outside `PaperAccess` (quorum, decisions, minutes, the decision register) read `Recusals`. Each surface is listed in the registry and has a leak probe.

### 5.4 The conflicted member's view of the minutes (input for E06)
1. Full minutes (CGI's majority of all respondents).
2. Minutes without the item at all (the overview's wording).
3. **The item's heading, their own declaration, and the decision, without the discussion or papers** (CGI §6.4's middle view; ABA "a redacted version"; financial-services bias towards redaction).

- **Recommendation: option 3**, with nothing but "Item 6 — restricted" when the item hides its title. A director is collectively responsible for the board's decisions and learns the outcome anyway; the discussion is what would compromise "the board's ability to freely investigate" (DWPV). This **changes the overview** ("a version without the items they were recused from"); recorded in ADR-0062 and reported to the owner.

### 5.5 Declarations and the memo
- When a member declares on an item that came from a memo, should the memo learn it?
- **Recommendation: yes, as a memo interest (SOD-4).** If the committee returns the memo or later steps follow the forum step, the declarant must not then approve it. The memo interest carries a fixed reason ("Declared an interest at BRC 3/2026, item 5") and never the nature text, which stays with the meeting. A withdrawn meeting declaration does not remove the memo interest; that stays the memo's own decision (Phase 3).
- **Recusal does not take away memo access granted elsewhere.** A conflicted member who already reads the memo through the memo rule (an approver, a copy-to) keeps reading it. Recusal withholds what the committee gives: papers, discussion, the decision and notifications. The secretary sees this in the register ("also reads the memo through the memo's own access"), so it can be handled the ordinary way (ADR-0050). Revoking memo access from a meeting would break the memo's own record and its approvers.

## 6. Usability for older, non-technical directors and secretaries
- **One question per item, in words:** "Do you have an interest in this item?" with a short explanation and examples taken from eScribe's list (a family member's company, a contract, a loan). No codes or categories.
- **The safe choice is the default and is said plainly:** "I will leave the room for this item (recommended). You will not receive its papers."
- **Nothing to remember:** declarations made on the memo or at an earlier meeting for the same paper are already there, marked "carried over".
- **A single confirmation per meeting** ("I have no other interests to declare") ends the reminders. New items in a later revision ask again, only about those items.
- **The member sees their own state on the agenda line:** "You declared an interest — you will be asked to leave for this item", with the time, so they know when to step out (Admincontrol keeps the title for this reason).
- **Large targets and no hidden gestures** on the declaration screens (WCAG 2.2 AA; 44 px on the members' pages, E03 research §3.8). W3C notes older users' "reduced short-term memory" (<https://www.w3.org/WAI/older-users/>), which the confirmation checklist addresses.
- **The secretary records what was said in the room** in one dialog ("Declared at the meeting", "By email on …"), because many directors will tell the secretary rather than use the app.

## 7. Conclusions for the design
1. **Declarations per agenda item** (or sub-item; a declaration on an item covers its sub-items), by the member, by the secretary or chair on their behalf, or carried over from the memo or an earlier placement of the same submission. Nature as free text; the member's intent (leave the room, or ask the chair).
2. **States:** RECUSED, AWAITING_RULING (treated as recused), TAKING_PART (the chair allowed it), WITHDRAWN. History in an append-only event table; the nature never changes.
3. **The recusal rule:** a member is recused from an item when they hold a RECUSED or AWAITING_RULING declaration on it or on its parent. It plugs into `PaperExclusions` (both methods), so it applies wherever `PaperAccess` does, to officers too. Titles stay visible unless the item hides them.
4. **Effects:** no papers or item details, no discussion requests, no notifications about the item; excluded from the item's quorum per `recusedCount` (E05) and from its decision (E06); minutes show the heading, the declaration and the decision only (E06).
5. **The prompt:** at publication and each revision with new items; one confirmation per meeting and revision; one reminder before the meeting.
6. **The register:** per meeting for the secretary and chair (with confirmations and a quorum warning computed from the roster), and across forums for oversight, with an XLSX export.
7. **The memo:** carried in (memo interests become declarations) and carried out (a declaration becomes a memo interest, SOD-4), without the nature text.
8. **The leak suite** with a recused-member column on every surface, canary strings, and placeholders that later epics must fill.
