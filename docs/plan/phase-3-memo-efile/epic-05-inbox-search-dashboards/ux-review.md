# E05 Inbox, search and dashboards — UX review

> `AGENTS.md §5` Step 3 and Phase 3 overview §9: **search** (flow H) and the **team view** (flow I) go to the owner **before** their web tasks. Flows J (watching and bulk) and K (dashboards) follow existing patterns and are shown for information. Wireframes are structural; the look is the existing Ledger direction and components. The questions at the end each have a recommended answer.

## Flow H — Search

**H1. The search box** (top bar, every page; `/` focuses it)
```
[ 🔍 Search memos, notes and documents…                              ]
  Recent:  "rent schedule"  ·  Saved: "Credit memos this quarter"
```
Enter opens the Search page with the query. Recent and saved searches show as a combobox list (keyboard: ↓, Enter, Esc).

**H2. Search page**
```
Search                                                        [ Save this search ]
[ rent schedule                                   ]  [ Search ]
23 memos · Status: In review ✕ · Unit: Credit Division ✕      Clear filters
Sort: [ Relevance ▾ ]
┌ Filters ─────────────┐  ┌───────────────────────────────────────────────────────┐
│ Status               │  │ M-2026-00042 · Office lease extension          In review│
│ ☑ In review (12)     │  │ Credit Division · General memo · updated 9 Nov 2026   │
│ ☐ Approved (9)       │  │ …the revised «rent schedule» for floors 3 and 4…       │
│ ☐ Returned (2)       │  │ Found in: note by Demo Head of Credit                 │
│ Type                 │  ├───────────────────────────────────────────────────────┤
│ ☐ General memo (17)  │  │ CR/2026/0001 · Credit line renewal for Meghna Agro  Approved│
│ ☐ Credit proposal (6)│  │ …annex B, «rent schedule» attached…                    │
│ Unit                 │  │ Found in: attachment "annex-b.pdf"                    │
│ [ Credit Division ▾ ]│  ├───────────────────────────────────────────────────────┤
│ Date                 │  │ …                                                     │
│ From [    ] To [   ] │  └───────────────────────────────────────────────────────┘
│ Amount (BDT)         │   [ Load more ]
│ [ min ] – [ max ]    │
│ Search in            │
│ ☑ Details ☑ Notes    │
│ ☑ Documents          │
│ [ Apply filters ]    │
└──────────────────────┘
```
- **The count:**
  - announced politely (an `aria-live` heading);
  - "2,000+ memos" when the cap is reached, with "Showing the best matches among the first 2,000. Add words or filters to narrow it.";
  - facet counts are of permitted memos only.
- **Snippets:** matches highlighted with `<mark>`. "Found in" names the place (details, a note and its author, the document, an attachment by name). Nothing comes from a memo or attachment the reader cannot open.
- **Identifiers:** a number such as `M-2026-00042`, `00042` or `CR/2026/0001` finds the memo directly; an exact match is put first.
- **Phones:** the filters collapse behind a "Filters (2)" button, and results take the full width.
- **Empty and limit states:**
  - no results: "No memos match. Check the spelling, remove filters, or search all of the memo's text." with Clear filters;
  - rate limit: "Too many searches in a minute. Try again shortly.";
  - timeout: "That search took too long. Add words or filters."

**H3. Save this search**
```
Save this search
Name *  [ Credit memos this quarter ]
Only you see your saved searches. Each time you open one, it shows what you may read then.
                                                            [ Cancel ] [ Save ]
```
Saved searches appear in the box's list and on a "Saved searches" page (rename, delete).

## Flow I — Team view (unit heads, and holders of `inbox.team.read`)

**I1. Inbox → Team tab**
```
Inbox    My tasks (4) · Drafts · Sent · Watching · [Team]
Team: [ Credit Division and its units ▾ ]  Status: [ Pending ▾ ]  Person: [ Anyone ▾ ]
14 tasks · 3 overdue                                                   Sort: Due ▾
☐  OVERDUE 2 days  M-2026-00031 · Branch generator replacement  Demo Credit Officer  Approve · step 2
☐  due today 16:00 M-2026-00042 · Office lease extension        Demo Head of Credit  Approve · step 1
☐  due in 2 days   Restricted memo                              Demo Credit Analyst  Opinion
…
[ 2 selected ]  [ Reassign… ]  [ Watch ]
```
- Overdue items come first, marked with text and an icon, never colour alone.
- A memo the head may not read shows as "Restricted memo", with no subject or number; it can still be reassigned.
- Reassign (heads within their scope) asks for the new person and a reason, then shows a result per item: "2 reassigned · 1 not changed: it was decided meanwhile".
- **Phones:** the list stacks, and the bulk bar sits at the bottom.

## Flow J — Watching and bulk (for information)
- **Memo page:** a "Follow" toggle. When following: "Following · you get updates on this memo · Unfollow".
- **Watching tab:**
  - memos followed, newest update first, each with its reason ("you approved step 2", "you follow this");
  - checkboxes and a bulk bar (Unfollow).
- **My tasks:** checkboxes and a bulk bar (Claim, Release, Watch). Results per item. **There is no bulk Approve, Return or Reject.**
- **Notification preferences:** "Follow memos I create or act on" (on) and "Email me about followed memos: Daily digest (default) · At once · Never".

## Flow K — Dashboards (for information)
```
Approvals dashboard        Scope: [ Credit Division and its units ▾ ]   Period: [ Last 12 weeks ▾ ]
Showing: Credit Division and its units, 1 Sep – 15 Nov 2026
┌ Pending by age ──────────────┐ ┌ Overdue ───┐ ┌ Cycle time by memo type ───────────┐
│ ▇▇▇ 0–2 days   41            │ │ 7 tasks    │ │ Credit proposal  median 3.2 · P90 9.5│
│ ▇▇  3–5 days   18            │ │ View list →│ │ General memo     median 1.4 · P90 4.0│
│ ▇   6–10 days   6            │ └────────────┘ └──────────────────────────────────────┘
│ ▏  >10 days     2            │
│ [ Show as table ]            │  Throughput per week: created vs completed (line chart + table)
└──────────────────────────────┘
```
- **Each chart:**
  - is a figure with a one-line summary, with its data table one click away (on by default for screen readers);
  - is labelled directly, with markers, not colour alone;
  - is reachable by keyboard.
- Every number links to the list behind it.
- People below "unit" scope see only "Me".

## Questions for the owner (recommendations first)
1. **Team view: who sees it?**
   - **Recommended:** holders of a unit's head position see that unit and its sub-units automatically, and tenants may also grant `inbox.team.read` to others (for example a COO or a registry), scoped like other grants.
   - Alternative: by grant only.
2. **Bulk actions:**
   - **Recommended:** Watch, Unwatch, Claim, Release, and Reassign (heads within scope and holders of the reassign permission), with results per item and no bulk decisions.
   - Alternative: add bulk "Mark as read" for notifications only.
3. **Email for followed memos:**
   - **Recommended:** a daily digest by default; "at once" and "never" are personal choices. Items that need the person are always immediate.
4. **Saved searches:**
   - **Recommended:** private only in E05; sharing and email subscriptions later.
5. **Search in documents:**
   - **Recommended:** on by default, searching only the text of documents and attachments the reader is cleared for.
   - Alternative: details and notes by default, documents on request (faster).
6. **Dashboards: who sees unit scope?**
   - **Recommended:** heads for their subtree, and holders of `memo.dashboard.read`. Per-person figures only within that scope.
