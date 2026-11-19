import { defineMessages } from 'react-intl'

/**
 * Texts of declaring an interest on the meeting page (Phase 4 E04 T6, flow S): "Declare an interest", the declaration
 * sheet, the review checklist and its confirmation, the recused line, "My declaration" and the Declarations standing
 * item. The `declarations` prefix is its own catalogue part, loaded on `/meetings` pages only.
 */
export const dm = defineMessages({
  panelTitle: { id: 'declarations.panel.title', defaultMessage: 'Declarations of interest' },
  prompt: {
    id: 'declarations.panel.prompt',
    defaultMessage: 'Please read the agenda and tell us about any interest.',
  },
  review: { id: 'declarations.panel.review', defaultMessage: 'Review and declare' },
  yours: { id: 'declarations.panel.yours', defaultMessage: 'Your declarations in this meeting' },
  // On an agenda item
  declare: { id: 'declarations.item.declare', defaultMessage: 'Declare an interest' },
  myDeclaration: { id: 'declarations.item.mine', defaultMessage: 'My declaration' },
  lineRecused: {
    id: 'declarations.item.recused',
    defaultMessage: 'You will be asked to leave the room for this item. Its papers are not shown to you.',
  },
  lineAwaiting: {
    id: 'declarations.item.awaiting',
    defaultMessage: 'You asked the chair to decide whether you may take part.',
  },
  lineTakingPart: {
    id: 'declarations.item.takingPart',
    defaultMessage: 'You declared an interest. The chair decided you may take part.',
  },
  standingLabel: { id: 'declarations.standing.label', defaultMessage: 'Declarations made so far' },
  // Declare (S2)
  declareExplain: {
    id: 'declarations.declare.explain',
    defaultMessage:
      'Tell the committee about any interest that could affect, or seem to affect, your judgement on this item. For example: you, a family member or a close associate works for, owns part of, or lends to a company in this item.',
  },
  natureLabel: { id: 'declarations.declare.nature', defaultMessage: 'What is your interest?' },
  natureHint: {
    id: 'declarations.declare.natureHint',
    defaultMessage: 'Only the secretaries, the chair and auditors see this.',
  },
  natureRequired: { id: 'declarations.declare.natureRequired', defaultMessage: 'Please say what your interest is.' },
  natureLength: {
    id: 'declarations.declare.natureLength',
    defaultMessage: 'Please shorten this to 1,000 characters or fewer.',
  },
  intentLegend: { id: 'declarations.declare.intent', defaultMessage: 'What would you like to do?' },
  leave: { id: 'declarations.declare.leave', defaultMessage: 'I will leave the room for this item.' },
  recommended: { id: 'declarations.declare.recommended', defaultMessage: '(recommended)' },
  leaveHint: {
    id: 'declarations.declare.leaveHint',
    defaultMessage: 'You will not receive its papers or take part in its decision.',
  },
  askChair: {
    id: 'declarations.declare.askChair',
    defaultMessage: 'I ask the chair to decide whether I may take part.',
  },
  askChairHint: {
    id: 'declarations.declare.askChairHint',
    defaultMessage: 'Until the chair decides, the papers are withheld from you.',
  },
  submit: { id: 'declarations.declare.submit', defaultMessage: 'Declare' },
  cancel: { id: 'declarations.cancel', defaultMessage: 'Cancel' },
  declaredLeave: {
    id: 'declarations.declare.doneLeave',
    defaultMessage: 'Thank you. The secretary and the chair have been told. You will not see this item’s papers.',
  },
  declaredAsk: {
    id: 'declarations.declare.doneAsk',
    defaultMessage: 'Thank you. The chair has been asked to decide. Until then, you will not see this item’s papers.',
  },
  // Review (S3)
  reviewExplain: {
    id: 'declarations.review.explain',
    defaultMessage: 'For each item, choose only if you have an interest. Items you do not mark need nothing.',
  },
  reviewList: { id: 'declarations.review.list', defaultMessage: 'Items to review' },
  haveInterest: { id: 'declarations.review.haveInterest', defaultMessage: 'I have an interest' },
  declaredLeaving: { id: 'declarations.review.leaving', defaultMessage: 'Declared — leaving the room' },
  declaredAsking: { id: 'declarations.review.asking', defaultMessage: 'Declared — asked the chair to decide' },
  declaredTakingPart: { id: 'declarations.review.takingPart', defaultMessage: 'Declared — taking part' },
  confirm: { id: 'declarations.review.confirm', defaultMessage: 'I have declared my interests in this agenda' },
  confirmHint: {
    id: 'declarations.review.confirmHint',
    defaultMessage: 'You will be asked again only if new items are added.',
  },
  confirmed: { id: 'declarations.review.done', defaultMessage: 'Thank you. Your review of the agenda is recorded.' },
  // My declaration (S4)
  carriedMemo: {
    id: 'declarations.mine.carriedMemo',
    defaultMessage: 'Carried over from the memo, where an interest was recorded for you.',
  },
  carriedMeeting: {
    id: 'declarations.mine.carriedMeeting',
    defaultMessage: 'Carried over from an earlier meeting, where this paper was deferred.',
  },
  choseLeave: { id: 'declarations.mine.choseLeave', defaultMessage: 'You chose: I will leave the room for this item.' },
  choseAsk: {
    id: 'declarations.mine.choseAsk',
    defaultMessage: 'You chose: I ask the chair to decide whether I may take part.',
  },
  awaiting: {
    id: 'declarations.mine.awaiting',
    defaultMessage: 'Waiting for the chair’s decision. The papers are withheld until then.',
  },
  askInstead: { id: 'declarations.mine.askInstead', defaultMessage: 'Ask the chair to decide instead' },
  leaveInstead: { id: 'declarations.mine.leaveInstead', defaultMessage: 'I will leave the room instead' },
  withdrawOpen: { id: 'declarations.mine.withdraw', defaultMessage: 'Withdraw this declaration…' },
  close: { id: 'declarations.close', defaultMessage: 'Close' },
  closed: {
    id: 'declarations.mine.closed',
    defaultMessage: 'This declaration can no longer be changed here. Ask the secretary if something is wrong.',
  },
  askedChair: {
    id: 'declarations.mine.askedChair',
    defaultMessage: 'The chair has been asked to decide. Until then, the papers stay withheld from you.',
  },
  leaving: {
    id: 'declarations.mine.leaving',
    defaultMessage: 'You will leave the room for this item. Its papers are not shown to you.',
  },
  // Withdraw
  withdrawExplain: {
    id: 'declarations.withdraw.explain',
    defaultMessage: 'The secretary and the chair will be told, and the withdrawal will be in the minutes.',
  },
  reasonLabel: { id: 'declarations.withdraw.reason', defaultMessage: 'Why are you withdrawing it?' },
  reasonRequired: {
    id: 'declarations.withdraw.reasonRequired',
    defaultMessage: 'Please say why you are withdrawing it.',
  },
  reasonLength: {
    id: 'declarations.withdraw.reasonLength',
    defaultMessage: 'Please shorten this to 500 characters or fewer.',
  },
  withdrawSubmit: { id: 'declarations.withdraw.submit', defaultMessage: 'Withdraw' },
  withdrawn: {
    id: 'declarations.withdraw.done',
    defaultMessage: 'Your declaration has been withdrawn. The secretary and the chair have been told.',
  },
  // Refusals
  ONE_OPEN: {
    id: 'declarations.problem.oneOpen',
    defaultMessage:
      'You have already declared an interest in this item. Use “My declaration” on the item to change or withdraw it.',
  },
  FROZEN: {
    id: 'declarations.problem.frozen',
    defaultMessage: 'Declarations for this meeting can no longer be changed here. Ask the secretary.',
  },
  WRONG_STATE: {
    id: 'declarations.problem.wrongState',
    defaultMessage: 'This cannot be done now: the meeting or your declaration has changed. The page shows the latest.',
  },
  STALE_REVISION: {
    id: 'declarations.problem.staleRevision',
    defaultMessage:
      'The agenda changed while you were reviewing it. The new items are now listed: look at them, then confirm again.',
  },
  RECUSED: {
    id: 'declarations.problem.recused',
    defaultMessage: 'You have declared an interest in this item, so you cannot do this.',
  },
  forbidden: {
    id: 'declarations.problem.forbidden',
    defaultMessage: 'Only the members, guests and officers of this meeting can declare interests here.',
  },
  notFound: {
    id: 'declarations.problem.notFound',
    defaultMessage:
      'This item is no longer on the agenda, or it is not shown to you. The page shows the latest agenda.',
  },
  stale: {
    id: 'declarations.problem.stale',
    defaultMessage: 'Your declaration changed in the meantime. The latest is shown: please try again.',
  },
  failed: { id: 'declarations.problem.failed', defaultMessage: 'That did not work. Try again in a moment.' },
})

export const dmv = defineMessages<{
  toReview: { count: number }
  newToReview: { count: number }
  reviewed: { revision: number }
  declareNamed: { item: string }
  mineNamed: { item: string }
  declareTitle: { item: string }
  reviewTitle: { date: string }
  reviewSince: { revision: number }
  haveInterestNamed: { item: string }
  declaredNamed: { what: string; item: string }
  mineTitle: { item: string }
  declaredSelf: { date: string }
  recordedFor: { name: string; date: string; how: string }
  howNote: { note: string }
  nature: { nature: string }
  ruledTakePart: { date: string }
  ruledLeave: { date: string }
  rulingNote: { note: string }
  withdrawTitle: { item: string }
  otherRecused: { name: string; item: string }
  otherAwaiting: { name: string; item: string }
  otherTakingPart: { name: string; item: string }
  selfRecused: { item: string }
  selfAwaiting: { item: string }
  selfTakingPart: { item: string }
}>({
  toReview: {
    id: 'declarations.panel.toReview',
    defaultMessage: '{count, plural, one {# item to review} other {# items to review}}',
  },
  newToReview: {
    id: 'declarations.panel.newToReview',
    defaultMessage: '{count, plural, one {# new item to review} other {# new items to review}}',
  },
  reviewed: {
    id: 'declarations.panel.reviewed',
    defaultMessage: 'You confirmed your declarations for revision {revision} of the agenda.',
  },
  declareNamed: { id: 'declarations.item.declareNamed', defaultMessage: 'Declare an interest: {item}' },
  mineNamed: { id: 'declarations.item.mineNamed', defaultMessage: 'My declaration: {item}' },
  declareTitle: { id: 'declarations.declare.title', defaultMessage: 'Declare an interest — {item}' },
  reviewTitle: {
    id: 'declarations.review.title',
    defaultMessage: 'Review the agenda for {date} — declarations of interest',
  },
  reviewSince: {
    id: 'declarations.review.since',
    defaultMessage: 'Only the items added since you confirmed revision {revision} are listed.',
  },
  haveInterestNamed: { id: 'declarations.review.haveInterestNamed', defaultMessage: 'I have an interest: {item}' },
  declaredNamed: { id: 'declarations.review.declaredNamed', defaultMessage: '{what}: {item}' },
  mineTitle: { id: 'declarations.mine.title', defaultMessage: 'Your declaration — {item}' },
  declaredSelf: { id: 'declarations.mine.self', defaultMessage: 'Declared on {date}, by you in the app.' },
  recordedFor: {
    id: 'declarations.mine.recorded',
    defaultMessage:
      'Recorded for you by {name} on {date}, {how, select, AT_MEETING {as declared at the meeting} IN_PERSON {as you told them in person} EMAIL {from your email} PHONE {from your phone call} other {}}.',
  },
  howNote: { id: 'declarations.mine.howNote', defaultMessage: 'Their note: {note}' },
  nature: { id: 'declarations.mine.nature', defaultMessage: '“{nature}”' },
  ruledTakePart: {
    id: 'declarations.mine.ruledTakePart',
    defaultMessage: 'On {date} the chair decided that you may take part.',
  },
  ruledLeave: {
    id: 'declarations.mine.ruledLeave',
    defaultMessage: 'On {date} the chair decided that you should leave the room for this item.',
  },
  rulingNote: { id: 'declarations.mine.rulingNote', defaultMessage: 'The chair’s note: “{note}”' },
  withdrawTitle: { id: 'declarations.withdraw.title', defaultMessage: 'Withdraw your declaration — {item}?' },
  otherRecused: {
    id: 'declarations.standing.otherRecused',
    defaultMessage: '{name} — will leave the room for item {item}',
  },
  otherAwaiting: {
    id: 'declarations.standing.otherAwaiting',
    defaultMessage: '{name} — has asked the chair to decide on item {item}',
  },
  otherTakingPart: {
    id: 'declarations.standing.otherTakingPart',
    defaultMessage: '{name} — declared an interest in item {item} and may take part',
  },
  selfRecused: { id: 'declarations.standing.selfRecused', defaultMessage: 'You — will leave the room for item {item}' },
  selfAwaiting: {
    id: 'declarations.standing.selfAwaiting',
    defaultMessage: 'You — have asked the chair to decide on item {item}',
  },
  selfTakingPart: {
    id: 'declarations.standing.selfTakingPart',
    defaultMessage: 'You — declared an interest in item {item} and may take part',
  },
})
