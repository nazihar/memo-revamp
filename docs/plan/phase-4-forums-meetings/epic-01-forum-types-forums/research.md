# E01 Forum types and forums — research

> Step 1 of `AGENTS.md §5`. Accessed 2026-11-18 unless stated. Phase research items covered here: R1 (versions), R4 (quorum rules), and the parts of R2 and R6 that concern committees and membership. Sources marked **[snippet]** were read only from a search result, because the site blocked automated reads.

## 1. Starting point (code, 2026-11-18)
- **Modules:**
  - `com.memo.forum` exists as an empty Spring Modulith module (`backend/src/main/java/com/memo/forum/package-info.java`). It has no tables, migrations or web screens.
  - `DemoTenant` already lists "forums (4)" among what it will seed.
- **Workflow:**
  - The definition schema accepts `{"type": "escalate-to-forum", "forum": "<key>"}` with the key pattern `^[a-z][a-z0-9-]{0,99}$`. Nothing checks the key.
  - No demo or volume definition uses the step (`grep escalate-to-forum`), so validating keys at publish breaks nothing that exists.
  - The visual builder shows the step with a free-text forum key (`web/src/features/workflows/builder/StepDetails.tsx`).
- **Maker-checker:**
  - `common.review` (`ReviewState`, `ReviewRules`) holds the shared states and refusals (ADR-0043).
  - Memo types use it with a version table, guarded moves, step-up on approve and a "Changes" view. Forum types follow the same pattern.
- **Authorisation:**
  - Permissions are a fixed catalogue in code, granted to tenant-defined roles, optionally scoped to a unit subtree (ADR-0027).
  - Relationship-based access (who participates in a memo) lives in the owning module as a SQL predicate (`MemoReadable`, ADR-0050), not in the policy service. Forum membership is that kind of relationship.
- **Org:**
  - `OrgDirectory.holders(position, date)` resolves who holds a position on a date, so a member can be a position ("the Chief Risk Officer") that follows its holder.
  - Assignments are primary only until Phase 1b.
- **Terminology:** overridable terms are `unit`, `position` and `memo` (ADR-0032). Tenants call forums "committees" or "boards", so a `forum` term is needed, defaulting to "committee" in English.
- **Notifications:** `Notifications.notify(...)` in the producer's transaction; kinds and categories in code; no parameters in subjects (ADR-0030).
- **Demo users with sign-in:** `admin@`, `reviewer@`, `orgadmin@`, `branchadmin@`, `auditor@`, `user@`, `head.credit@`, `head.finance@` and `director@demo.example`, plus 44 named staff without sign-in. There is no company secretary, and later epics need one.

## 2. Versions (R1)
E01 adds **no new dependency**. The quorum rule is plain Kotlin (§3.3), the forms use the existing web components, and presets are code. Spring Boot stays on 4.1.1; 4.2's status is checked again in the E03 research, where dnd-kit is added.

## 3. Industry practice

### 3.1 How board portals model committees and membership
| Product | What it models | Source |
|---|---|---|
| Diligent Boards | Committee roles Administrator, Coordinator (assigns users and edits their roles, need not be a board member), Director, Executive, Reader. Application roles User manager (the only one that creates or deletes committees) and Settings manager. No chair, secretary, voting flag or terms | <https://help.diligentoneplatform.com/helpdocs/d1p/en-us/Content/boards/administrators/managing_boards/roles-and-permissions.htm> |
| BoardEffect | A workroom per board or committee with a members tab; workroom administrator vs system administrator. "Chair" is a label that "does not change the chairperson's permissions" | <https://help.highbond.com/helpdocs/highbond/de/Content/boardeffect/admin/workroom/managing_workroom_boardeffect.htm>, <https://help.highbond.com/helpdocs/highbond/es/Content/boardeffect/admin/directory/designating_a_chairperson.htm> |
| OnBoard | "Roles & Terms": current and past roles per board and committee, term start and end, number of terms served, and "recommended actions" for terms ending and members on their last term | <https://www.onboardmeetings.com/blog/may-2021-product-release-preview> |
| BoardPro | Roles Chair, Admin, Board Secretary, Board Member, Senior Executive, Guest. **Voting comes from a separate "Board Member" tick, not from the role.** Tenure with optional dates. Reminders 6 weeks and 1 day before the end, timings editable. Removal after the end is manual | <https://help.boardpro.io/articles/110421-roles-and-permissions-overview>, <https://help.boardpro.io/en/articles/11422228-manage-board-tenure-with-ease-new-ui> |
| Boardable | Organisation types Admin, Member, Observer (view only), Guest (one meeting); meeting roles owner, collaborator, attendee | <https://docs.boardable.com/knowledge/understand-user-types-guests-and-meeting-roles> |
| eScribe | Expiring terms flagged 30 days ahead by default **[snippet]** | <https://help.escribemeetings.com/hc/en-us/articles/41361240063117-Board-Manager-Term-Expiry-Settings> |
| Admincontrol | Admin-built roles (name, level, system role, folder permissions); no committee semantics documented | <https://admincontrol.com/en/help/board-management-solution/user-roles> |
| Azeus Convene | Permissions "by user roles and/or meeting roles"; a secretary "Minute Taker" | <https://www.azeusconvene.com/features> |
| netFORUM (associations) | "Copy Committee" copies the set-up and, optionally, the positions | <https://documentation.mip.com/netforum-enterprise/2017.1/Content/Committee/Management/Copying%20a%20Committee.htm> |

Nasdaq Boardvantage and iBabs publish no public help on committee roles.

**What users say:**
- OnBoard administrators call permissions "time-consuming to set up… by committee". They object to setting them once in the system and again per meeting, and they ask for ad-hoc guests (<https://www.capterra.com/p/179015/OnBoard-Board-Portal/reviews/>).
- BoardEffect reviewers report "limited administrator permission levels" and confusing workrooms for people in several groups (<https://www.capterra.com/p/151082/BoardEffect/reviews>).
- Diligent reviewers praise administration but criticise navigation (<https://www.g2.com/products/diligent-boards/reviews>).

**What charters need that portals mostly lack** (Barclays Board Risk Committee terms of reference, December 2024, §3, <https://home.barclays/content/dam/home-barclays/documents/who-we-are/our-governance/BPLC-and-BBPLC-Board-Risk-Committee-Terms-of-Reference-December-2024.pdf>):
- terms of up to three years, renewable twice, with anything beyond nine years at the Nominations Committee's discretion;
- members who observe only for some matters;
- a chair without a casting vote;
- a deputy chair, or a chair elected by the members present when both are absent.

The FOMC's alternates attend for an absent member with "the same status as the member" (<https://www.federalreserve.gov/monetarypolicy/files/FOMC_RulesOrganization.pdf>).

**Supervisory expectations** (Basel Committee, CGO10, <https://www.bis.org/committees/bcbs/basel-consolidated-guidelines/module/cgo/10>):
- every committee has a charter with its "mandate, scope and working procedures", including "any tenure limits" (10.57);
- committees "maintain appropriate records of their deliberations and decisions" (10.59);
- the risk committee has an independent chair and "a majority of members who are independent" (10.64);
- the audit committee is "entirely of independent or non-executive" members (10.61).

**Taken from this:**
- **Governance role, voting and access are three different things.** A role (chair, secretary, member, observer) is what the charter says. Voting is a separate flag that defaults from the role. Access to papers follows membership automatically, so administrators never set permissions twice.
- **Member attributes** such as independent, non-executive or management must be recorded, because composition rules and quorum read them.
- **Terms of office** with dates and term numbers, reminders before the end (6 weeks and 1 day, editable) to the secretary, and nothing removed automatically. A member whose term has run out is flagged, not deleted.
- **Templates and cloning:** forum types as reusable policy templates (no portal has them; banks have many similar committees), presets as editable examples, and "clone this committee".
- **History:** current and past roles kept and shown, never overwritten.

### 3.2 Who sets committees up
- In banks the board appoints committee members, often on the Nominations Committee's recommendation. The company secretariat runs each committee:
  - Barclays: terms of reference §3.2–3.4;
  - HSBC: the secretary is "nominated by the Group Company Secretary" (<https://www.hsbc.com/-/files/hsbc/investors/results-and-announcements/stock-exchange-announcements/2026/september/sea-280926-e-terms-of-reference-of-group-risk-committee.pdf>);
  - Exim Bank Malaysia: the "Head of Corporate Secretarial Department" (<https://www.exim.com.my/documents/about-us/corporate-governance/TOR_of_BCC_2026.pdf>).
- The approval of a membership change therefore happens **outside** the tool, by a board resolution. The tool records it.
- No portal found has a maker-checker step on membership. Diligent separates powers instead: a User manager creates committees, and a Coordinator manages their members. BoardPro needs the chair's approval only for administrator-role changes.

**Taken from this:**
- A central governance administrator creates forum types and forums. The forum's secretary manages its members.
- Each membership change can cite its **authority** (for example "Board resolution 12 October 2026").
- Membership changes are audited and announced to the chair and secretaries. There is no maker-checker on membership.
- Forum *types* hold policy (quorum, outcomes), so they go through maker-checker like every other sensitive configuration (AGENTS §3.5).

### 3.3 Quorum rules (R4)
**Defaults and law:**
- **UK model articles for private companies:** the quorum is "never less than two" and two unless fixed otherwise (art. 11). A conflicted director "is not to be counted… for quorum or voting purposes" (art. 14). Where a director is located does not matter, so remote attendance counts (art. 10). Sources: <https://www.companylawclub.co.uk/model-articles>, <https://www.legislation.gov.uk/uksi/2008/3229/schedule/1>.
- **Robert's Rules:**
  - The default quorum is a majority of the members.
  - Quorum counts those present, not those voting.
  - Presence is assumed until someone notes that quorum is lost.
  - No substantive business is done without a quorum.
  - Sources: <https://robertsrules.com/frequently-asked-questions/>, <https://canons.sog.unc.edu/quorum-calculations-the-impact-of-vacancies-and-members-who-dont-vote/>.
- **Vacancies in public bodies (North Carolina):** city councils exclude vacant seats, while county boards are "not affected by vacancies", so the count can be seats or members in office. Recused members still count there unless a local rule says otherwise (same UNC source).
- **The Charity Commission's board:**
  - The quorum steps with the number of serving members: 3 of 4–5, 4 of 6–7, 5 of 8–9.
  - It needs at least one legally qualified member, and the chief executive never counts.
  - The meeting adjourns when quorum is lost.
  - Remote attendance counts with the chair's approval.
  - Source: <https://www.gov.uk/government/publications/charity-commission-governance-framework/appendix-4-standing-orders-for-board-and-committee-meetings>.

**Published bank charters:**
| Charter | Quorum clause |
|---|---|
| HSBC Group Risk Committee (September 2026) | "two members, including the Chair or their delegate from among the members"; all members are independent non-executive directors |
| Barclays Board Risk Committee (December 2024) | "Any two independent non-executive Directors may form a quorum" |
| Exim Bank Malaysia Board Credit Committee (2026) | "two-thirds (2/3) of the members inclusive of the Chairman"; an interested director **is counted** for quorum but abstains; audio or video attendance counts with the chair's approval |
| JPMorgan Chase by-laws (committees) | one-third of the members, "or the next highest integer" **[snippet]** (<https://www.sec.gov/Archives/edgar/data/0000019617/000001961718000010/exh31-jpmcbyxlawsjan2018.htm>) |
| Catalyst Corp ALCO | a majority of members, provided at least two board members **and** two management members attend **[snippet]** (<https://catalystcorp.org/about-us/governance/committees/committee-charters>) |

**Exim and the UK model articles disagree on conflicted members**, so whether a recused member counts must be a setting.

**Building blocks that cover every rule above.** A quorum is a list of conditions that must all hold:
1. **Who counts:** voting members, or all members, in office on the day, or a fixed number of seats. Roles that never count (observers, standing invitees) are excluded.
2. **Threshold:** a majority (more than half), a fraction (one-third, two-thirds) rounded up, or a fixed number.
3. **Floor:** never fewer than N, with 2 as the default.
4. **Required presence:** one of a set of roles, for example "the chair or a vice-chair".
5. **Composition:** at least k of those present carry a member attribute, or a majority of those present do. Examples: independent, two board members and two management members, one legally qualified.
6. **Settings:**
   - whether alternates count in place of the member they replace;
   - whether remote attendance counts;
   - whether members recused from an item count for that item's quorum.

The only "or" any source needs is a set of roles ("the chair or their delegate"). Everything else is a conjunction of conditions from a small vocabulary.

**A structured rule builder is enough and is friendlier than an expression language.** CEL (ADR-0039) would be a second rule language for administrators who are company secretaries, not workflow designers. Because charters state quorum in one sentence, the builder echoes the rule back as a sentence, with a worked example and a tester for sample attendance. Recorded in ADR-0057.

### 3.4 Decision outcomes (for E02 and E06; stored on the forum type now)
- **eScribe:** Carried, Tabled, Defeated **[snippet]**.
- **Hyland OnBase Agenda:** fixed result statuses (Pass, Pending, Fail) with labels administrators can edit (<https://docs.hyland.com/r/OnBase/Agenda/English/Foundation-22.1/Agenda/Configuration/Configuring-Attendance-Motion-and-Vote-Types/Configuring-Motion-Result-Types>).
- **Boardable:** Carried, Failed, Tabled, Withdrawn **[snippet]**.
- **UK councils:** "approved", and "RESOLVED, that the report be noted" **[snippet]**.
- **Credit committees:** outcomes such as "approved with conditions", "declined", "returned for more information" and "referred to the board" are practitioner convention. No published source was found.

**Taken from this:** a fixed set of outcome **kinds** that the system understands, each with a label the tenant can edit, and a per-type choice of which kinds the forum uses. The kinds are approved, approved with conditions, rejected, deferred, returned for more information, referred to another forum, noted and withdrawn. What each kind does to a waiting memo is fixed in code (E02, ADR-ESCALATE), so a label can never change meaning.

## 4. Conclusions for the design
1. **Forum type:**
   - a versioned policy template, published through maker-checker with step-up;
   - holds the member attributes, the quorum rule, the outcomes, the term policy, and the default classification and retention;
   - later epics add their own fields (submissions E02, agenda template E03, invitations E05, minutes and resolution numbering E06), each with an expand-only migration;
   - presets (Board, Board committee, Management committee) are offered as editable starting points.
2. **Forum:**
   - one concrete committee of one type, with a workflow **key** (lower case, as the workflow schema requires) and a short **code** (upper case, for meeting and resolution numbers);
   - optionally a parent forum (a board committee under the board);
   - can be dissolved, never deleted, and can be cloned with or without members.
3. **Membership:**
   - one row per term of office: a person or a position, a role, voting (defaulting from the role), member attributes, an alternate, dates, the term number and an authority;
   - a role change or a renewal ends the row and starts a new one, so history is kept;
   - one chair at a time, and no overlapping rows for the same person.
4. **Access:** forum membership is a relationship that the forum module answers through `ForumDirectory` and SQL predicates, like `MemoReadable`. New catalogue permissions are `forum.types.manage`, `forum.types.approve`, `forums.manage` and `forum.read`. A forum's secretaries manage its members but cannot add or remove secretaries, change their own row, or make anyone chair.
5. **Expired terms:** a member past `term_ends_on` without renewal is out of office (not counted, not voting) and shown as "term ended, renew or end". Reminders go to the secretaries 42 days and 1 day before, editable on the type.
6. **Workflow link:** a definition naming an unknown or dissolved forum is refused at publish (`UNKNOWN_FORUM`). The builder offers a forum picker.
7. **UI wording:** "committee" by default, through a new terminology term `forum` that tenants can rename.
8. **Demo:** forum types Board, Board committee and Management committee; forums for the Board, Board Risk Committee, Board Audit Committee, Management Credit Committee, ALCO and the Executive Committee; and a company secretary user, `secretary@demo.example`.
