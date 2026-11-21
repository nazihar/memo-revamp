# Meetings and agendas

**When:** a committee secretary schedules a meeting, builds and publishes its agenda, takes a late paper or calls a meeting off, or someone asks "why can't this director see that paper?", "why won't it publish?" or "why did the secretary get this reminder?". Background: ADR-0060 (meetings and the agenda model), ADR-0061 (meeting papers and the one access rule), Phase 4 E03 `design.md`. Committees, their members and the papers sent to them are in [committees.md](committees.md).

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
- **A director cannot see a paper.** Check, in this order: the meeting is scheduled and the agenda published (members never see a draft); they are in the audience (a member in office on the meeting's date, a guest, or an attendee of that item); the item or paper is not limited to other people; the section is not members-only for a guest or attendee; their clearance covers the item's and paper's level; they have not declared an interest in the item (E04). "Preview as …" in the builder shows the agenda exactly as one person would see it if it were published now.
- **A paper shows "Being prepared".** Its PDF rendition is still being built (large Office files take longest). It needs nothing; if it stays, see [attachments-and-previews.md](attachments-and-previews.md).
- **The wrong version was published.** Replace the paper (the draft takes the new version) and publish a new revision with a note. The earlier revision keeps the version it carried, which is the record of what members had.
- **An item must leave a published agenda.** Remove it from the draft and publish again; members see it struck through in "What changed". A paper returned or declined in the committee's queue leaves the draft by itself, and the secretaries are told to publish again (`MEETING_ITEM_WITHDRAWN`).
- **The chair is unreachable before revision 1.** The vice-chair cannot agree in the chair's place while a chair is in office. Record the chair's agreement given outside the app (email, phone), saying how. With no chair or vice-chair in office, the answer is 409 `NO_CHAIR`: appoint one first ([committees.md](committees.md)).
- **A meeting moved to another day.** Reschedule it (never cancel and recreate): the number, agenda, papers placed from the queue and audience follow, and the members see the reason.
- **Slowness.** The budgets (design §8) were met at 3,600 meetings, 180,000 items and 600,000 papers by `MeetingVolumeIT` (weekly `load`, job `meeting-volume`; local p95s in the E03 log, T5). Re-run it after a change to the meeting queries, and check the plans it prints.

## Demo data
The demo tenant's seeder (`DemoMeetingSeeder`, after the demo memos) adds two meetings once, with dates relative to the day it runs: the Board Risk Committee's "Quarterly risk review" (two revisions: the replaced risk appetite paper, a late liquidity addendum the chair agreed, a consent item Demo Director asked to discuss, an item whose papers only the chair reads, an in-camera section and the CFO as a guest), and the Management Credit Committee's "Credit approvals" with a BDT 15 million credit proposal placed from the queue, not yet published. On a long-lived environment both drift into the past; a meeting already there is never seeded again.
