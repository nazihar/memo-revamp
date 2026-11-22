import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of recording decisions (Phase 4 E06 T10, flows AA and AB): the officers' Decisions tab on the meeting page (the
 * decision panel, notes, consent sections, corrections and history), what "Mark the meeting as held" says will happen,
 * and the decisions on the members' agenda. The `decisions` prefix is its own catalogue part, loaded on the `/meetings`
 * pages and the committee pages (whose meeting menu marks a meeting as held).
 */
export const dm = defineMessages({
  tab: { id: 'decisions.tab', defaultMessage: 'Decisions' },
  heading: { id: 'decisions.heading', defaultMessage: 'Decisions' },
  agenda: { id: 'decisions.agenda', defaultMessage: 'Agenda' },
  noItems: { id: 'decisions.noItems', defaultMessage: 'The agenda has no items to decide.' },
  notYet: { id: 'decisions.notYet', defaultMessage: 'Not decided yet' },
  consentItems: { id: 'decisions.consentItems', defaultMessage: 'Consent items' },
  lifted: { id: 'decisions.lifted', defaultMessage: '(lifted for discussion)' },
  forDecision: { id: 'decisions.purpose.decision', defaultMessage: 'For decision' },
  forDiscussion: { id: 'decisions.purpose.discussion', defaultMessage: 'For discussion' },
  forInformation: { id: 'decisions.purpose.information', defaultMessage: 'For information' },
  // The form
  decision: { id: 'decisions.decision', defaultMessage: 'Decision' },
  resolutionText: { id: 'decisions.resolutionText', defaultMessage: 'Resolution (as it will be minuted)' },
  decisionText: { id: 'decisions.decisionText', defaultMessage: 'What was decided' },
  optional: { id: 'decisions.optional', defaultMessage: '(optional)' },
  conditions: { id: 'decisions.conditions', defaultMessage: 'Conditions' },
  addCondition: { id: 'decisions.addCondition', defaultMessage: 'Add a condition' },
  validUntil: { id: 'decisions.validUntil', defaultMessage: 'Valid until' },
  deferTo: { id: 'decisions.deferTo', defaultMessage: 'Deferred to' },
  deferNext: { id: 'decisions.defer.next', defaultMessage: 'The next meeting' },
  deferDate: { id: 'decisions.defer.date', defaultMessage: 'A date' },
  deferMeeting: { id: 'decisions.defer.meeting', defaultMessage: 'A meeting already planned' },
  deferDateLabel: { id: 'decisions.defer.dateLabel', defaultMessage: 'Date' },
  deferMeetingLabel: { id: 'decisions.defer.meetingLabel', defaultMessage: 'Meeting' },
  noLaterMeetings: { id: 'decisions.defer.noLater', defaultMessage: 'No later meeting is planned yet.' },
  referReason: { id: 'decisions.referReason', defaultMessage: 'Why it is referred' },
  chooseCommittee: { id: 'decisions.chooseCommittee', defaultMessage: 'Choose…' },
  recordVote: { id: 'decisions.recordVote', defaultMessage: 'Record a vote' },
  votesFor: { id: 'decisions.votesFor', defaultMessage: 'For' },
  votesAgainst: { id: 'decisions.votesAgainst', defaultMessage: 'Against' },
  votesAbstain: { id: 'decisions.votesAbstain', defaultMessage: 'Abstaining' },
  people: { id: 'decisions.people', defaultMessage: 'Dissent and abstentions' },
  peopleHint: {
    id: 'decisions.peopleHint',
    defaultMessage: 'Only the people present who may vote on this item can be named.',
  },
  noVoters: { id: 'decisions.noVoters', defaultMessage: 'Nobody present may vote on this item.' },
  agreed: { id: 'decisions.person.agreed', defaultMessage: 'Agreed' },
  dissented: { id: 'decisions.person.dissented', defaultMessage: 'Dissented' },
  abstained: { id: 'decisions.person.abstained', defaultMessage: 'Abstained' },
  dissentReason: {
    id: 'decisions.dissentReason',
    defaultMessage: 'Reason they asked to be recorded (optional)',
  },
  ratifies: { id: 'decisions.ratifies', defaultMessage: 'This decision ratifies (optional)' },
  ratifiesNone: { id: 'decisions.ratifies.none', defaultMessage: 'Nothing' },
  ratifiesHint: {
    id: 'decisions.ratifiesHint',
    defaultMessage:
      'A decision taken without a quorum is ratified by a later quorate decision that approves or notes it.',
  },
  whenTaken: { id: 'decisions.whenTaken', defaultMessage: 'When was this item taken?' },
  whenTakenHint: {
    id: 'decisions.whenTakenHint',
    defaultMessage: 'On the meeting’s clock. Leave it empty if the item was shown as “Now” during the meeting.',
  },
  correctReason: { id: 'decisions.correctReason', defaultMessage: 'Why are you correcting it?' },
  recheck: { id: 'decisions.recheck', defaultMessage: 'Check the quorum again at the time the item was taken' },
  record: { id: 'decisions.record', defaultMessage: 'Record the decision' },
  correctSubmit: { id: 'decisions.correctSubmit', defaultMessage: 'Correct the decision' },
  recordWithoutQuorum: { id: 'decisions.recordWithoutQuorum', defaultMessage: 'Record without a quorum…' },
  cancel: { id: 'decisions.cancel', defaultMessage: 'Cancel' },
  correct: { id: 'decisions.correct', defaultMessage: 'Correct' },
  history: { id: 'decisions.history', defaultMessage: 'History' },
  hideHistory: { id: 'decisions.hideHistory', defaultMessage: 'Hide the history' },
  recorded: { id: 'decisions.recorded', defaultMessage: 'The decision is recorded.' },
  corrected: { id: 'decisions.corrected', defaultMessage: 'The decision is corrected.' },
  failed: { id: 'decisions.failed', defaultMessage: 'That did not work. Try again.' },
  checkForm: { id: 'decisions.checkForm', defaultMessage: 'Some answers need your attention.' },
  // Field problems in words
  outcomeRequired: { id: 'decisions.outcomeRequired', defaultMessage: 'Choose the decision.' },
  OUTCOME_NOT_OFFERED: {
    id: 'decisions.problem.outcomeNotOffered',
    defaultMessage: 'Choose one of the outcomes offered.',
  },
  TEXT_REQUIRED: {
    id: 'decisions.problem.textRequired',
    defaultMessage: 'Write the resolution as it will be minuted.',
  },
  CONDITIONS_REQUIRED: { id: 'decisions.problem.conditionsRequired', defaultMessage: 'Add at least one condition.' },
  TARGET_REQUIRED: { id: 'decisions.problem.targetRequired', defaultMessage: 'Say where it goes.' },
  SAME_FORUM: { id: 'decisions.problem.sameForum', defaultMessage: 'Choose another committee.' },
  REASON_REQUIRED: { id: 'decisions.problem.reasonRequired', defaultMessage: 'Give a reason.' },
  NOT_PRESENT: { id: 'decisions.problem.notPresent', defaultMessage: 'This person was not present for the item.' },
  NOT_VOTING: { id: 'decisions.problem.notVoting', defaultMessage: 'This person may not vote on this committee.' },
  RECUSED_NAMED: {
    id: 'decisions.problem.recusedNamed',
    defaultMessage: 'This person declared an interest in the item and took no part, so they cannot be named.',
  },
  TOO_MANY_NAMED: {
    id: 'decisions.problem.tooManyNamed',
    defaultMessage: 'More people are named than the vote counts.',
  },
  DUPLICATE: { id: 'decisions.problem.duplicate', defaultMessage: 'Each person can be named once.' },
  BOTH: { id: 'decisions.problem.both', defaultMessage: 'Nobody can both dissent and abstain.' },
  VOTE_MODE: { id: 'decisions.problem.voteMode', defaultMessage: 'Give the counts only with a vote.' },
  TIME_REQUIRED: { id: 'decisions.problem.timeRequired', defaultMessage: 'Say when this item was taken.' },
  TIME_OUT_OF_RANGE: {
    id: 'decisions.problem.timeOutOfRange',
    defaultMessage: 'Give a time during the meeting, not in the future.',
  },
  RATIFY_NOT_QUORATE: {
    id: 'decisions.problem.ratifyNotQuorate',
    defaultMessage: 'Only a decision taken with a quorum ratifies another.',
  },
  OUTCOME: { id: 'decisions.problem.outcome', defaultMessage: 'A ratification approves or notes the decision.' },
  RANGE: { id: 'decisions.problem.range', defaultMessage: 'This value is out of range.' },
  LENGTH: { id: 'decisions.problem.length', defaultMessage: 'This is too long.' },
  unknown: { id: 'decisions.problem.unknown', defaultMessage: 'This is no longer available. Choose again.' },
  invalid: { id: 'decisions.problem.invalid', defaultMessage: 'Check this answer.' },
  // Refusals in words
  RECUSED: {
    id: 'decisions.refusal.recused',
    defaultMessage: 'You declared an interest in this item, so you cannot record its decision.',
  },
  FROZEN: {
    id: 'decisions.refusal.frozen',
    defaultMessage: 'The minutes of this meeting are approved: its decisions can no longer be changed.',
  },
  WRONG_STATE: {
    id: 'decisions.refusal.wrongState',
    defaultMessage: 'Decisions can be recorded only once the meeting has started.',
  },
  RELEASED: {
    id: 'decisions.refusal.released',
    defaultMessage:
      'The memo has already moved on with this outcome. You can correct the wording, the conditions and the vote, not the outcome.',
  },
  NUMBER_TAKEN: {
    id: 'decisions.refusal.numberTaken',
    defaultMessage: 'The resolution number is already taken. Ask an administrator to check the numbering format.',
  },
  NOTHING_TO_DECIDE: {
    id: 'decisions.refusal.nothingToDecide',
    defaultMessage: 'Every item in this section already has a decision or was lifted for discussion.',
  },
  stale: {
    id: 'decisions.refusal.stale',
    defaultMessage:
      'Someone else changed this decision meanwhile. It is shown again as it is now; check it and try again.',
  },
  forbidden: { id: 'decisions.refusal.forbidden', defaultMessage: 'Only the secretaries record decisions.' },
  // The decision as text
  numberedAtEnd: { id: 'decisions.numberedAtEnd', defaultMessage: 'numbered when the meeting ends' },
  toBeRatified: { id: 'decisions.toBeRatified', defaultMessage: 'To be ratified' },
  toBeRatifiedLine: {
    id: 'decisions.toBeRatifiedLine',
    defaultMessage: 'Decided without a quorum, to be ratified at a later quorate meeting.',
  },
  drifted: {
    id: 'decisions.drifted',
    defaultMessage:
      'The attendance was corrected after this decision was recorded. Check the quorum again when you correct it.',
  },
  recusedLine: {
    id: 'decisions.recusedLine',
    defaultMessage: 'You declared an interest in this item and took no part.',
  },
  recusedOfficer: {
    id: 'decisions.recusedOfficer',
    defaultMessage:
      'You declared an interest in this item, so you cannot record its decision or read its notes. Another secretary records it.',
  },
  frozenLine: {
    id: 'decisions.frozenLine',
    defaultMessage: 'The minutes are approved: this decision can no longer be changed.',
  },
  releasedOutcome: {
    id: 'decisions.releasedOutcome',
    defaultMessage: 'To change the outcome, record it in the minutes and ask the author to resubmit the memo.',
  },
  openResolution: { id: 'decisions.openResolution', defaultMessage: 'Open the resolution' },
  // Notes
  notes: { id: 'decisions.notes', defaultMessage: 'Notes for the minutes (only secretaries see these)' },
  notesHint: {
    id: 'decisions.notesHint',
    defaultMessage: 'Saved as you type. They fill “key points discussed” when the draft minutes are made.',
  },
  notesSaving: { id: 'decisions.notes.saving', defaultMessage: 'Saving…' },
  notesSaved: { id: 'decisions.notes.saved', defaultMessage: 'Saved' },
  notesUnsaved: { id: 'decisions.notes.unsaved', defaultMessage: 'Not saved yet' },
  notesFailed: { id: 'decisions.notes.failed', defaultMessage: 'Not saved. It will be tried again as you type.' },
  notesConflict: {
    id: 'decisions.notes.conflict',
    defaultMessage: 'Another secretary changed these notes. Their version is shown; your text was not saved.',
  },
  notesClosed: {
    id: 'decisions.notes.closed',
    defaultMessage: 'Notes can no longer be changed: the minutes have left the draft.',
  },
  // Consent
  decideConsent: { id: 'decisions.consent.decide', defaultMessage: 'Decide the consent items together' },
  decideConsentTitle: { id: 'decisions.consent.title', defaultMessage: 'Decide the consent items together' },
  decideConsentSubmit: { id: 'decisions.consent.submit', defaultMessage: 'Decide together' },
  consentDone: { id: 'decisions.consent.done', defaultMessage: 'The consent items are decided.' },
  consentNothing: { id: 'decisions.consent.nothing', defaultMessage: 'Every consent item already has a decision.' },
  // Correcting while the minutes are under review
  minutesInReview: {
    id: 'decisions.minutesInReview',
    defaultMessage:
      'The minutes are with the chair or the members. Correcting this decision sends them back to the draft.',
  },
  // Mark the meeting as held
  heldNone: { id: 'decisions.held.none', defaultMessage: 'No decision is recorded yet.' },
  heldAfterNow: {
    id: 'decisions.held.afterNow',
    defaultMessage: 'After this, decisions you record will reach their memos at once.',
  },
  heldAfterApproval: {
    id: 'decisions.held.afterApproval',
    defaultMessage:
      'After this, decisions you record are numbered at once; memos move on when the minutes are approved.',
  },
  heldLoading: { id: 'decisions.held.loading', defaultMessage: 'Reading the decisions…' },
  heldTitle: { id: 'decisions.held.title', defaultMessage: 'What happens next' },
  whyDeclared: { id: 'decisions.why.declared', defaultMessage: 'declared an interest' },
  whySteppedOut: { id: 'decisions.why.steppedOut', defaultMessage: 'stepped out' },
  // The members' view
})

/** Texts with values. */
export const dmv = defineMessages<{
  itemLine: { number: string; title: string }
  itemOnly: { number: string }
  now: { item: string }
  stateLine: { outcome: string; extra: string }
  outcomeCaps: { outcome: string }
  outcomeNumber: { outcome: string; number: string }
  resolutionSought: { text: string }
  conditionRemove: { number: number }
  conditionLabel: { number: number }
  validUntilLine: { date: string }
  votersLine: { count: number }
  tooManyVotes: { count: number }
  forNotMore: Record<string, never>
  vote: { for: number; against: number; abstain: number }
  dissent: { name: string }
  dissentReason: { name: string; reason: string }
  abstained: { names: string; count: number }
  tookNoPart: { names: string }
  tookNoPartWhy: { name: string; why: string }
  deferredTo: { target: string }
  deferredNext: Record<string, never>
  ratifiesItem: { meeting: string; item: string; outcome: string }
  quorate: { item: string; present: number; needed: number; basis: number }
  quorateDeclared: { item: string; present: number; needed: number; basis: number; names: string }
  quorumAtTime: { item: string }
  historyRecorded: { time: string; name: string }
  historyCorrected: { time: string; name: string; reason: string }
  historyEntry: { outcome: string }
  correctTitle: { item: string }
  saved: { time: string }
  consentWill: { items: string; outcome: string; count: number }
  consentLifted: { items: string; count: number }
  consentDecided: { items: string; count: number }
  heldDecisions: { count: number }
  heldResolutions: { count: number; items: string }
  heldMemosNow: { count: number; items: string }
  heldMemosLater: { count: number; items: string }
  heldUndecided: { count: number; items: string }
  itemWithOutcome: { item: string; outcome: string }
  itemWords: { number: string }
  notQuorateRefusal: { present: number; needed: number }
}>({
  itemLine: { id: 'decisions.itemLine', defaultMessage: 'Item {number} — {title}' },
  itemOnly: { id: 'decisions.itemOnly', defaultMessage: 'Item {number}' },
  now: { id: 'decisions.now', defaultMessage: 'Now: {item}' },
  stateLine: { id: 'decisions.stateLine', defaultMessage: '{outcome} — {extra}' },
  outcomeCaps: { id: 'decisions.outcomeCaps', defaultMessage: '{outcome}' },
  outcomeNumber: { id: 'decisions.outcomeNumber', defaultMessage: '{outcome} — Resolution {number}' },
  resolutionSought: { id: 'decisions.resolutionSought', defaultMessage: 'Resolution sought: “{text}”' },
  conditionRemove: { id: 'decisions.conditionRemove', defaultMessage: 'Remove condition {number}' },
  conditionLabel: { id: 'decisions.conditionLabel', defaultMessage: 'Condition {number}' },
  validUntilLine: { id: 'decisions.validUntilLine', defaultMessage: 'Valid until {date}.' },
  votersLine: {
    id: 'decisions.votersLine',
    defaultMessage:
      '{count, plural, =0 {No voting member present may vote on this item.} one {# voting member present and able to vote.} other {# voting members present and able to vote.}}',
  },
  tooManyVotes: {
    id: 'decisions.tooManyVotes',
    defaultMessage:
      '{count, plural, =0 {No voting member present may vote on this item.} one {There is only # voting member present who may vote on this item.} other {There are only # voting members present who may vote on this item.}}',
  },
  forNotMore: {
    id: 'decisions.forNotMore',
    defaultMessage:
      'For is not more than Against. Check the counts if this is an approval (a casting vote is allowed).',
  },
  vote: {
    id: 'decisions.vote',
    defaultMessage:
      'Vote: for {for, number}, against {against, number}{abstain, plural, =0 {} other {, abstaining #}}.',
  },
  dissent: { id: 'decisions.dissentLine', defaultMessage: '{name} asked that their dissent be recorded.' },
  dissentReason: {
    id: 'decisions.dissentReasonLine',
    defaultMessage: '{name} asked that their dissent be recorded: {reason}',
  },
  abstained: {
    id: 'decisions.abstainedLine',
    defaultMessage: '{count, plural, one {{names} abstained.} other {{names} abstained.}}',
  },
  tookNoPart: { id: 'decisions.tookNoPartLine', defaultMessage: 'Took no part: {names}.' },
  tookNoPartWhy: { id: 'decisions.tookNoPartWhy', defaultMessage: '{name} ({why})' },
  deferredTo: { id: 'decisions.deferredTo', defaultMessage: 'Deferred to {target}.' },
  deferredNext: { id: 'decisions.deferredNext', defaultMessage: 'Deferred to the next meeting.' },
  ratifiesItem: { id: 'decisions.ratifiesItem', defaultMessage: '{meeting}, item {item}: {outcome}' },
  quorate: {
    id: 'decisions.quorate',
    defaultMessage: 'Quorate for {item}: {present} of {basis} voting members present, {needed} needed.',
  },
  quorateDeclared: {
    id: 'decisions.quorateDeclared',
    defaultMessage:
      'Quorate for {item}: {present} of {basis} voting members present, {needed} needed ({names} declared an interest).',
  },
  quorumAtTime: {
    id: 'decisions.quorumAtTime',
    defaultMessage: 'The quorum for {item} is checked at the time it was taken.',
  },
  historyRecorded: { id: 'decisions.history.recorded', defaultMessage: 'Recorded {time} by {name}' },
  historyCorrected: { id: 'decisions.history.corrected', defaultMessage: 'Corrected {time} by {name}: {reason}' },
  historyEntry: { id: 'decisions.history.entry', defaultMessage: 'Decision: {outcome}' },
  correctTitle: { id: 'decisions.correctTitle', defaultMessage: 'Correct the decision on {item}' },
  saved: { id: 'decisions.notes.savedAt', defaultMessage: 'Saved at {time}' },
  consentWill: {
    id: 'decisions.consent.will',
    defaultMessage: '{count, plural, one {Item {items} will be {outcome}.} other {Items {items} will be {outcome}.}}',
  },
  consentLifted: {
    id: 'decisions.consent.lifted',
    defaultMessage:
      '{count, plural, one {Item {items} was lifted for discussion and is decided on its own.} other {Items {items} were lifted for discussion and are decided on their own.}}',
  },
  consentDecided: {
    id: 'decisions.consent.decided',
    defaultMessage:
      '{count, plural, one {Item {items} already has a decision.} other {Items {items} already have a decision.}}',
  },
  heldDecisions: {
    id: 'decisions.held.decisions',
    defaultMessage: '{count, plural, one {# decision is recorded.} other {# decisions are recorded.}}',
  },
  heldResolutions: {
    id: 'decisions.held.resolutions',
    defaultMessage:
      '{count, plural, =0 {None is a resolution.} one {# is a resolution and will be numbered ({items}).} other {# are resolutions and will be numbered in agenda order ({items}).}}',
  },
  heldMemosNow: {
    id: 'decisions.held.memosNow',
    defaultMessage:
      '{count, plural, one {# memo will move on now: {items}.} other {# memos will move on now: {items}.}}',
  },
  heldMemosLater: {
    id: 'decisions.held.memosLater',
    defaultMessage:
      '{count, plural, one {# memo will move on when the minutes are approved: {items}.} other {# memos will move on when the minutes are approved: {items}.}}',
  },
  heldUndecided: {
    id: 'decisions.held.undecided',
    defaultMessage:
      '{count, plural, one {{items} has no decision. Its memo will keep waiting until you record one.} other {{items} have no decision. Their memos will keep waiting until you record them.}}',
  },
  itemWithOutcome: { id: 'decisions.itemWithOutcome', defaultMessage: '{item} ({outcome})' },
  itemWords: { id: 'decisions.itemWords', defaultMessage: 'item {number}' },
  notQuorateRefusal: {
    id: 'decisions.refusal.notQuorate',
    defaultMessage: 'The item is not quorate: {present} of {needed} needed. You cannot record a decision.',
  },
})

/** Texts with the organisation's words for a committee. */
export const dmt = defineMessages<Record<'referTo' | 'noCommittees', TermValues>>({
  referTo: { id: 'decisions.referTo', defaultMessage: 'Referred to which {forum}?' },
  noCommittees: { id: 'decisions.noCommittees', defaultMessage: 'No other {forum} can take it.' },
})
