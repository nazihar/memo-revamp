import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of attendance and quorum at the meeting (Phase 4 E05 T8, flows X and Y): the officers' Attendance tab (the
 * register, "Now: item n", corrections), the live quorum banner and the per-item view, the members' summary during the
 * meeting, the forecast lines of the Invitations and replies tab, and the override dialog E06's decisions open. The
 * `meetingAttendance` prefix is its own catalogue part, loaded on the `/meetings` pages.
 */
export const am = defineMessages({
  tab: { id: 'meetingAttendance.tab', defaultMessage: 'Attendance' },
  cancel: { id: 'meetingAttendance.cancel', defaultMessage: 'Cancel' },
  close: { id: 'meetingAttendance.close', defaultMessage: 'Close' },
  nobody: { id: 'meetingAttendance.nobody', defaultMessage: 'Nobody.' },
  anItem: { id: 'meetingAttendance.anItem', defaultMessage: 'an item' },
  thisItemStart: { id: 'meetingAttendance.thisItem', defaultMessage: 'This item' },
  online: { id: 'meetingAttendance.online', defaultMessage: 'online' },
  theSystem: { id: 'meetingAttendance.theSystem', defaultMessage: 'memo, from the replies' },
  // Roles and the role phrases of the quorum's conditions
  roleChair: { id: 'meetingAttendance.role.chair', defaultMessage: 'Chair' },
  roleViceChair: { id: 'meetingAttendance.role.viceChair', defaultMessage: 'Vice-chair' },
  roleSecretary: { id: 'meetingAttendance.role.secretary', defaultMessage: 'Secretary' },
  roleMember: { id: 'meetingAttendance.role.member', defaultMessage: 'Member' },
  roleStandingInvitee: { id: 'meetingAttendance.role.standingInvitee', defaultMessage: 'Standing invitee' },
  roleObserver: { id: 'meetingAttendance.role.observer', defaultMessage: 'Observer' },
  alternate: { id: 'meetingAttendance.role.alternate', defaultMessage: 'Alternate' },
  guest: { id: 'meetingAttendance.role.guest', defaultMessage: 'Guest' },
  inAttendance: { id: 'meetingAttendance.role.inAttendance', defaultMessage: 'In attendance' },
  phraseChair: { id: 'meetingAttendance.phrase.chair', defaultMessage: 'the chair' },
  phraseViceChair: { id: 'meetingAttendance.phrase.viceChair', defaultMessage: 'a vice-chair' },
  phraseSecretary: { id: 'meetingAttendance.phrase.secretary', defaultMessage: 'the secretary' },
  phraseMember: { id: 'meetingAttendance.phrase.member', defaultMessage: 'a member' },
  phraseStandingInvitee: { id: 'meetingAttendance.phrase.standingInvitee', defaultMessage: 'a standing invitee' },
  phraseObserver: { id: 'meetingAttendance.phrase.observer', defaultMessage: 'an observer' },
  // The register (flow X1)
  groupMembers: { id: 'meetingAttendance.group.members', defaultMessage: 'Members' },
  groupOthers: { id: 'meetingAttendance.group.others', defaultMessage: 'In attendance' },
  colName: { id: 'meetingAttendance.col.name', defaultMessage: 'Name' },
  colRole: { id: 'meetingAttendance.col.role', defaultMessage: 'Role' },
  colAttendance: { id: 'meetingAttendance.col.attendance', defaultMessage: 'Attendance' },
  colActions: { id: 'meetingAttendance.col.actions', defaultMessage: 'Actions' },
  expected: { id: 'meetingAttendance.state.expected', defaultMessage: 'Expected' },
  apologies: { id: 'meetingAttendance.state.apologies', defaultMessage: 'Apologies' },
  apologiesFromReply: { id: 'meetingAttendance.state.apologiesReply', defaultMessage: 'Apologies (from their reply)' },
  absent: { id: 'meetingAttendance.state.absent', defaultMessage: 'Absent, without apologies' },
  joinedLate: { id: 'meetingAttendance.joinedLate', defaultMessage: 'joined late' },
  numberOnly: { id: 'meetingAttendance.numberOnly', defaultMessage: 'counts for the number only' },
  declaredHere: { id: 'meetingAttendance.declaredHere', defaultMessage: 'Declared an interest in this item' },
  modeInPerson: { id: 'meetingAttendance.mode.inPerson', defaultMessage: 'in person' },
  modeOnline: { id: 'meetingAttendance.mode.online', defaultMessage: 'online' },
  modeInPersonChoice: { id: 'meetingAttendance.mode.inPersonChoice', defaultMessage: 'In person' },
  modeOnlineChoice: { id: 'meetingAttendance.mode.onlineChoice', defaultMessage: 'Online' },
  actionInPerson: { id: 'meetingAttendance.action.inPerson', defaultMessage: 'In person' },
  actionOnline: { id: 'meetingAttendance.action.online', defaultMessage: 'Online' },
  actionBackInPerson: { id: 'meetingAttendance.action.backInPerson', defaultMessage: 'Back in person' },
  actionBackOnline: { id: 'meetingAttendance.action.backOnline', defaultMessage: 'Back online' },
  actionApologies: { id: 'meetingAttendance.action.apologies', defaultMessage: 'Apologies' },
  actionAbsent: { id: 'meetingAttendance.action.absent', defaultMessage: 'Absent' },
  actionLeft: { id: 'meetingAttendance.action.left', defaultMessage: 'Left' },
  actionStepOut: { id: 'meetingAttendance.action.stepOut', defaultMessage: 'Stepped out for this item' },
  actionBack: { id: 'meetingAttendance.action.back', defaultMessage: 'Back' },
  menuCameInPerson: { id: 'meetingAttendance.menu.cameInPerson', defaultMessage: 'Came after all, in person' },
  menuCameOnline: { id: 'meetingAttendance.menu.cameOnline', defaultMessage: 'Came after all, online' },
  menuArrivedAt: { id: 'meetingAttendance.menu.arrivedAt', defaultMessage: 'Arrived at another time…' },
  menuLeftAt: { id: 'meetingAttendance.menu.leftAt', defaultMessage: 'Left at another time…' },
  menuNowOnline: { id: 'meetingAttendance.menu.nowOnline', defaultMessage: 'Changed to online' },
  menuNowInPerson: { id: 'meetingAttendance.menu.nowInPerson', defaultMessage: 'Changed to in person' },
  menuRecordEntry: { id: 'meetingAttendance.menu.recordEntry', defaultMessage: 'Record a missed entry…' },
  menuCorrect: { id: 'meetingAttendance.menu.correct', defaultMessage: 'Correct an entry…' },
  nowNone: { id: 'meetingAttendance.now.none', defaultMessage: 'Now: no item chosen' },
  nowChange: { id: 'meetingAttendance.now.change', defaultMessage: 'Change the item' },
  nowMenu: { id: 'meetingAttendance.now.menu', defaultMessage: 'The item in progress' },
  correctingHint: {
    id: 'meetingAttendance.correctingHint',
    defaultMessage:
      'The meeting is over: what you record now corrects the register, with a reason, until the minutes are approved.',
  },
  frozenHint: {
    id: 'meetingAttendance.frozenHint',
    defaultMessage: 'The register is closed: the minutes are approved.',
  },
  recorded: { id: 'meetingAttendance.recorded', defaultMessage: 'Recorded.' },
  corrected: { id: 'meetingAttendance.corrected', defaultMessage: 'The entry is corrected.' },
  bulkTitle: { id: 'meetingAttendance.bulk.title', defaultMessage: 'Mark everyone who said yes as present' },
  // History
  historyEmpty: { id: 'meetingAttendance.history.empty', defaultMessage: 'Nothing recorded yet.' },
  voidedPrefix: { id: 'meetingAttendance.history.voidedPrefix', defaultMessage: 'Voided:' },
  eventApologies: { id: 'meetingAttendance.event.apologies', defaultMessage: 'Sent apologies' },
  eventApologiesReply: {
    id: 'meetingAttendance.event.apologiesReply',
    defaultMessage: 'Sent apologies (from their reply)',
  },
  eventAbsent: { id: 'meetingAttendance.event.absent', defaultMessage: 'Absent, without apologies' },
  eventVoided: { id: 'meetingAttendance.event.voided', defaultMessage: 'Voided an entry' },
  // An entry at another time, a correction (flow X2)
  kindArrived: { id: 'meetingAttendance.kind.arrived', defaultMessage: 'Arrived' },
  kindLeft: { id: 'meetingAttendance.kind.left', defaultMessage: 'Left' },
  kindApologies: { id: 'meetingAttendance.kind.apologies', defaultMessage: 'Apologies' },
  kindAbsent: { id: 'meetingAttendance.kind.absent', defaultMessage: 'Absent' },
  kindModeChanged: { id: 'meetingAttendance.kind.modeChanged', defaultMessage: 'Changed the way of attending' },
  kindSteppedOut: { id: 'meetingAttendance.kind.steppedOut', defaultMessage: 'Stepped out for an item' },
  kindSteppedBack: { id: 'meetingAttendance.kind.steppedBack', defaultMessage: 'Came back after an item' },
  entryKind: { id: 'meetingAttendance.entry.kind', defaultMessage: 'Entry' },
  entryMode: { id: 'meetingAttendance.entry.mode', defaultMessage: 'How' },
  entryTime: { id: 'meetingAttendance.entry.time', defaultMessage: 'At' },
  entryTimeHint: {
    id: 'meetingAttendance.entry.timeHint',
    defaultMessage: 'On the meeting’s clock, during the meeting.',
  },
  entryTimeRequired: { id: 'meetingAttendance.entry.timeRequired', defaultMessage: 'Give the time.' },
  entryItem: { id: 'meetingAttendance.entry.item', defaultMessage: 'During item' },
  entryItemThen: { id: 'meetingAttendance.entry.itemThen', defaultMessage: 'The item in progress then' },
  entrySubmit: { id: 'meetingAttendance.entry.submit', defaultMessage: 'Record' },
  laterHint: {
    id: 'meetingAttendance.later.hint',
    defaultMessage: 'For an arrival or a departure you could not record when it happened.',
  },
  correctionHint: {
    id: 'meetingAttendance.correction.hint',
    defaultMessage: 'The meeting is over: this entry corrects the register and keeps your reason.',
  },
  correctionReason: { id: 'meetingAttendance.correction.reason', defaultMessage: 'Why the register is corrected' },
  correctHint: {
    id: 'meetingAttendance.correct.hint',
    defaultMessage: 'The wrong entry stays in the history, struck through, with who corrected it, when and why.',
  },
  correctEntry: { id: 'meetingAttendance.correct.entry', defaultMessage: 'Entry' },
  correctWhat: { id: 'meetingAttendance.correct.what', defaultMessage: 'What is wrong?' },
  correctAgain: { id: 'meetingAttendance.correct.again', defaultMessage: 'Record the right entry now' },
  correctSubmit: { id: 'meetingAttendance.correct.submit', defaultMessage: 'Record the correction' },
  // Refusals
  WRONG_STATE: {
    id: 'meetingAttendance.problem.wrongState',
    defaultMessage:
      'That does not fit what is recorded now (for example, they are already present). The register is shown as it is now.',
  },
  FROZEN: {
    id: 'meetingAttendance.problem.frozen',
    defaultMessage: 'The register of this meeting is closed: the minutes are approved or the meeting was cancelled.',
  },
  alreadyCounted: {
    id: 'meetingAttendance.problem.alreadyCounted',
    defaultMessage: 'Someone on the list is already counted at this meeting, as a member or for another seat.',
  },
  TIME_OUT_OF_RANGE: {
    id: 'meetingAttendance.problem.timeOutOfRange',
    defaultMessage: 'Give a time during the meeting (from an hour before it starts), not in the future.',
  },
  ITEM_NOT_ON_AGENDA: {
    id: 'meetingAttendance.problem.itemNotOnAgenda',
    defaultMessage: 'Choose an item of the published agenda.',
  },
  MODE_REQUIRED: {
    id: 'meetingAttendance.problem.modeRequired',
    defaultMessage: 'Say whether they attend in person or online.',
  },
  REASON_REQUIRED: {
    id: 'meetingAttendance.problem.reasonRequired',
    defaultMessage: 'Say why the register is corrected.',
  },
  NOT_INVITABLE: {
    id: 'meetingAttendance.problem.notInvitable',
    defaultMessage: 'This person is not among the people this meeting is for.',
  },
  LENGTH: {
    id: 'meetingAttendance.problem.length',
    defaultMessage: 'Please make this shorter (at most 500 characters).',
  },
  staleRegister: {
    id: 'meetingAttendance.problem.staleRegister',
    defaultMessage:
      'The register was changed meanwhile, perhaps by another secretary. It is shown as it is now: try again.',
  },
  forbidden: {
    id: 'meetingAttendance.problem.forbidden',
    defaultMessage: 'You cannot record attendance for this meeting.',
  },
  failed: { id: 'meetingAttendance.problem.failed', defaultMessage: 'That did not work. Please try again.' },
  // The live quorum (flow Y1) and the per-item view (Y2)
  quorumTitle: { id: 'meetingAttendance.quorum.title', defaultMessage: 'Quorum' },
  quorumLoading: { id: 'meetingAttendance.quorum.loading', defaultMessage: 'Loading the quorum…' },
  quorumUnavailable: {
    id: 'meetingAttendance.quorum.unavailable',
    defaultMessage: 'The quorum cannot be shown right now.',
  },
  noDecision: {
    id: 'meetingAttendance.quorum.noDecision',
    defaultMessage: 'No decision can be recorded until quorum is restored.',
  },
  byItemOpen: { id: 'meetingAttendance.byItem.open', defaultMessage: 'Quorum by item…' },
  byItemTitle: { id: 'meetingAttendance.byItem.title', defaultMessage: 'Quorum by item' },
  byItemChoose: { id: 'meetingAttendance.byItem.choose', defaultMessage: 'Item' },
  noItems: { id: 'meetingAttendance.byItem.noItems', defaultMessage: 'The agenda has no published items.' },
  notCountedHeading: { id: 'meetingAttendance.byItem.notCounted', defaultMessage: 'Not counted:' },
  alternatesNumberOnly: {
    id: 'meetingAttendance.byItem.alternatesNumberOnly',
    defaultMessage: 'Alternates count for the number of members only, not as chair or as independent members.',
  },
  whyDeclared: { id: 'meetingAttendance.why.declared', defaultMessage: 'declared an interest in this item' },
  whySteppedOut: { id: 'meetingAttendance.why.steppedOut', defaultMessage: 'stepped out for this item' },
  whyOnline: { id: 'meetingAttendance.why.online', defaultMessage: 'online, which does not count here' },
  whyAlternate: { id: 'meetingAttendance.why.alternate', defaultMessage: 'an alternate, who does not count here' },
  whyAway: { id: 'meetingAttendance.why.away', defaultMessage: 'not at the meeting' },
  serverCounts: {
    id: 'meetingAttendance.byItem.serverCounts',
    defaultMessage: 'The result is the one the quorum rule gives; the names show who is in the room.',
  },
  // The members' summary during the meeting
  atTheMeeting: { id: 'meetingAttendance.summary.title', defaultMessage: 'At the meeting' },
  summaryQuorate: { id: 'meetingAttendance.summary.quorate', defaultMessage: 'The meeting is quorate.' },
  summaryWaiting: { id: 'meetingAttendance.summary.waiting', defaultMessage: 'The meeting is waiting for quorum.' },
  youPresentInPerson: {
    id: 'meetingAttendance.you.inPerson',
    defaultMessage: 'You are recorded as present (in person).',
  },
  youPresentOnline: { id: 'meetingAttendance.you.online', defaultMessage: 'You are recorded as present (online).' },
  youApologies: { id: 'meetingAttendance.you.apologies', defaultMessage: 'You sent your apologies.' },
  youAbsent: { id: 'meetingAttendance.you.absent', defaultMessage: 'You are recorded as absent.' },
  youLeftNoTime: { id: 'meetingAttendance.you.leftNoTime', defaultMessage: 'You are recorded as having left.' },
  youExpected: {
    id: 'meetingAttendance.you.expected',
    defaultMessage: 'You are expected. The secretary records you when you arrive.',
  },
  // The forecast (flow W1)
  forecastUnavailable: {
    id: 'meetingAttendance.forecast.unavailable',
    defaultMessage: 'The quorum forecast cannot be shown right now.',
  },
  // The override (flow Y3)
  overrideTitle: { id: 'meetingAttendance.override.title', defaultMessage: 'Record without quorum' },
  overrideReason: { id: 'meetingAttendance.override.reason', defaultMessage: 'Reason' },
  overrideReasonRequired: {
    id: 'meetingAttendance.override.reasonRequired',
    defaultMessage: 'Give the reason for recording this decision without a quorum.',
  },
  overrideTold: {
    id: 'meetingAttendance.override.told',
    defaultMessage: 'The chair and the other secretaries will be told.',
  },
  overrideSubmit: { id: 'meetingAttendance.override.submit', defaultMessage: 'Record without quorum' },
  overrideRecused: {
    id: 'meetingAttendance.override.recused',
    defaultMessage: 'You declared an interest in this item, so you cannot record its decision without a quorum.',
  },
  overrideNotQuorate: {
    id: 'meetingAttendance.override.notQuorate',
    defaultMessage: 'The item is not quorate and no reason reached the server. Please try again.',
  },
  overrideForbidden: {
    id: 'meetingAttendance.override.forbidden',
    defaultMessage: 'Only the secretaries can record a decision without a quorum.',
  },
})

/** Messages with values. */
export const amv = defineMessages<{
  item: { number: string }
  itemStart: { number: string }
  numberTitle: { number: string; title: string }
  registerTitle: { name: string }
  now: { item: string }
  nowDone: { item: string }
  countsLine: { inPerson: number; remote: number; apologies: number; expected: number; left: number; absent: number }
  inPersonSince: { at: string }
  onlineSince: { at: string }
  leftAt: { at: string }
  leftDuring: { at: string; item: string }
  joinedDuring: { item: string }
  alternateFor: { name: string }
  standsIn: { name: string }
  steppedOutFor: { item: string }
  actionFor: { action: string; name: string }
  moreFor: { name: string }
  arrivedInPerson: { name: string }
  arrivedOnline: { name: string }
  apologiesDone: { name: string }
  absentDone: { name: string }
  leftDone: { name: string }
  backDone: { name: string }
  steppedOutDone: { name: string }
  bulkAction: { count: number }
  bulkConfirm: { count: number; inPerson: number; remote: number }
  bulkSubmit: { count: number }
  bulkDone: { count: number }
  history: { count: number }
  historyLine: { name: string; entry: string }
  stateNotes: { state: string; notes: string }
  recordedByAt: { name: string; at: string }
  correctionNote: { reason: string }
  voidedBy: { name: string; at: string; reason: string }
  eventArrived: { mode: string; at: string }
  eventArrivedDuring: { mode: string; at: string; item: string }
  eventModeChanged: { mode: string; at: string }
  eventLeft: { at: string }
  eventLeftDuring: { at: string; item: string }
  eventSteppedOut: { item: string }
  eventSteppedBack: { item: string }
  laterTitle: { name: string }
  correctionTitle: { name: string }
  correctTitle: { name: string }
  correctWhatHint: { name: string }
  voidedNotRecorded: { problem: string }
  ALREADY_COUNTED: { name: string }
  stale: { name: string }
  meetingQuorate: { present: number; basis: number; needed: number }
  meetingWaiting: { present: number; basis: number; needed: number }
  lostSince: { at: string; present: number; basis: number; needed: number }
  lostSinceDuring: { at: string; item: string; present: number; basis: number; needed: number }
  announceLost: { present: number; basis: number; needed: number }
  announceRestored: { present: number; basis: number; needed: number }
  itemQuorate: { item: string; present: number; basis: number; needed: number }
  itemNotQuorate: { item: string; present: number; basis: number; needed: number }
  itemDeclared: { item: string; count: number }
  unmetCount: { needed: number; actual: number }
  unmetRole: { roles: string }
  unmetComposition: { label: string; needed: number; actual: number }
  unmetMajority: { label: string; needed: number; actual: number }
  byItemHeading: { item: string }
  needed: { needed: number; basis: number }
  countsOnly: { present: number; needed: number }
  countedLine: { count: number; names: string }
  alternateForSeat: { name: string; member: string }
  nameNotes: { name: string; notes: string }
  notCountedLine: { name: string; why: string }
  resultQuorate: { present: number; needed: number }
  resultNotQuorate: { present: number; needed: number }
  youLeft: { at: string }
  youStandIn: { name: string }
  forecastQuorate: { present: number; basis: number; needed: number }
  forecastNotQuorate: { present: number; basis: number; needed: number }
  forecastItem: { number: string; present: number; needed: number }
  forecastItemDeclared: { number: string; present: number; needed: number; declared: number }
  noDecisionNotQuorate: { present: number; needed: number }
  overrideNotQuorate: { present: number; needed: number }
  overrideReasonHint: { max: number }
  overrideTooLong: { max: number }
}>({
  item: { id: 'meetingAttendance.item', defaultMessage: 'item {number}' },
  itemStart: { id: 'meetingAttendance.itemStart', defaultMessage: 'Item {number}' },
  numberTitle: { id: 'meetingAttendance.numberTitle', defaultMessage: '{number} {title}' },
  registerTitle: { id: 'meetingAttendance.register.title', defaultMessage: 'Attendance — {name}' },
  now: { id: 'meetingAttendance.now', defaultMessage: 'Now: item {item}' },
  nowDone: { id: 'meetingAttendance.now.done', defaultMessage: 'Now: item {item}.' },
  countsLine: {
    id: 'meetingAttendance.counts',
    defaultMessage:
      '{inPerson} in person · {remote} online · {apologies} {apologies, plural, one {apology} other {apologies}} · {expected} expected · {left} left · {absent} absent',
  },
  inPersonSince: { id: 'meetingAttendance.state.inPersonSince', defaultMessage: 'In person since {at}' },
  onlineSince: { id: 'meetingAttendance.state.onlineSince', defaultMessage: 'Online since {at}' },
  leftAt: { id: 'meetingAttendance.state.leftAt', defaultMessage: 'Left at {at}' },
  leftDuring: { id: 'meetingAttendance.state.leftDuring', defaultMessage: 'Left at {at}, during {item}' },
  joinedDuring: { id: 'meetingAttendance.joinedDuring', defaultMessage: 'joined during {item} (late)' },
  alternateFor: { id: 'meetingAttendance.role.alternateFor', defaultMessage: 'Alternate for {name}' },
  standsIn: { id: 'meetingAttendance.standsIn', defaultMessage: '{name} attends in their place' },
  steppedOutFor: { id: 'meetingAttendance.steppedOutFor', defaultMessage: 'Stepped out for {item}' },
  actionFor: { id: 'meetingAttendance.actionFor', defaultMessage: '{action}: {name}' },
  moreFor: { id: 'meetingAttendance.moreFor', defaultMessage: 'More for {name}' },
  arrivedInPerson: { id: 'meetingAttendance.done.inPerson', defaultMessage: '{name} is present in person.' },
  arrivedOnline: { id: 'meetingAttendance.done.online', defaultMessage: '{name} is present online.' },
  apologiesDone: { id: 'meetingAttendance.done.apologies', defaultMessage: '{name}: apologies recorded.' },
  absentDone: { id: 'meetingAttendance.done.absent', defaultMessage: '{name}: recorded as absent.' },
  leftDone: { id: 'meetingAttendance.done.left', defaultMessage: '{name} left.' },
  backDone: { id: 'meetingAttendance.done.back', defaultMessage: '{name} is back in the room.' },
  steppedOutDone: { id: 'meetingAttendance.done.steppedOut', defaultMessage: '{name} stepped out for this item.' },
  bulkAction: {
    id: 'meetingAttendance.bulk.action',
    defaultMessage: 'Mark everyone who said yes as present ({count})',
  },
  bulkConfirm: {
    id: 'meetingAttendance.bulk.confirm',
    defaultMessage:
      'Mark {count, plural, one {# person} other {# people}} present in the way they said ({inPerson} in person, {remote} online)? You can change anyone afterwards.',
  },
  bulkSubmit: {
    id: 'meetingAttendance.bulk.submit',
    defaultMessage: 'Mark {count, plural, one {# person} other {# people}} present',
  },
  bulkDone: {
    id: 'meetingAttendance.bulk.done',
    defaultMessage: '{count, plural, one {# person is} other {# people are}} marked present.',
  },
  history: {
    id: 'meetingAttendance.history',
    defaultMessage: 'History: arrivals, departures and corrections ({count})',
  },
  historyLine: { id: 'meetingAttendance.history.line', defaultMessage: '{name}: {entry}' },
  stateNotes: { id: 'meetingAttendance.stateNotes', defaultMessage: '{state} · {notes}' },
  recordedByAt: { id: 'meetingAttendance.history.recordedBy', defaultMessage: 'Recorded by {name} at {at}' },
  correctionNote: { id: 'meetingAttendance.history.correction', defaultMessage: 'Correction: “{reason}”' },
  voidedBy: {
    id: 'meetingAttendance.history.voidedBy',
    defaultMessage: 'Voided by {name} at {at}: “{reason}”',
  },
  eventArrived: { id: 'meetingAttendance.event.arrived', defaultMessage: 'Arrived {mode} at {at}' },
  eventArrivedDuring: {
    id: 'meetingAttendance.event.arrivedDuring',
    defaultMessage: 'Arrived {mode} at {at}, during {item}',
  },
  eventModeChanged: { id: 'meetingAttendance.event.modeChanged', defaultMessage: 'Changed to {mode} at {at}' },
  eventLeft: { id: 'meetingAttendance.event.left', defaultMessage: 'Left at {at}' },
  eventLeftDuring: { id: 'meetingAttendance.event.leftDuring', defaultMessage: 'Left at {at}, during {item}' },
  eventSteppedOut: { id: 'meetingAttendance.event.steppedOut', defaultMessage: 'Stepped out for {item}' },
  eventSteppedBack: { id: 'meetingAttendance.event.steppedBack', defaultMessage: 'Came back after {item}' },
  laterTitle: { id: 'meetingAttendance.later.title', defaultMessage: 'At another time — {name}' },
  correctionTitle: { id: 'meetingAttendance.correction.title', defaultMessage: 'Record a missed entry for {name}' },
  correctTitle: { id: 'meetingAttendance.correct.title', defaultMessage: 'Correct an entry for {name}' },
  correctWhatHint: {
    id: 'meetingAttendance.correct.whatHint',
    defaultMessage: 'For example “{name} arrived at 10:20, during item 2”. At most 500 characters.',
  },
  voidedNotRecorded: {
    id: 'meetingAttendance.correct.voidedNotRecorded',
    defaultMessage: 'The wrong entry is voided, but the right one is not recorded yet: {problem}',
  },
  ALREADY_COUNTED: {
    id: 'meetingAttendance.problem.alreadyCountedFor',
    defaultMessage: '{name} is already counted at this meeting, as a member or for another seat.',
  },
  stale: {
    id: 'meetingAttendance.problem.stale',
    defaultMessage:
      '{name}’s attendance was changed meanwhile, perhaps by another secretary. It is shown as it is now: try again.',
  },
  meetingQuorate: {
    id: 'meetingAttendance.quorum.quorate',
    defaultMessage: 'Quorate — meeting: {present} of {basis} present, {needed} needed.',
  },
  meetingWaiting: {
    id: 'meetingAttendance.quorum.waiting',
    defaultMessage: 'Not quorate — the meeting is waiting for quorum: {present} of {basis} present, {needed} needed.',
  },
  lostSince: {
    id: 'meetingAttendance.quorum.lostSince',
    defaultMessage: 'The meeting is not quorate since {at}: {present} of {basis} present, {needed} needed.',
  },
  lostSinceDuring: {
    id: 'meetingAttendance.quorum.lostSinceDuring',
    defaultMessage:
      'The meeting is not quorate since {at} (during {item}): {present} of {basis} present, {needed} needed.',
  },
  announceLost: {
    id: 'meetingAttendance.quorum.announceLost',
    defaultMessage:
      'Quorum lost: {present} of {basis} present, {needed} needed. No decision can be recorded until quorum is restored.',
  },
  announceRestored: {
    id: 'meetingAttendance.quorum.announceRestored',
    defaultMessage: 'Quorum restored: {present} of {basis} present, {needed} needed.',
  },
  itemQuorate: {
    id: 'meetingAttendance.quorum.itemQuorate',
    defaultMessage: '{item}: quorate — {present} of {basis} counted, {needed} needed.',
  },
  itemNotQuorate: {
    id: 'meetingAttendance.quorum.itemNotQuorate',
    defaultMessage: '{item}: not quorate — {present} of {basis} counted, {needed} needed.',
  },
  itemDeclared: {
    id: 'meetingAttendance.quorum.itemDeclared',
    defaultMessage:
      '{item} is not quorate: {count, plural, one {# member has declared an interest and does not count} other {# members have declared an interest and do not count}} for it.',
  },
  unmetCount: { id: 'meetingAttendance.unmet.count', defaultMessage: 'Needs {needed} present, has {actual}.' },
  unmetRole: { id: 'meetingAttendance.unmet.role', defaultMessage: 'Needs {roles} present.' },
  unmetComposition: {
    id: 'meetingAttendance.unmet.composition',
    defaultMessage: 'Needs at least {needed} {label} members, has {actual}.',
  },
  unmetMajority: {
    id: 'meetingAttendance.unmet.majority',
    defaultMessage: 'Needs a majority of {label} members: {needed} needed, has {actual}.',
  },
  byItemHeading: { id: 'meetingAttendance.byItem.heading', defaultMessage: 'Quorum for item {item}' },
  needed: { id: 'meetingAttendance.byItem.needed', defaultMessage: 'Needed: {needed} of {basis}.' },
  countsOnly: {
    id: 'meetingAttendance.byItem.countsOnly',
    defaultMessage:
      '{present} counted, {needed} needed. You declared an interest in this item, so the names are not shown to you.',
  },
  countedLine: { id: 'meetingAttendance.byItem.counted', defaultMessage: 'Counted ({count}): {names}' },
  alternateForSeat: { id: 'meetingAttendance.byItem.alternateFor', defaultMessage: '{name} (alternate for {member})' },
  nameNotes: { id: 'meetingAttendance.byItem.nameNotes', defaultMessage: '{name} ({notes})' },
  notCountedLine: { id: 'meetingAttendance.byItem.notCountedLine', defaultMessage: '{name} — {why}' },
  resultQuorate: {
    id: 'meetingAttendance.byItem.resultQuorate',
    defaultMessage: 'Result: quorate — {present} counted, {needed} needed.',
  },
  resultNotQuorate: {
    id: 'meetingAttendance.byItem.resultNotQuorate',
    defaultMessage: 'Result: not quorate — {present} of {needed} needed.',
  },
  youLeft: { id: 'meetingAttendance.you.left', defaultMessage: 'You are recorded as having left at {at}.' },
  youStandIn: { id: 'meetingAttendance.you.standIn', defaultMessage: 'You are attending in place of {name}.' },
  forecastQuorate: {
    id: 'meetingAttendance.forecast.quorate',
    defaultMessage: 'If everyone who said yes attends: quorate — {present} of {basis} members, {needed} needed.',
  },
  forecastNotQuorate: {
    id: 'meetingAttendance.forecast.notQuorate',
    defaultMessage: 'If everyone who said yes attends: not quorate — {present} of {basis} members, {needed} needed.',
  },
  forecastItem: {
    id: 'meetingAttendance.forecast.item',
    defaultMessage: 'Item {number}: not quorate even then — {present} of {needed} needed.',
  },
  forecastItemDeclared: {
    id: 'meetingAttendance.forecast.itemDeclared',
    defaultMessage:
      'Item {number}: not quorate even then — {present} of {needed} needed ({declared, plural, one {# member has} other {# members have}} declared an interest).',
  },
  noDecisionNotQuorate: {
    id: 'meetingAttendance.override.noDecision',
    defaultMessage: 'No decision can be recorded: the item is not quorate ({present} of {needed} needed).',
  },
  overrideNotQuorate: {
    id: 'meetingAttendance.override.description',
    defaultMessage: 'This item is not quorate: {present} of {needed} needed.',
  },
  overrideReasonHint: {
    id: 'meetingAttendance.override.reasonHint',
    defaultMessage: 'For example “Urgent: the borrower’s facility expires on 15 December”. At most {max} characters.',
  },
  overrideTooLong: {
    id: 'meetingAttendance.override.tooLong',
    defaultMessage: 'Please keep the reason to {max} characters.',
  },
})

/** Messages that name the committee with the organisation's terms. */
export const amt = defineMessages<Record<'OVERRIDE_NOT_ALLOWED' | 'overrideRules', TermValues>>({
  OVERRIDE_NOT_ALLOWED: {
    id: 'meetingAttendance.override.notAllowed',
    defaultMessage: 'The {forum}’s rules do not allow a decision without a quorum.',
  },
  overrideRules: {
    id: 'meetingAttendance.override.rules',
    defaultMessage:
      'The {forum}’s rules allow a decision without a quorum only with a reason, and it must be ratified at a later quorate meeting.',
  },
})
