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
- **T1 built (#155):** forum types with reviewed versions, presets, the quorum rule and evaluator, the preview, and the two permissions.
  - `QuorumTest` covers the clauses of the cited charters; `QuorumProperties` covers the arithmetic and invariants.
  - `ForumTypesIT`, the matrix, audit coverage, modularity, module metadata and the contract are green locally.
- **Deviations from the plan, recorded here:**
  - The workflow's `EscalationTargets` interface and the escalation-targets endpoint shipped with T1. They were small and already in the tree, and with no implementation every key stays accepted. T3 adds the forum's implementation.
  - T2 and T3 ship as one PR. They share the notifier and the notification kinds, and each local build-test-commit cycle takes about 30 minutes.
  - detekt's limits split two classes. The type store became `ForumTypeStore` (identity) and `ForumTypeVersionStore` (versions), and the controller became `ForumTypesController`, `ForumTypeVersionsController` and `ForumTypeResponses`, as memo types do.
  - The two seat problems (missing with fixed seats, present without) share the code `RANGE`.
  - Membership and term emails say "committee" in plain English until T4 adds the renameable `forum` term.
  - The demo credit workflow's escalation to the Management Credit Committee moves to E02, where the step starts to wait. As a pass-through step in E01 it would show nothing and would change a published definition on dev twice.
- **T4 built (web, committee types, flow J):** `/admin/committee-types` (list, "New committee type" from Blank or a preset) and `/admin/committee-types/$typeId` (version selector, Published line, the review toolbar with step-up on approve and the `SAME_PERSON` message, tabs Policy, Quorum, Outcomes, Terms and Changes). Code in `web/src/features/forum-types/`; texts under the new catalogue part `forumAdmin` (`forumTypes.*`), with Arabic.
  - The Quorum tab writes the rule back as one sentence beside the builder, built from ICU messages in the browser. The worked example and the "Try it" tester come from `POST /forums/quorum-preview` on a sample roster built from the tester's numbers, so no quorum arithmetic runs in the browser. The preview's field problems also mark the builder's controls.
  - The Changes tab lists "before → after" lines against the published version; a quorum change shows the old and new sentences.
  - The terminology term `forum` ("committee", Arabic "لجنة", feminine) is renameable on the Language and terms page. The menu, breadcrumbs and administration overview now pass the organisation's terms to their labels.
- **Deviations in T4:**
  - Small backend change: renaming the term needed the server to accept it. `TermKey.FORUM`, migration `tenancy/V5__forum_term.sql` (the check on `tenancy_term.term` widens; expand only), the mail bundle's default `term.forum.*`, the regenerated jOOQ table and contract, and a `TerminologyIT` case. Mail templates still name the committee by its name; none uses `{forum}` yet.
  - Member attributes (J3) are a list: each row has a tick, an editable label and its key, and the three common attributes (Independent, Non-executive, Management) are offered as unticked rows until used. "Add attribute" asks for a label and a key, suggested from the label.
  - Required roles (J4) are chips with a remove button and an "Add a role" list per requirement. A new requirement starts with the first role not yet used.
  - Reminders (J6) are up to three day fields with "Add a reminder", not a fixed two.
  - Outcome effects (J5's help text) are a disclosure under the table, following E02's mapping: Approved, Approved with conditions and Noted continue the route, Rejected ends it, Returned goes back to the initiator, and the others keep the memo waiting.
  - Arabic needs the same message arguments as English, so a few Arabic headings use the singular term ("أنواع {Forum}").
- **T5 built (web, committees, flows K and L):** `/committees` (K1), `/committees/$forumId` (K2 to K5), `/me/committees` with a home-page card (L1), and the workflow builder's committee picker (L2). Code in `web/src/features/forums/`; texts under the new catalogue part `forums` (prefix `forums`), loaded on `/committees` and `/me/committees`, with Arabic.
  - K1 lists name, code, type, chair, members today with a badge for terms ending or ended, and status. "New committee" (`forums.manage`) asks for the type, name, short name, code, a workflow key suggested from the name, reports to and classification.
  - K2 shows type, classification, reporting line and secretaries, the tabs Members, History and Policy, and the page menu Clone, Edit and Dissolve. A date field drives `GET /forums/{id}/members?on=` and the quorum line ("Quorum today: 2 of 3 voting members", or "Quorum on <date>: …") with the rule in words from T4's sentence builder.
  - K3 is a side sheet on the inline-end side, full screen on a phone: a person or a position (the existing people picker), role, vote from the role, the type's attributes, alternate, from and term end (filled from the type's usual term), authority. A secretary is not offered Chair or Secretary and is told "Only an administrator can appoint a chair or a secretary."; rows a secretary may not change (chairs, secretaries, their own) have no menu.
  - K4: the row menu has Renew term, Change role, Edit and End membership, and Withdraw for a row that has not started. Renew says "Term 3 starts on …" and warns past the type's most consecutive terms. End asks for the last day and a reason; Other needs words. Refusals (`ONE_CHAIR`, `ALREADY_MEMBER`, `SECRETARY_LIMIT` and the others) are shown in words inside the form.
  - History lists every row newest first, with its state and end reason (for example "Ended: Renewed") and the authority, and says that nothing is deleted. Rows that have not ended keep their menu there. That is where a term that ended without renewal is renewed or ended, because the Members tab shows only rows in office on the date.
  - L2: the "Escalate to committee" step picks from `GET /admin/workflow/escalation-targets` (name and code). A key that names no active committee stays selected as "<key> (not found)", and the step says "This committee does not exist or is dissolved." The server's `UNKNOWN_FORUM` at `/steps/N/forum` shows there too, instead of the raw message. The step type reads "Escalate to a committee" in English; the Arabic already said لجنة.
- **Deviations in T5:**
  - Small backend change: members and secretaries could not read the type's policy, because the type endpoints need `forum.types.*`. The Policy tab, the attribute ticks of the add-member sheet, the usual term and the quorum line all need it. The new `GET /forums/{id}/policy?on=` (`ForumPolicyResponse`) returns the published version's description, attributes, quorum rule, outcomes, term policy and default classification, plus `quorumBasis` and `quorumNeeded` from `Quorum.evaluate` over the members in office that day. Whoever sees the committee may read it. Covered in `ForumsIT` and the authorisation matrix; contract and web client regenerated.
  - Small backend change: `GET /admin/forum-types` now also admits `forums.manage`, as the authorisation matrix (design §4) says, so a committee administrator who neither makes nor checks types can still choose one in "New committee".
  - Renew and Change role send no `If-Match`: those endpoints take none, and a repeated request is refused by state (`MEMBER_ENDED`). End and Edit send the row's version; Edit committee and Dissolve send the committee's.
  - History is a table (name, role, term, from, until, state with reason, authority) rather than K2's prose lines. It carries the same facts and is easier to scan.
  - The committee page reuses the committee-type texts (quorum sentence, outcome meanings), so the `forumAdmin` part now also loads on `/committees`. Every member-facing text of T5 is in `forums`.
  - "My committees" is in the account menu (beside My security) and on the home page; "Committees" joins the Work menu for every signed-in user. The nightly visual baselines that show the menu need re-rendering.

## 2026-11-28 — Terms and position seats now drive the queue readers by date
- Terms of office and position seats were already resolved by date (`ForumDirectory.membersOn`), but the memo readers derived from them (E02's `FORUM` rows) were refreshed only by edits. A daily per-tenant run in the tenant's zone and a listener on assignment changes now keep them in step; a position's new holder also reaches open meetings' audiences through `ForumChanged`. Details in the E02 log of the same date and the ADR-0059 amendment.
