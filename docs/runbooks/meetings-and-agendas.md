# Meetings and agendas

**When:** a committee secretary schedules a meeting, builds and publishes its agenda, takes a late paper or calls a meeting off, or someone asks "why can't this director see that paper?", "why won't it publish?" or "why did the secretary get this reminder?"; or a director declares an interest in an item (see Conflicts of interest); or the secretary keeps the register at the meeting and someone asks "was it quorate?" (see Attendance and quorum). Background: ADR-0060 (meetings and the agenda model), ADR-0061 (meeting papers and the one access rule), ADR-0062 (declarations and recusal), ADR-0064 (attendance and quorum), Phase 4 E03, E04 and E05 `design.md`. Committees, their members and the papers sent to them are in [committees.md](committees.md); invitations, replies and calendars in [meeting-invitations.md](meeting-invitations.md).

## Who does what
| Who | Can |
|---|---|
| The committee's secretaries in office, and holders of `forums.manage` | Create, schedule, reschedule, start, mark as held and cancel meetings; add guests; build the agenda; record the chair's agreement and answers on the chair's behalf; publish; read every draft, paper and original |
| The chair in office (the vice-chair when there is no chair, or the chair has declared an interest in the item) | Read the draft; comment to the secretaries; agree the agenda before its first publication; agree or decline late papers |
| Members, guests and item attendees (the audience) | Read the published agenda and the papers the rule lets them read (PDF only); ask to discuss a consent item |
| `forum.read` holders (for example the auditor) | See the meetings and the members-only lines without titles; no papers, no join link |

Everyone else gets 404: a meeting, item or paper they may not see does not exist for them.

## Meetings
- **States:** DRAFT (officers only) → SCHEDULED (the audience sees the date, place and link) → IN_PROGRESS → HELD. A draft can be discarded and a scheduled meeting cancelled, both with a reason. Anything else answers 409 `WRONG_STATE`.
- **Times** are entered on the meeting's own clock and time zone (chosen by city). Members abroad also see their own time. A start inside a daylight-saving gap is refused.
- **The type version is pinned** when the meeting is created: a later change to the committee type (agenda template, paper deadline, late rule, chair's agreement) applies from the next meeting, not this one.
- **Rescheduling** a scheduled meeting needs a reason, which the members see. Papers placed from the queue follow the new date, the paper deadline moves with it, and the sweep's reminders start again for the new date. Moving a meeting earlier can make papers added afterwards late (see Late papers).
- **Cancelling** freezes the agenda. Papers placed from the queue go back to the committee's "Ready for an agenda" list, still accepted, so they can be placed on another meeting. Members lose access to the papers; officers keep it.
- **Guests** attend the whole meeting but never see a members-only section. Removing one records the time; nothing is deleted.
- **Meeting numbers** ("BRC 3/2026") are taken at the first publication, per committee and calendar year of the meeting's date, with no gaps. The committee's code locks at the same moment.

## Building the agenda
- The first time the builder opens, the secretary starts from the committee type's **standard agenda** or **copies an earlier meeting** (structure, times, presenters and restrictions; never papers, queue items, lateness or agreements). Restrictions that named people not on the new meeting are dropped and listed.
- Every row has a **Move menu** (up, down, to a section, to a position, make a sub-item, make an item again); dragging is an extra. One level of sub-items only (`ONE_LEVEL`).
- **Papers** come from the memo's pack (its final record or document, then attachments; the secretary may leave files out and put them back) or are uploaded for the meeting (PDF, Office, OpenDocument or images, up to 50 MB; members read them as PDF). At most 20 papers per item, 150 items and 30 sections per agenda.
- **Who can see this:** an item or paper can be limited to "only these people" from the meeting's audience. Others see the item's line with "papers restricted"; with "hide the title" they see "Restricted item". A restricted paper is not listed at all for the others. Replacing a paper keeps its restriction.
- **Two secretaries at once:** every change carries the agenda's version. The second one gets "the agenda has moved on" (412) and the page reloads the latest draft; nothing is lost silently.
- **Too many changes in a short time** (429): builder writes are rate-limited per person. Wait a minute.

## Publishing and revisions
- Publishing copies the draft into **revision n**: numbers, times, restrictions and each paper pinned to its current file version. Revisions never change afterwards. Members keep reading revision n until n+1 is published, whatever the secretary changes meanwhile.
- **Revision 1** needs the chair's agreement when the committee type says so (the default). The chair agrees in the builder, or the secretary records it ("By email on 4 Dec"); the record names the chair and the secretary who recorded it.
- **From revision 2** a one-line note is required ("Updated credit paper"). Members see "What changed since you last looked": items added, removed, moved (with the old number), renamed, retimed, and papers added, replaced or removed. Restriction changes are never shown as changes.
- **Tell the members** is on by default. Each person is told once per revision, and only if something they may see changed. Email subjects carry no meeting or item names. Turn it off for a minor correction.
- **Publishing is refused when:**

  | Answer | Why | What to do |
  |---|---|---|
  | `MEETING_NOT_SCHEDULED` | The meeting is still a draft | Schedule it |
  | `CHAIR_AGREEMENT_REQUIRED` | Revision 1 without the chair's agreement | Ask the chair, or record their agreement |
  | `PAPER_NOT_READABLE` (with the paper ids) | A paper's PDF could not be made | Replace the paper with a readable version, or remove it. A PDF still being prepared does not block; members see "Being prepared" |
  | `NOTHING_TO_PUBLISH` | The draft is the same as the last revision | Nothing to do |
  | `FROZEN` | The meeting is held or cancelled | Nothing can change; record corrections in the minutes (E06) |

## Late papers
- Papers are due a number of working days before the meeting (the committee type's **Papers due**, 5 by default), counted on the business calendar in the meeting's time zone. The builder shows the deadline.
- After the deadline, what happens to a paper placed from the queue or uploaded follows the type's **Late papers** setting:
  - **allowed**: published with "Late paper";
  - **held for the next meeting**: refused (409 `PAPER_DEADLINE_PASSED`); the paper stays ready for a later meeting;
  - **only if the chair agrees** (the default): the paper waits for the chair, who is told. It is left out of revisions until the chair agrees ("Late paper" when published). If the chair declines, an item goes back to "Ready for an agenda" and an uploaded paper is taken off. The secretary can record an answer the chair gave by email or in person.
- Papers from the memo's pack ride with their item. Standing items, breaks and items without papers are never late.
- **During the meeting**, items and papers can still be added; they are marked "Tabled at the meeting" and no late rule applies.

## Consent items
- Items in a consent section are taken together without discussion. Any member or guest who sees an item can **ask to discuss** it, with no reason and no approval, until the meeting starts; they can withdraw the request. Everyone who sees the item reads "Lifted for discussion at the request of …", and the secretaries and the chair are told. Move the item out of the consent section in the next revision.
- A request after the meeting has started is refused (409 `WRONG_STATE`).

## Background work
- **Notifications of a revision** are a db-scheduler task per revision (`meeting-agenda-notices`), run after the publication commits; each recipient is recorded before being told, so a retry tells nobody twice. A failed task is retried by the scheduler; see the scheduler's failed executions.
- **The hourly sweep** (`meeting-sweep-tenants` → one `meeting-sweep` per tenant) reminds the secretaries (or the holders of `forums.manage` when a committee has no secretary in office), once per date:
  - **Agenda due** (`MEETING_AGENDA_DUE`): the day after the paper deadline, for a draft or scheduled meeting with nothing published;
  - **Not marked as held** (`MEETING_NOT_HELD`): a scheduled meeting a day after it ended. Mark it as held (or cancel it); the meeting page shows "Not marked as held" meanwhile.
  - Rescheduling clears both marks, so the new date reminds again.

## What to do when …
- **A director cannot see a paper.** Check, in this order: the meeting is scheduled and the agenda published (members never see a draft); they are in the audience (a member in office on the meeting's date, a guest, or an attendee of that item); the item or paper is not limited to other people; the section is not members-only for a guest or attendee; their clearance covers the item's and paper's level; they have not declared an interest in the item (see Conflicts of interest). "Preview as …" in the builder shows the agenda exactly as one person would see it if it were published now.
- **A paper shows "Being prepared".** Its PDF rendition is still being built (large Office files take longest). It needs nothing; if it stays, see [attachments-and-previews.md](attachments-and-previews.md).
- **The wrong version was published.** Replace the paper (the draft takes the new version) and publish a new revision with a note. The earlier revision keeps the version it carried, which is the record of what members had.
- **An item must leave a published agenda.** Remove it from the draft and publish again; members see it struck through in "What changed". A paper returned or declined in the committee's queue leaves the draft by itself, and the secretaries are told to publish again (`MEETING_ITEM_WITHDRAWN`).
- **The chair is unreachable before revision 1.** The vice-chair cannot agree in the chair's place while a chair is in office. Record the chair's agreement given outside the app (email, phone), saying how. With no chair or vice-chair in office, the answer is 409 `NO_CHAIR`: appoint one first ([committees.md](committees.md)).
- **A meeting moved to another day.** Reschedule it (never cancel and recreate): the number, agenda, papers placed from the queue and audience follow, and the members see the reason.
- **Slowness.** The budgets (design §8) were met at 3,600 meetings, 180,000 items and 600,000 papers by `MeetingVolumeIT` (weekly `load`, job `meeting-volume`; local p95s in the E03 log, T5). Re-run it after a change to the meeting queries, and check the plans it prints.

## Conflicts of interest
Background: ADR-0062 (per-item declarations and one recusal rule), Phase 4 E04 `design.md`. A declaration is made on one agenda item and is itself the access change: nobody has to remember to restrict the papers.

### Declarations
- **Who declares:** anyone in the meeting's audience (members, guests, item attendees) on an item whose title they see in the latest published revision, from "Declare an interest" on the item or from "Review and declare". Officers can also declare on items still in the draft. Standing items and breaks take no declaration.
- **What they choose:** "I will leave the room for this item" (the default; RECUSED) or "I ask the chair to decide whether I may take part" (AWAITING_RULING). The interest is free text of up to 1,000 characters. Only the secretaries, the chair, the vice-chair and the register's readers see it; other members see only who declared and what they will do.
- **One open declaration per person and item** (409 `ONE_OPEN`). To change it, the person uses "My declaration": ask the chair instead, leave the room instead, or withdraw it with a reason. A withdrawal is told to the secretaries and the chair, and it will be in the minutes.
- **Recorded for someone:** when a director tells the secretary by email, by phone or in person, or says it in the room, an officer not recused from the item records it ("Record a declaration…" on the meeting's Declarations tab). The officer says how it was made and can add a note ("email of 8 Dec"). The record names who recorded it, and the member is told. During the meeting the sheet opens on "At the meeting".
- **When:** members declare, change and withdraw while the meeting is scheduled or in progress. Once it is held:
  - only a secretary can record or withdraw, as a correction with a reason ("Entered after the meeting, as a correction of the record");
  - intent changes, rulings and reviews are frozen (409 `FROZEN`);
  - once the minutes are approved, nobody can change anything.
- **The review:** each person confirms "I have declared my interests in this agenda" once per revision. A later revision asks again only about the items it added. Confirming an older revision answers 409 `STALE_REVISION`; the sheet reads the new items and asks again.
- **Carried over:** some declarations arrive by themselves, marked "Carried over", so nobody declares the same matter twice:
  - **from the memo:** a paper placed from the queue whose memo has interests (an approver who stood aside, or a related party the initiator listed). They carry at placement and at every publication, RECUSED, with the interest "Interest declared on memo {number}", never the memo's own reason;
  - **from an earlier meeting** the same paper was placed on (deferred or removed). RECUSED and waiting ones arrive RECUSED; a "may take part" arrives waiting for the chair again;
  - **once per person and item:** a withdrawn declaration is not carried back.
- **Back to the memo:** a declaration on an item placed from the queue is recorded as an interest on the memo ("Declared an interest at BRC 3/2026, item 5"), so the workflow passes the person by on the memo's later steps (SOD-4). Withdrawing the declaration leaves the memo's interest.

### Rulings
- **The chair** decides "They may take part" (TAKING_PART) or "They should leave the room" (RECUSED) on the Declarations tab, with an optional note. The decision can be changed until the meeting is held.
- **The vice-chair** decides instead when the chair made the declaration, is recused from the item, or there is no chair in office.
- **Nobody rules on their own declaration** (409 `SELF_RULING`). A chair or vice-chair recused from the item cannot rule on it (403 `RECUSED`).
- **A secretary records the chair's decision** when it was given by email, by phone or in person ("Record the chair's decision…"), saying how. The decision stays the chair's; the record names the secretary. When no chair or vice-chair is free to decide, the answer is 409 `NO_CHAIR`.
- **While a declaration waits, its papers are withheld** (fail-closed). The member reads "You asked the chair to decide whether you may take part."

### What a declaration does
While a declaration is RECUSED or waiting for the chair, the person sees only the item's line: its number, time, purpose and title ("Restricted item" when the title is hidden), with "— You declared an interest" or "— Waiting for the chair's decision". Sub-items follow their parent. Everything else of the item is withheld on every surface:
- **For everyone:**
  - the papers and their content (404, also through `/files/…` and `?revision=`);
  - "What changed", the revision list and the "My meetings" counts;
  - consent requests (404);
  - notifications about the item: a revision that changes only that item tells them nothing.
- **For a recused officer, also:**
  - changing the item or its papers in the builder (403 `RECUSED`);
  - the item in "Preview as …" and in the publish preview;
  - late-paper requests, discussion requests and item-withdrawn notices on it.
- **Late papers on that item** go to whoever decides for it: the chair, else the vice-chair, who answers under "Late papers for you to decide" on the meeting page. When both are recused, a secretary who is not recused records the answer.
- **Not the memo:** a recused member who reads the memo through the memo's own access (an approver, a copy recipient, a committee officer while the paper is in the queue) still reads the memo. The Declarations tab says "also reads the memo". Change the access on the memo if it must stop.
- **"They may take part"** (TAKING_PART) gives the item back in full, with "The chair decided you may take part."

### The registers and the export
- **The meeting's register** is the Declarations tab, for the committee's secretaries, the chair and the vice-chair. It shows:
  - per item, each declaration with its state, how it was made, the interest and the decision;
  - who has reviewed the latest revision, and who was reminded;
  - "also reads the memo" (above);
  - the **quorum warning**, when the item cannot be quorate under the committee type's quorum rule even with every member present. Whether members with an interest count depends on the rule's `recusedCount`.

  An officer recused from an item sees only their own declaration on it, and no warning.
- **The register across committees** is at `/declarations` (menu "Declarations of interest"):
  - holders of `forum.read` and `forums.manage` see every committee;
  - secretaries, chairs and vice-chairs see their own committees, from the Declarations tab ("Register of declarations for this committee");
  - everyone else gets 403.

  Filters: committee, dates, member and state; 50 rows a page, newest first. Titles follow the paper rule, and interests show only where the title does, so a hidden or restricted item reads "Restricted item" and "(restricted item)".
- **"Export to Excel"** writes the rows under the filters, at most 20,000, as one sheet:
  - any cell that could be read as a formula is prefixed;
  - each export is audited (`meeting.declarations.exported`, with the filter and the count);
  - each person can export 5 times an hour (429: wait).

### Reminders
- **The hourly sweep** (`meeting-sweep`) reminds the members and officers who still have items to review (`MEETING_DECLARATIONS_DUE`; the mail names no item and no interest). It runs for scheduled meetings with a published agenda, from 09:00 on the working day before the meeting until it starts. Holidays are skipped, and times are in the meeting's time zone. Each person is reminded once per revision.
- **"Remind them now"** (secretaries and `forums.manage`) on the Declarations tab sends the same reminder at once, with the same once-per-revision mark. It answers 409 `NOT_PUBLISHED` before revision 1 and `WRONG_STATE` once the meeting is held.

### What to do when …
- **A director emails or phones the secretary with an interest.** Record it for them ("Record a declaration…", By email or By phone, with the email's date in the note). Their papers are withheld at once.
- **A declaration is on the wrong item.** The person, or an officer, withdraws it with a reason, then declares on the right item. The withdrawal stays in the history and in the minutes.
- **Someone declares in the room.** Record it "At the meeting" during the meeting. If it was missed, a secretary records it after the meeting as a correction, with a reason. Once the minutes are approved it can no longer be added.
- **The chair has not ruled and the meeting is close.** The member's papers stay withheld. Ask the chair; a secretary can record a decision given by phone or email. If the chair made the declaration, the vice-chair rules.
- **The quorum warning shows on an item.** Too many members have an interest for the item to be decided. Consider inviting alternates (E05) or referring the item to the board. The warning does not stop the meeting.
- **A recused director says they can still see the matter.** Look for "also reads the memo" on the Declarations tab: they read it through the memo, not the committee. Change the memo's access, or accept it (ADR-0062).
- **A director did not get the reminder.** One of these applies: they had already confirmed this revision; they were already reminded of it; they have no items left to review; or the agenda is not published yet. "Remind them now" says who was reminded.
- **A memo's interest did not reach the committee's item.** It carries at placement and at each publication. Publish the agenda again after the interest was added on the memo.

## Attendance and quorum
Background: ADR-0064 (attendance and quorum at the meeting; amends ADR-0057), Phase 4 E05 `design.md` §1.6–§1.7. The quorum rule itself (counts, thresholds, required roles, composition, and the switches for alternates, online attendance and recused members) is the committee type's; see [committees.md](committees.md).

### The register
- **Who keeps it:** the committee's secretaries in office and holders of `forums.manage`. Members, guests and alternates see "At the meeting" instead: "You are recorded as present (online)", "You sent your apologies", and whether the meeting is quorate.
- **It opens when the meeting starts** ("Start the meeting…" in the meeting's ⋯ menu). Everyone in the audience gets a row: **Members** (each alternate under the member they replace, "Alternate for …") and **In attendance** (guests, item attendees). Apologies from the replies for the current times are already there ("Apologies (from their reply)"). The Attendance tab is where officers land while the meeting runs.
- **One press records now** and the item in progress: In person, Online, Apologies, Absent for someone expected; Left; Back in person, Back online; "Stepped out for this item" on the row of a member who declared an interest in the item in progress, and Back. "Mark everyone who said yes as present (n)" records all expected people who answered yes, each in the way they said, after one confirmation.
- **Another time** is in "⋯" (Arrived or Left at another time…, with the time on the meeting's clock and the item). A time may be from an hour before the start to six hours after the end, never in the future (`TIME_OUT_OF_RANGE`). "Came after all" turns apologies into an arrival; "Changed to online / in person" records a change of mode.
- **"Now: item n"** ("Change the item") is the item in progress. Arrivals and departures are labelled with it ("Left at 11:42 AM, during item 6"), "joined late" means after the first item started (or ten minutes after the start when none has), and the minutes (E06) read who was present for which item.
- **Mistakes:** "Correct an entry…" voids the wrong entry with a reason and can record the right one; the voided entry stays in the History, struck through, with who voided it, when and why. Nothing is ever deleted.
- **After the meeting** (held) every change is a correction with a reason ("Record a missed entry…": arrival, departure, apologies, absence). Once the minutes are approved, or the meeting was cancelled, the register only reads (`FROZEN`).
- **Two secretaries at once:** each entry carries the person's row version; the second gets "… was changed meanwhile … shown as it is now" (412) and the row reloads. Arriving twice answers `WRONG_STATE`; a person counted for one seat cannot arrive for another (`ALREADY_COUNTED`).

### The live quorum
- **The banner** above the tabs, for officers while the meeting runs, refreshed every 15 seconds: "Quorate — meeting: 5 of 7 present, 4 needed.", the item in progress ("Item 5 is not quorate: 2 members have declared an interest and do not count for it."), and the unmet conditions in words ("Needs the chair or a vice-chair present."). A change between quorate and not is announced politely to screen readers ("Quorum lost: … No decision can be recorded until quorum is restored.").
- **Quorum lost and restored** are logged with the time, the numbers, the item and the entry that caused them, audited (`meeting.quorum.lost`, `.restored`; `.reached` the first time), and told in the app to the chair and the secretaries (not by email: they are in the room). Item-level changes are logged and audited too.
- **"Quorum by item…"** shows any published item: who is counted, who is not and why (declared an interest in this item, stepped out for it, online where that does not count, not at the meeting), and the result. An officer who declared an interest in the item sees the numbers only.
- **Who counts** (ADR-0064):
  - a member present in person; online only when the rule's "online attendance counts" is on;
  - **an alternate** standing in for a member who sent apologies counts towards the number only (when "alternates count" is on), never as the chair, a vice-chair or an independent member, and only while the member is absent. A member online keeps their seat even where online does not count, so their alternate does not count then;
  - **a member with an interest in the item** does not count for it unless the rule's "recused members count" is on, and their alternate never stands in for that item (the alternate is recused with them);
  - a member stepped out of the room for an item does not count for that item.
- **Before the meeting** the Invitations and replies tab shows the forecast from the replies, and the sweep warns the secretaries and the chair when it is not quorate (see [meeting-invitations.md](meeting-invitations.md), Reminders).

### Decisions and the quorum (for E06)
- Each decision keeps a **quorum snapshot**: the meeting, the item, the instant, the pinned type version and its rule, the roster, who filled which seat, who was recused and who had stepped out, with the result and a SHA-256 of all of it. Recomputing the stored inputs gives the stored result; a later correction of the register does not change a snapshot; `QuorumSnapshots.drifted` tells E06 that the register was corrected after the decision, so the minutes can show both.
- **A decision on an item that is not quorate is refused** (409 `NOT_QUORATE`, "3 of 4 needed"). Only when the committee type's "Decisions without a quorum" is "With a reason, to be ratified" can a secretary who has no interest in the item record it anyway, with a reason; it is stored as not quorate, overridden and to be ratified at a later quorate meeting, audited (`meeting.quorum.overridden`, the reason as its hash) and told to the chair and the other secretaries. Otherwise `OVERRIDE_NOT_ALLOWED`; a recused secretary gets 403 `RECUSED`.

### What to do when …
- **The register says someone left who did not.** "Correct an entry…" on their row, say what is wrong, and record the right entry if needed. During the meeting that is all; after it, it is a correction with a reason.
- **The quorum was lost.** The banner says since when and why. Wait for members to return, or adjourn the item. No decision can be recorded until quorum is restored, unless the committee's rules allow an override (above).
- **An alternate is not counted.** Check that their member sent apologies (the alternate is "Alternate for …" under the member), that the member is not present or online, that the rule lets alternates count, and that the member has no interest in the item in progress.
- **An item is not quorate but the meeting is.** Members with an interest in it do not count for it, and members who stepped out are out of the room. Consider inviting alternates of other members before the meeting, or deferring the item.
- **"Give a time during the meeting".** The time is outside an hour before the start to six hours after the end, or in the future. The screens take times on the meeting's own date only, so for a meeting that ran past midnight record the last entries at the time they were made (now) or at the nearest time that day, and say so in the reason.
- **The meeting was not started in memo.** Start it when it begins. If it was held without memo, start it and mark it as held afterwards, then record the paper register as corrections, with the reason "recorded from the paper register".

## Demo data
The demo tenant's seeder (`DemoMeetingSeeder`, after the demo memos) adds these meetings once, with dates relative to the day it runs: the Board Risk Committee's "Quarterly risk review" (two revisions: the replaced risk appetite paper, a late liquidity addendum the chair agreed, a consent item Demo Director asked to discuss, an item whose papers only the chair reads, an in-camera section and the CFO as a guest), and the Management Credit Committee's "Credit approvals" with a BDT 15 million credit proposal placed from the queue, not yet published. (E05 adds a third, held, below). On a long-lived environment they drift into the past; a meeting already there is never seeded again.

Declarations of interest (Phase 4 E04):
- **At the risk review:** Demo Director declared in the app on "Term loan, Delta Textiles" and leaves the room (recused). He asked the chair (Demo Board Member) to decide on "Operational risk incidents, third quarter", which is still waiting. He confirmed the review of revision 2. The secretary recorded the Chairman's declaration on "Large exposures and concentration limits" "by email".
- **At the credit committee:** Demo Head of Credit stood aside on the Padma Spinning Mills proposal at the credit desk (an interest on the memo), and Demo Director recommended it. Placing it carried a declaration to the item, so the vice-chair is recused from it.
- **Gaps only:** a declaration is added only while the risk review is still scheduled, and only for a person who never had one on that item. A risk review seeded before E04 has no Delta Textiles item, and a credit proposal recommended before E04 has no interest, so those two declarations are not added there.

Replies and a held meeting (Phase 4 E05):
- **Alternate:** Demo Director's seat on the risk committee has Demo Head of Credit as its alternate (`DemoForumSeeder`, added only to a seat without one).
- **Replies at the risk review:** Demo Director sent apologies ("Travelling for the regional review that week.") and Demo Head of Credit stands in for him, recused from the same items. The CFO, a guest, sent apologies. The secretary recorded the Chairman's "Yes, in person" "by phone". The chair, Demo Board Member, said yes for the date first planned, so since the meeting was brought forward the answer is "for the old time". The Chairman's account cannot sign in, so he holds no invitation: the Invitations and replies tab says so on his row.
- **The credit committee's "Monthly credit review"**, hybrid, was held about ten days before the seed ran (14:00–16:00, Dhaka time; MCC numbered at its publication). The register was kept as it happened: the secretary, Demo Director (chair) and Demo Head of Finance in person before the start and Demo Head of Credit (vice-chair) online at 14:00, quorate (3 of 4, 3 needed); the CRO's seat holder arrived at 14:20, during matters arising ("joined late"); Demo Head of Credit, who declared an interest in "Working capital limit, Meghna Foods", stepped out for it and came back; Demo Head of Finance left at 15:15 ("left early"); Demo Head of Credit's connection dropped at 15:30 (quorum lost, 2 of 4) and was back at 15:38 (restored). The Meghna Foods decision's quorum snapshot (14:45, quorate 3 of 3 needed, the vice-chair recused) is kept for E06's minutes.
- **Gaps only:** replies are added only while the risk review is still scheduled and only for someone with no answer; the "old time" answer only when the review itself is seeded. The held review is seeded once, by its title; a review already there is left as it is.
