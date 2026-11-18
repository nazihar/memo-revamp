import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of a committee's papers (Phase 4 E02 T4, flow M1): the Submissions tab, its triage dialogs and cover sheet, and
 * the paper page. Purpose and status labels and the timeline are shared with the memo page (`papers-messages.ts`).
 */
export const sm = defineMessages({
  tab: { id: 'forums.sub.tab', defaultMessage: 'Submissions' },
  show: { id: 'forums.sub.show', defaultMessage: 'Show' },
  waiting: { id: 'forums.sub.filter.waiting', defaultMessage: 'Waiting' },
  accepted: { id: 'forums.sub.filter.accepted', defaultMessage: 'Accepted' },
  deferred: { id: 'forums.sub.filter.deferred', defaultMessage: 'Deferred' },
  closed: { id: 'forums.sub.filter.closed', defaultMessage: 'Closed' },
  emptyWaiting: { id: 'forums.sub.empty.waiting', defaultMessage: 'No papers are waiting for triage.' },
  emptyAccepted: { id: 'forums.sub.empty.accepted', defaultMessage: 'No accepted papers.' },
  emptyDeferred: { id: 'forums.sub.empty.deferred', defaultMessage: 'No deferred papers.' },
  emptyClosed: { id: 'forums.sub.empty.closed', defaultMessage: 'No closed papers yet.' },
  colPurpose: { id: 'forums.sub.col.purpose', defaultMessage: 'Purpose' },
  colMemo: { id: 'forums.sub.col.memo', defaultMessage: 'Memo' },
  colFrom: { id: 'forums.sub.col.from', defaultMessage: 'From' },
  colWaiting: { id: 'forums.sub.col.waiting', defaultMessage: 'Waiting' },
  colFlags: { id: 'forums.sub.col.flags', defaultMessage: 'Flags' },
  colStatus: { id: 'forums.sub.col.status', defaultMessage: 'Status' },
  colActions: { id: 'forums.sub.col.actions', defaultMessage: 'Actions' },
  waitingLong: { id: 'forums.sub.flag.waitingLong', defaultMessage: 'Waiting too long' },
  forInformation: { id: 'forums.sub.flag.information', defaultMessage: 'For information only' },
  resubmitted: { id: 'forums.sub.flag.resubmitted', defaultMessage: 'Sent again' },
  triage: { id: 'forums.sub.triage', defaultMessage: 'Triage' },
  accept: { id: 'forums.sub.accept', defaultMessage: 'Accept…' },
  defer: { id: 'forums.sub.defer', defaultMessage: 'Defer…' },
  returnIt: { id: 'forums.sub.return', defaultMessage: 'Return for more information…' },
  decline: { id: 'forums.sub.decline', defaultMessage: 'Decline…' },
  coverSheet: { id: 'forums.sub.coverSheet', defaultMessage: 'Edit cover sheet…' },
  openPaper: { id: 'forums.sub.open', defaultMessage: 'Open the paper' },
  acceptTitle: { id: 'forums.sub.accept.title', defaultMessage: 'Accept the paper' },
  acceptSubmit: { id: 'forums.sub.accept.submit', defaultMessage: 'Accept' },
  deferTitle: { id: 'forums.sub.defer.title', defaultMessage: 'Defer the paper' },
  deferSubmit: { id: 'forums.sub.defer.submit', defaultMessage: 'Defer' },
  returnTitle: { id: 'forums.sub.return.title', defaultMessage: 'Return the memo for more information' },
  returnSubmit: { id: 'forums.sub.return.submit', defaultMessage: 'Return to the author' },
  declineTitle: { id: 'forums.sub.decline.title', defaultMessage: 'Decline the paper' },
  declineSubmit: { id: 'forums.sub.decline.submit', defaultMessage: 'Decline' },
  referSubmit: { id: 'forums.sub.refer.submit', defaultMessage: 'Refer' },
  coverTitle: { id: 'forums.sub.cover.title', defaultMessage: 'Cover sheet' },
  coverLead: {
    id: 'forums.sub.cover.lead',
    defaultMessage: 'What the meeting sees about this paper. Nothing here changes the memo.',
  },
  save: { id: 'forums.sub.save', defaultMessage: 'Save' },
  targetDate: { id: 'forums.sub.targetDate', defaultMessage: 'Target meeting date' },
  reason: { id: 'forums.sub.reason', defaultMessage: 'Reason' },
  returnHint: { id: 'forums.sub.return.hint', defaultMessage: 'Say what the author must add or change.' },
  reasonHint: { id: 'forums.sub.reason.hint', defaultMessage: 'The memo’s author sees this reason.' },
  purpose: { id: 'forums.sub.purpose', defaultMessage: 'Purpose' },
  resolutionSought: { id: 'forums.sub.resolutionSought', defaultMessage: 'Resolution sought' },
  required: { id: 'forums.sub.required', defaultMessage: 'Give a reason.' },
  choose: { id: 'forums.sub.choose', defaultMessage: 'Choose…' },
  TARGET_IN_PAST: { id: 'forums.sub.problem.targetInPast', defaultMessage: 'Choose today or a later date.' },
  WRONG_STATE: {
    id: 'forums.sub.problem.wrongState',
    defaultMessage: 'Someone has already dealt with this paper. The list now shows where it stands.',
  },
  conflict: {
    id: 'forums.sub.problem.conflict',
    defaultMessage: 'Someone changed this paper meanwhile. The list is up to date now; try again.',
  },
  failed: { id: 'forums.sub.problem.failed', defaultMessage: 'That did not work. Try again.' },
  doneAccepted: { id: 'forums.sub.done.accepted', defaultMessage: 'Paper accepted.' },
  doneDeferred: { id: 'forums.sub.done.deferred', defaultMessage: 'Paper deferred.' },
  doneReturned: { id: 'forums.sub.done.returned', defaultMessage: 'Memo returned to its author.' },
  doneDeclined: { id: 'forums.sub.done.declined', defaultMessage: 'Paper declined.' },
  doneReferred: { id: 'forums.sub.done.referred', defaultMessage: 'Paper referred.' },
  doneSaved: { id: 'forums.sub.done.saved', defaultMessage: 'Cover sheet saved.' },
  sentBy: { id: 'forums.sub.sentBy', defaultMessage: 'Sent by' },
  presenter: { id: 'forums.sub.presenter', defaultMessage: 'Presenter' },
  memo: { id: 'forums.sub.memo', defaultMessage: 'Memo' },
  status: { id: 'forums.sub.status', defaultMessage: 'Status' },
  openMemo: { id: 'forums.sub.openMemo', defaultMessage: 'Open the memo' },
  notFound: { id: 'forums.sub.notFound', defaultMessage: 'This paper does not exist or you may not see it.' },
  infoOnlyLead: {
    id: 'forums.sub.lead.infoOnly',
    defaultMessage: 'The memo was sent for information: its route has moved on, and this does not change the memo.',
  },
})

/** Texts that name the committee in the organisation's words (ADR-0032). */
export const smt = defineMessages<{
  refer: TermValues
  referTitle: TermValues
  referTo: TermValues
  acceptLead: TermValues
  deferLead: TermValues
  returnLead: TermValues
  declineLead: TermValues
  referLead: TermValues
  SAME_FORUM: TermValues
  unknownForum: TermValues
  resolutionHint: TermValues
  back: TermValues
}>({
  refer: { id: 'forums.sub.refer', defaultMessage: '{forumGender, select, other {Refer to another {forum}…}}' },
  referTitle: {
    id: 'forums.sub.refer.title',
    defaultMessage: '{forumGender, select, other {Refer to another {forum}}}',
  },
  referTo: { id: 'forums.sub.refer.to', defaultMessage: '{Forum}' },
  acceptLead: {
    id: 'forums.sub.accept.lead',
    defaultMessage:
      'The paper is ready for a meeting. The memo keeps waiting for the {forum}’s decision, and its author is told.',
  },
  deferLead: {
    id: 'forums.sub.defer.lead',
    defaultMessage:
      'The paper stays with the {forum} for a later meeting. The memo keeps waiting, and its author is told why.',
  },
  returnLead: {
    id: 'forums.sub.return.lead',
    defaultMessage:
      'The memo goes back to its author to complete. They see your reason on the note sheet and can send it again.',
  },
  declineLead: {
    id: 'forums.sub.decline.lead',
    defaultMessage: 'The {forum} will not take this paper. The memo goes back to its author with your reason.',
  },
  referLead: {
    id: 'forums.sub.refer.lead',
    defaultMessage:
      'The paper moves to the {forum} you choose, with your reason. The memo keeps waiting for a decision there.',
  },
  SAME_FORUM: {
    id: 'forums.sub.problem.sameForum',
    defaultMessage: '{forumGender, select, other {Choose another {forum}.}}',
  },
  unknownForum: {
    id: 'forums.sub.problem.unknownForum',
    defaultMessage: '{forumGender, select, other {This {forum} does not exist or is dissolved.}}',
  },
  resolutionHint: {
    id: 'forums.sub.resolutionSought.hint',
    defaultMessage: 'What the {forum} is asked to decide, in a sentence or two.',
  },
  back: { id: 'forums.sub.back', defaultMessage: 'All papers of the {forum}' },
})

/** Texts with values. */
export const smv = defineMessages<{
  tabCount: { count: number }
  filterCount: { label: string; count: number }
  caption: { name: string }
  age: { days: number }
  triageFor: { memo: string }
  title: { name: string }
  deadline: { days: number; mode: string }
  remindedAt: { date: string }
}>({
  tabCount: { id: 'forums.sub.tab.count', defaultMessage: 'Submissions ({count})' },
  filterCount: { id: 'forums.sub.filter.count', defaultMessage: '{label} ({count})' },
  caption: { id: 'forums.sub.caption', defaultMessage: 'Papers sent to {name}' },
  age: { id: 'forums.sub.age', defaultMessage: '{days, plural, =0 {Today} one {# day} other {# days}}' },
  triageFor: { id: 'forums.sub.triageFor', defaultMessage: 'Triage {memo}' },
  title: { id: 'forums.sub.title', defaultMessage: 'Paper for {name}' },
  deadline: {
    id: 'forums.sub.deadline',
    defaultMessage:
      'Papers are due {days, plural, =0 {by the day of the meeting} one {# working day before it} other {# working days before it}}. {mode, select, ALLOW {A later paper can still go on the agenda, marked late.} BLOCK {A later paper waits for the next meeting.} other {Late: the chair’s approval will be needed when it goes on the agenda.}}',
  },
  remindedAt: {
    id: 'forums.sub.remindedAt',
    defaultMessage: 'Waiting too long: the secretaries were reminded on {date}.',
  },
})
