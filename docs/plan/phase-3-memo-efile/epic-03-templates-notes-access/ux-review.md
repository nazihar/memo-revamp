# E03 Templates, note sheet and access — UX review

> `AGENTS.md §5` Step 3 and Phase 3 overview §9: the **note sheet** (flow C) and **sharing and copy-to** (flow D) go to the owner **before build**. Flows E (stamps) and F (template admin) follow existing patterns and are shown for information. Wireframes are structural; the look is the existing Ledger direction and components. The questions at the end each have a recommended answer.

## Flow C — Note sheet (memo page)

The note sheet replaces today's "History" section on the memo page. It shows the same rounds, now with rich remarks, comments, stamps and corrections, newest round first.

**C1. Reading**
```
Note sheet                                              [ Write a note ]
──────────────────────────────────────────────────────────────────────────
Round 2 (current)
  Demo User · Resubmitted · 9 Nov 2026, 09:31
    Annex B added with the **lease terms**. @Demo Head of Credit, page 4
    has the rent schedule you asked for.
    📎 annex-b-lease.pdf
  Demo Head of Credit · Comment · 9 Nov 2026, 11:02
    Thanks. See also M-2026-00007 for the expansion plan.
  ┌ Demo Head of Credit · Approved · 9 Nov 2026, 14:20 ─────────────────┐
  │ Supported. The rent is within the budget.                           │
  │ Authenticated with password and authenticator, 2 min before.        │
  │ Document v2 · 3f9a1c07                              [ Check ]       │
  └──────────────────────────────────────────────────────────────────────┘
Round 1
  Demo User · Submitted · 8 Nov 2026, 08:05
    For your approval.
  Demo Head of Credit · Returned · 8 Nov 2026, 14:20        Corrected ↓
    Please attach annex B with the lease terms.  (struck through, grey)
  Demo Head of Credit · Correction · 8 Nov 2026, 14:25
    Please attach annex B with the lease terms **and the rent schedule**.
```
- Each entry shows who, the kind (Submitted, Approved, Returned, Rejected, Comment, Correction), the time and, when acting for someone, "for Demo Director".
- **Decisions** (approve, return, reject) are boxed **stamps** (flow E).
- **Mentions** appear as names; a memo number is a link that opens that memo if you may read it, else "Memo (restricted)".
- **A corrected entry** stays visible, struck through and greyed, with "Corrected ↓" linking to its correction.
- **Attachments on a remark** open in the existing preview dialog.
- **Phone:** the same list, one column; stamps full width.

**C2. Writing a note** (button "Write a note", or the remark field in the approve, return and reject dialogs)
```
┌ Write a note ───────────────────────────────────────────────────────────┐
│ [B] [I] [• List] [1. List] [Link] [@ Mention] [# Memo]                   │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │ The valuation in annex C is from 2025; @Demo Hea│                    │ │
│ │                                   ┌────────────────────────┐        │ │
│ │                                   │ Demo Head of Credit    │        │ │
│ │                                   │ Demo Head of Finance   │        │ │
│ │                                   └────────────────────────┘        │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│ 📎 Add a file    valuation-2025.pdf ✕                                    │
│ Draft saved 10:42 · only you can see it                1,240 / 4,000    │
│                                              [ Cancel ]  [ Post note ]  │
└─────────────────────────────────────────────────────────────────────────┘
```
- **Toolbar:** bold, italic, the two kinds of list, a link (https only), a mention (also by typing `@`), and a memo reference (also by typing `#` and a number). Keyboard shortcuts as usual (Ctrl+B, Ctrl+I).
- **Mention list:**
  - It shows people who may read the memo first, then others marked "no access".
  - Picking someone without access asks: *"Demo Head of Finance cannot see this memo. Share it with them? Reason: [ ]"*, with the choice **Share and mention** or **Cancel**.
- **The draft** saves itself every few seconds and survives closing the browser. It is only yours; posting removes it.
- **Posting is final.** The button says "Post note", and the help text under it reads "Notes cannot be edited. You can post a correction."
- **The approve, return and reject dialogs** get the same editor in place of today's plain remark box. On a phone they stay full-screen sheets.

**C3. Correcting**
- Your own entries show **Correct** in a "⋯" menu while the memo is open.
- It opens the editor pre-filled with the original text, under the heading "Correction of your note of 8 Nov, 14:20".
- Posting adds the correction. The original stays, marked as corrected.

## Flow D — Sharing and copy-to

**D1. Who can see this memo** (side column, below Co-authors)
```
People with access                                   [ Share ]
  Approvers          Demo Head of Credit, Demo Head of Finance, …
  Copies             6 people · 4 read          [ Copy to… ]
  Shared with        Internal Auditor (until 31 Dec) · by Demo User
                     "Audit sample Q4"                         [ Revoke ]
```
- Every reader sees this box. Revoke shows only to whoever shared, the initiator, and holders of the "manage shares" permission.

**D2. Share** (dialog)
```
Share this memo
  Person        [ Search people…            ]
  Reason        [ Audit sample Q4                         ]  (required)
  Until         [ 31/12/2026 ]  (optional, at most a year)
  Only people cleared for "Internal" can be chosen.
                                              [ Cancel ]  [ Share ]
```

**D3. Copy to** (dialog; on a draft or a returned memo, the initiator or a co-author)
```
Copy to
  Add  [ Search people, positions or lists…   ]
  ┌──────────────────────────────┬────────────────────────────┬───┐
  │ Recipient                    │ Send                       │   │
  ├──────────────────────────────┼────────────────────────────┼───┤
  │ Branch managers (list, 12)   │ ( ) at submit (•) after approval │ ✕ │
  │ Head of Operations (position)│ (•) at submit ( ) after approval │ ✕ │
  │ Demo Auditor                 │ ( ) at submit (•) after approval │ ✕ │
  └──────────────────────────────┴────────────────────────────┴───┘
  Copies can read the memo and its notes. They cannot act on it.
                                              [ Cancel ]  [ Save ]
```
- **At submit:** a person the memo's level does not allow is listed in the submit error, like an approver who is not cleared.
- **After approval:** the copies go out when the last approver approves. If the memo is rejected or withdrawn, they are not sent, and the dialog shows "not sent".
- **After approval, adding more:** a registry clerk with the "add copies" permission sees **Copy to…** on approved memos, and may only add.

**D4. Receipts** (opened from "6 people · 4 read")
```
Copies and receipts                                   Sent 9 Nov, 14:20
  Recipient                 Via                    Read
  Demo Head of Operations   Head of Operations     9 Nov, 15:02 (v2)
  Branch Manager, Sylhet    Branch managers        —
  Demo Auditor              —                      10 Nov, 09:10 (v2)
  Not sent: Branch Manager, Rangpur (not cleared for Internal)
```
- **Read** means the person opened the memo page. The version they saw is in brackets.
- The initiator, co-authors and auditors see this. Approvers' first opens show in the same way under "Approvers".

**D5. Copy recipient's view.** The memo opens with a notice: *"You received a copy of this memo for information. You cannot act on it."* There is no action bar, and no Write a note unless the tenant allows comments by copies.

## Flow E — Stamps and the check (for information)
- Stamps are as in C1. **Check** opens a small dialog: *"Checked just now: the document and attachments match what Demo Head of Credit approved, and the record has not changed."* If something differs, it names what: *"Document version 2 does not match its recorded hash."*
- Auditors also see the IP address and the device ("Chrome on Windows") on each stamp.
- **Wording everywhere is "authenticated approval".** The words "digital signature" are not used.

## Flow F — Templates (admin, for information)
On the memo-type page (E01 A2), the Settings tab gains a **Template** block in a draft version:
```
Template    letterhead-credit.docx (48 KB) · uploaded 15 Nov   [ Replace ] [ Remove ]
            ✓ 9 tags, all known              [ Preview as PDF ]
            Tags you can use:  {{memo.number}} ⧉  {{memo.date}} ⧉  {{memo.subject}} ⧉
                               {{initiator.name}} ⧉  {{meta.amount}} ⧉  … [ All tags ]
```
- **Problems** are listed with where they are, for example *"Header 1, paragraph 2: `{{memo.numbr}}` is not a tag. Did you mean `{{memo.number}}`?"* or *"This file contains macros. Save it as .docx (not .docm) and upload again."*
- Submit for review is disabled until the template has no problems and has been previewed.
- **For initiators:** when creating a memo of a type with a template, the document starts as the filled template, and the create page says *"The document starts from the Credit facility template."*

## Questions for the owner
1. **Copy timing default.** Recommended: each recipient defaults to **after approval** (information about the decision), and **at submit** is chosen per recipient.
2. **Comments by copy recipients.** Recommended: **off** by default, with a tenant setting to allow them.
3. **Unit readers.** Recommended: a privileged "Read memos of a unit" permission. No built-in role gets it; tenants grant it, for example to internal audit for a division. In the demo, give it to the auditor for the Credit Division.
4. **Naming.** Recommended: **"authenticated approval"**, not "e-signature" or "digital signature", because of the legal meaning of those terms (research §B.5).
5. **Note sheet replaces History.** Recommended: yes, one record of the memo. Audit-style events (reassignment, escalation) stay as small grey lines in it.
6. **Drafts of notes.** Recommended: kept privately on the server (survive a closed browser), not shown to anyone, and removed when posted.

## Owner feedback
_(to be recorded here after the review)_
