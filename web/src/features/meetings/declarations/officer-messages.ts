import { defineMessages } from 'react-intl'

/**
 * Texts of managing declarations (Phase 4 E04 T7, flow T): the Declarations tab for the committee's officers and its
 * chair or vice-chair, recording a declaration for someone, the chair's decision (or a secretary recording it), an
 * officer's withdrawal, quorum warnings and memo flags, and the builder's recused rows and preview banner; and the
 * register across committees (T8, flow U). All in the `declarations` catalogue part, which loads on `/meetings` and
 * `/declarations` pages.
 */
export const om = defineMessages({
  tab: { id: 'declarations.officer.tab', defaultMessage: 'Declarations' },
  notPublished: {
    id: 'declarations.officer.notPublished',
    defaultMessage: 'The agenda is not published yet. Members review it for interests once it is.',
  },
  everyoneReviewed: {
    id: 'declarations.officer.everyoneReviewed',
    defaultMessage: 'Everyone has reviewed the latest agenda.',
  },
  remind: { id: 'declarations.officer.remind', defaultMessage: 'Remind them now' },
  remindedNone: {
    id: 'declarations.officer.remindedNone',
    defaultMessage: 'Nobody was reminded: everyone has reviewed the agenda or was already reminded of this revision.',
  },
  none: { id: 'declarations.officer.none', defaultMessage: 'No declarations of interest yet.' },
  youRecused: {
    id: 'declarations.officer.youRecused',
    defaultMessage: 'You declared an interest in this item, so only your own declaration is shown.',
  },
  stateRecused: { id: 'declarations.officer.state.recused', defaultMessage: 'Leaving the room' },
  stateAwaiting: { id: 'declarations.officer.state.awaiting', defaultMessage: 'Waiting for a decision' },
  stateTakingPart: { id: 'declarations.officer.state.takingPart', defaultMessage: 'Taking part' },
  stateWithdrawn: { id: 'declarations.officer.state.withdrawn', defaultMessage: 'Withdrawn' },
  originMemo: { id: 'declarations.officer.originMemo', defaultMessage: 'Carried over from the memo' },
  originMeeting: { id: 'declarations.officer.originMeeting', defaultMessage: 'Carried over from an earlier meeting' },
  correction: {
    id: 'declarations.officer.correction',
    defaultMessage: 'Entered after the meeting, as a correction of the record.',
  },
  noRuler: {
    id: 'declarations.officer.noRuler',
    defaultMessage: 'Nobody can decide: the committee has no chair or vice-chair in office who is free to.',
  },
  waitingYou: { id: 'declarations.officer.waitingYou', defaultMessage: 'Waiting for your decision.' },
  decide: { id: 'declarations.officer.decide', defaultMessage: 'Decide…' },
  changeDecision: { id: 'declarations.officer.changeDecision', defaultMessage: 'Change the decision…' },
  recordRuling: { id: 'declarations.officer.recordRuling', defaultMessage: 'Record the chair’s decision…' },
  withdraw: { id: 'declarations.officer.withdraw', defaultMessage: 'Withdraw…' },
  quorumTip: {
    id: 'declarations.officer.quorumTip',
    defaultMessage: 'Consider inviting alternates, or referring the item to the board.',
  },
  record: { id: 'declarations.officer.record', defaultMessage: 'Record a declaration…' },
  registerLink: {
    id: 'declarations.officer.registerLink',
    defaultMessage: 'Register of declarations for this committee',
  },
  restrictedItem: { id: 'declarations.officer.restrictedItem', defaultMessage: 'Restricted item' },
  // Record a declaration (T2)
  recordTitle: { id: 'declarations.record.title', defaultMessage: 'Record a declaration of interest' },
  recordExplain: {
    id: 'declarations.record.explain',
    defaultMessage:
      'For a declaration someone made to you: at the meeting, in person, by email or by phone. They will be told that it was recorded.',
  },
  correctionTitle: {
    id: 'declarations.record.correctionTitle',
    defaultMessage: 'Correcting the record of a held meeting',
  },
  correctionExplain: {
    id: 'declarations.record.correctionExplain',
    defaultMessage:
      'The meeting has been held. Record a declaration made in the room that was not entered, and say why.',
  },
  member: { id: 'declarations.record.member', defaultMessage: 'Member' },
  chooseMember: { id: 'declarations.record.chooseMember', defaultMessage: 'Choose who made it' },
  memberRequired: {
    id: 'declarations.record.memberRequired',
    defaultMessage: 'Please choose who made the declaration.',
  },
  item: { id: 'declarations.record.item', defaultMessage: 'Item' },
  chooseItem: { id: 'declarations.record.chooseItem', defaultMessage: 'Choose the item' },
  itemRequired: { id: 'declarations.record.itemRequired', defaultMessage: 'Please choose the item.' },
  theirInterest: { id: 'declarations.record.nature', defaultMessage: 'Their interest' },
  theirInterestRequired: {
    id: 'declarations.record.natureRequired',
    defaultMessage: 'Please say what their interest is.',
  },
  how: { id: 'declarations.record.how', defaultMessage: 'How it was made' },
  howAT_MEETING: { id: 'declarations.record.how.atMeeting', defaultMessage: 'At the meeting' },
  howIN_PERSON: { id: 'declarations.record.how.inPerson', defaultMessage: 'In person' },
  howEMAIL: { id: 'declarations.record.how.email', defaultMessage: 'By email' },
  howPHONE: { id: 'declarations.record.how.phone', defaultMessage: 'By phone' },
  howRequired: { id: 'declarations.record.howRequired', defaultMessage: 'Please say how it was made.' },
  note: { id: 'declarations.record.note', defaultMessage: 'Note' },
  noteHint: { id: 'declarations.record.noteHint', defaultMessage: 'For example: email of 8 December.' },
  optional: { id: 'declarations.record.optional', defaultMessage: '(optional)' },
  noteLength: {
    id: 'declarations.record.noteLength',
    defaultMessage: 'Please shorten the note to 200 characters or fewer.',
  },
  theyWill: { id: 'declarations.record.intent', defaultMessage: 'What they will do' },
  theyLeave: { id: 'declarations.record.leave', defaultMessage: 'Leave the room for this item' },
  theyAsk: { id: 'declarations.record.ask', defaultMessage: 'Ask the chair to decide' },
  reason: { id: 'declarations.record.reason', defaultMessage: 'Why the record is being corrected' },
  reasonRequired: {
    id: 'declarations.record.reasonRequired',
    defaultMessage: 'Please say why the record is being corrected.',
  },
  recordSubmit: { id: 'declarations.record.submit', defaultMessage: 'Record' },
  NOT_IN_AUDIENCE: {
    id: 'declarations.record.notInAudience',
    defaultMessage:
      'A declaration can be recorded only for someone this meeting is for, or an officer of the committee.',
  },
  ONE_OPEN: {
    id: 'declarations.record.oneOpen',
    defaultMessage: 'This person already has a declaration on this item. Find it in the list to change or withdraw it.',
  },
  // The chair's decision (T3)
  chairDeclared: {
    id: 'declarations.ruling.chairDeclared',
    defaultMessage: 'The chair declared this interest, so the vice-chair decides.',
  },
  rulingNote: { id: 'declarations.ruling.note', defaultMessage: 'Note' },
  rulingNoteLength: {
    id: 'declarations.ruling.noteLength',
    defaultMessage: 'Please shorten the note to 500 characters or fewer.',
  },
  ruleLeave: { id: 'declarations.ruling.leave', defaultMessage: 'They should leave the room' },
  ruleTakePart: { id: 'declarations.ruling.takePart', defaultMessage: 'They may take part' },
  recordRulingExplain: {
    id: 'declarations.ruling.recordExplain',
    defaultMessage:
      'For a decision the chair gave by email, by phone or in person. It is recorded as theirs, with your name as the one who entered it.',
  },
  decisionLegend: { id: 'declarations.ruling.decision', defaultMessage: 'The decision' },
  decisionRequired: { id: 'declarations.ruling.decisionRequired', defaultMessage: 'Please choose the decision.' },
  givenHow: { id: 'declarations.ruling.how', defaultMessage: 'How it was given' },
  givenHowRequired: { id: 'declarations.ruling.howRequired', defaultMessage: 'Please say how it was given.' },
  SELF_RULING: {
    id: 'declarations.ruling.selfRuling',
    defaultMessage: 'Nobody decides on their own declaration. When the chair declared, the vice-chair decides.',
  },
  NO_CHAIR: {
    id: 'declarations.ruling.noChair',
    defaultMessage: 'There is no chair or vice-chair in office who is free to decide on this declaration.',
  },
  // Withdrawing someone's declaration
  withdrawReason: { id: 'declarations.withdrawFor.reason', defaultMessage: 'Why is it being withdrawn?' },
  // Refusals
  forbidden: {
    id: 'declarations.officer.forbidden',
    defaultMessage: 'Only the committee’s secretaries, its chair and its vice-chair can do this.',
  },
  NOT_PUBLISHED: {
    id: 'declarations.officer.notPublishedProblem',
    defaultMessage: 'Publish the agenda first: members review a published agenda.',
  },
  rateLimited: {
    id: 'declarations.officer.rateLimited',
    defaultMessage: 'Too many changes in a short time. Wait a minute, then try again.',
  },
  stale: {
    id: 'declarations.officer.stale',
    defaultMessage: 'This declaration changed in the meantime. The latest is shown: please try again.',
  },
  loadFailed: {
    id: 'declarations.officer.loadFailed',
    defaultMessage: 'The declarations could not be read. Try again in a moment.',
  },
  // The builder (T7, flow T4)
  builderRecused: {
    id: 'declarations.builder.recused',
    defaultMessage: 'You declared an interest. Another secretary or an administrator handles this item.',
  },
  previewHidden: {
    id: 'declarations.builder.previewHidden',
    defaultMessage: 'Items you declared an interest in are hidden in this preview.',
  },
})

export const omv = defineMessages<{
  numbered: { number: string; title: string }
  item: { name: string }
  revision: { revision: number }
  reviewed: { reviewed: number; total: number }
  notYet: { names: string }
  remindedName: { name: string }
  reminded: { count: number; names: string }
  originSelf: { date: string }
  originRecorded: { name: string; date: string; how: string }
  howNote: { note: string }
  waitingFor: { name: string }
  ruled: { name: string; outcome: string; date: string }
  ruledOnBehalf: { name: string; outcome: string; date: string; secretary: string; how: string }
  rulingNote: { note: string }
  alsoReads: { names: string; count: number }
  quorumCount: { item: string; declared: number; basis: number; needed: number }
  quorumRule: { item: string; declared: number; basis: number }
  decideNamed: { name: string; item: string }
  changeNamed: { name: string; item: string }
  recordRulingNamed: { name: string; item: string }
  withdrawNamed: { name: string; item: string }
  recorded: { name: string }
  rulingTitle: { date: string }
  rulingWho: { name: string; item: string }
  declaredOn: { date: string }
  asks: { name: string }
  ruledDone: { name: string; outcome: string }
  recordRulingTitle: { name: string; item: string }
  rulerIs: { name: string }
  withdrawTitle: { name: string; item: string }
  withdrawExplain: { name: string }
  withdrawn: { name: string }
  previewRecused: { title: string }
}>({
  numbered: { id: 'declarations.officer.numbered', defaultMessage: '{number} {title}' },
  item: { id: 'declarations.officer.item', defaultMessage: 'Item {name}' },
  revision: { id: 'declarations.officer.revision', defaultMessage: 'Revision {revision} published' },
  reviewed: {
    id: 'declarations.officer.reviewed',
    defaultMessage: 'Reviewed: {reviewed} of {total}',
  },
  notYet: { id: 'declarations.officer.notYet', defaultMessage: 'Not yet: {names}' },
  remindedName: { id: 'declarations.officer.remindedName', defaultMessage: '{name} (reminded)' },
  reminded: {
    id: 'declarations.officer.reminded',
    defaultMessage: '{count, plural, one {# person was reminded} other {# people were reminded}}: {names}.',
  },
  originSelf: { id: 'declarations.officer.originSelf', defaultMessage: 'In the app, {date}' },
  originRecorded: {
    id: 'declarations.officer.originRecorded',
    defaultMessage:
      '{how, select, AT_MEETING {At the meeting} IN_PERSON {In person} EMAIL {By email} PHONE {By phone} other {Recorded}}, recorded by {name}, {date}',
  },
  howNote: { id: 'declarations.officer.howNote', defaultMessage: '({note})' },
  waitingFor: { id: 'declarations.officer.waitingFor', defaultMessage: 'Waiting for {name} to decide.' },
  ruled: {
    id: 'declarations.officer.ruled',
    defaultMessage:
      '{name} decided: {outcome, select, TAKE_PART {may take part} other {should leave the room}}, {date}.',
  },
  ruledOnBehalf: {
    id: 'declarations.officer.ruledOnBehalf',
    defaultMessage:
      '{name} decided: {outcome, select, TAKE_PART {may take part} other {should leave the room}}, {date}; recorded by {secretary} ({how, select, AT_MEETING {at the meeting} IN_PERSON {given in person} EMAIL {by email} PHONE {by phone} other {}}).',
  },
  rulingNote: { id: 'declarations.officer.rulingNote', defaultMessage: 'Note: “{note}”' },
  alsoReads: {
    id: 'declarations.officer.alsoReads',
    defaultMessage:
      '{names} also {count, plural, one {reads} other {read}} the memo through the memo’s own access. A declaration withholds the committee’s papers, not the memo: change its access on the memo.',
  },
  quorumCount: {
    id: 'declarations.officer.quorumCount',
    defaultMessage:
      'Even if every member attends, item {item} cannot be decided: {declared} of {basis} members have declared an interest and the committee needs {needed} (members with an interest do not count).',
  },
  quorumRule: {
    id: 'declarations.officer.quorumRule',
    defaultMessage:
      'Even if every member attends, item {item} cannot be decided: with {declared} of {basis} members declaring an interest, the committee’s quorum rule cannot be met.',
  },
  decideNamed: {
    id: 'declarations.officer.decideNamed',
    defaultMessage: 'Decide on {name}’s declaration, item {item}',
  },
  changeNamed: {
    id: 'declarations.officer.changeNamed',
    defaultMessage: 'Change the decision on {name}’s declaration, item {item}',
  },
  recordRulingNamed: {
    id: 'declarations.officer.recordRulingNamed',
    defaultMessage: 'Record the chair’s decision on {name}’s declaration, item {item}',
  },
  withdrawNamed: {
    id: 'declarations.officer.withdrawNamed',
    defaultMessage: 'Withdraw {name}’s declaration, item {item}',
  },
  recorded: { id: 'declarations.record.done', defaultMessage: 'Recorded. {name} has been told.' },
  rulingTitle: {
    id: 'declarations.ruling.title',
    defaultMessage: 'A declaration for {date} — your decision is needed',
  },
  rulingWho: { id: 'declarations.ruling.who', defaultMessage: '{name}, item {item}' },
  declaredOn: { id: 'declarations.ruling.declaredOn', defaultMessage: 'Declared on {date}.' },
  asks: {
    id: 'declarations.ruling.asks',
    defaultMessage: '{name} asks whether they may take part. Until you decide, the papers are withheld from them.',
  },
  ruledDone: {
    id: 'declarations.ruling.done',
    defaultMessage:
      '{outcome, select, TAKE_PART {{name} may take part.} other {{name} will leave the room for this item.}} They have been told.',
  },
  recordRulingTitle: {
    id: 'declarations.ruling.recordTitle',
    defaultMessage: 'Record the chair’s decision — {name}, item {item}',
  },
  rulerIs: { id: 'declarations.ruling.rulerIs', defaultMessage: 'The decision is {name}’s.' },
  withdrawTitle: {
    id: 'declarations.withdrawFor.title',
    defaultMessage: 'Withdraw {name}’s declaration — item {item}?',
  },
  withdrawExplain: {
    id: 'declarations.withdrawFor.explain',
    defaultMessage: '{name}, the secretaries and the chair will be told, and the withdrawal will be in the minutes.',
  },
  withdrawn: {
    id: 'declarations.withdrawFor.done',
    defaultMessage: '{name}’s declaration has been withdrawn. They have been told.',
  },
  previewRecused: {
    id: 'declarations.builder.previewRecused',
    defaultMessage: '{title} — an interest is declared, papers withheld',
  },
})
