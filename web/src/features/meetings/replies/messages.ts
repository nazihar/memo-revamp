import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of invitations and replies (Phase 4 E05 T7, flows V and W): "Will you attend?" on the meeting page and the
 * "My meetings" cards, apologies with the alternate, "Add to my calendar", and the officers' "Invitations and replies"
 * tab with recording an answer and resending. The `meetingReplies` prefix is its own catalogue part, loaded on the home
 * page, `/me/meetings` and `/meetings` pages.
 */
export const rm = defineMessages({
  title: { id: 'meetingReplies.title', defaultMessage: 'Will you attend?' },
  // The answers in the member's own words
  IN_PERSON: { id: 'meetingReplies.answer.inPerson', defaultMessage: 'Yes, in person' },
  REMOTE: { id: 'meetingReplies.answer.remote', defaultMessage: 'Yes, online' },
  APOLOGIES: { id: 'meetingReplies.answer.apologies', defaultMessage: 'No, I send my apologies' },
  sentApologies: { id: 'meetingReplies.sentApologies', defaultMessage: 'You sent your apologies' },
  pleaseReply: { id: 'meetingReplies.pleaseReply', defaultMessage: 'Please reply' },
  replyAgain: { id: 'meetingReplies.replyAgain', defaultMessage: 'Reply again: the time changed' },
  noReply: { id: 'meetingReplies.noReply', defaultMessage: 'You did not reply.' },
  closed: {
    id: 'meetingReplies.closed',
    defaultMessage: 'Answers close when the meeting starts. From then the secretary records who attends.',
  },
  changeAnswer: { id: 'meetingReplies.change', defaultMessage: 'Change my answer' },
  keepAnswer: { id: 'meetingReplies.keep', defaultMessage: 'Keep my answer' },
  saved: { id: 'meetingReplies.saved', defaultMessage: 'Your answer is saved.' },
  apologiesSaved: { id: 'meetingReplies.apologiesSaved', defaultMessage: 'Your apologies are recorded.' },
  // The note
  noteLabel: { id: 'meetingReplies.note.label', defaultMessage: 'A note for the secretary' },
  optional: { id: 'meetingReplies.optional', defaultMessage: '(optional)' },
  noteHint: {
    id: 'meetingReplies.note.hint',
    defaultMessage: 'For example “I will join after 11:00”. At most 300 characters.',
  },
  saveNote: { id: 'meetingReplies.note.save', defaultMessage: 'Save the note' },
  noteSaved: { id: 'meetingReplies.note.saved', defaultMessage: 'Your note is saved.' },
  // Apologies (flow V3)
  nobodyAttends: { id: 'meetingReplies.apologies.nobody', defaultMessage: 'Nobody will attend in my place' },
  sendApologies: { id: 'meetingReplies.apologies.send', defaultMessage: 'Send my apologies' },
  cancel: { id: 'meetingReplies.cancel', defaultMessage: 'Cancel' },
  alternateNotStanding: {
    id: 'meetingReplies.alternate.notStanding',
    defaultMessage: 'You asked your alternate to attend, but nobody stands in for you now. Please tell the secretary.',
  },
  // The calendar
  addToCalendar: { id: 'meetingReplies.calendar', defaultMessage: 'Add to my calendar' },
  calendarHint: {
    id: 'meetingReplies.calendar.hint',
    defaultMessage: 'Your calendar’s Accept and Decline buttons do not reach the secretary: answer here.',
  },
  // Refusals
  WRONG_STATE: {
    id: 'meetingReplies.problem.wrongState',
    defaultMessage: 'Answers are closed: the meeting has started or is no longer scheduled.',
  },
  MODE_NOT_OFFERED: {
    id: 'meetingReplies.problem.modeNotOffered',
    defaultMessage: 'The meeting is no longer held that way. Choose one of the answers shown.',
  },
  ALTERNATE_NONE: {
    id: 'meetingReplies.problem.alternateNone',
    defaultMessage: 'Your seat has no alternate who can attend this meeting.',
  },
  ALTERNATE_WITH_APOLOGIES: {
    id: 'meetingReplies.problem.alternateWithApologies',
    defaultMessage: 'An alternate attends only in place of a member who sends apologies.',
  },
  LENGTH: { id: 'meetingReplies.problem.length', defaultMessage: 'Please make this shorter.' },
  stale: {
    id: 'meetingReplies.problem.stale',
    defaultMessage: 'This answer was changed meanwhile, perhaps by the secretary. It is shown as it is now: try again.',
  },
  forbidden: { id: 'meetingReplies.problem.forbidden', defaultMessage: 'You cannot do this for this meeting.' },
  failed: { id: 'meetingReplies.problem.failed', defaultMessage: 'That did not work. Please try again.' },
  // The officers' tab (flow W)
  tabAgenda: { id: 'meetingReplies.tab.agenda', defaultMessage: 'Agenda' },
  tabReplies: { id: 'meetingReplies.tab.replies', defaultMessage: 'Invitations and replies' },
  tabs: { id: 'meetingReplies.tabs', defaultMessage: 'Parts of the meeting page' },
  notCreated: {
    id: 'meetingReplies.panel.notCreated',
    defaultMessage: 'Invitations are sent when the meeting is scheduled.',
  },
  pending: {
    id: 'meetingReplies.panel.pending',
    defaultMessage: 'A change is waiting to be sent: it goes out within a few minutes.',
  },
  cancelledSent: {
    id: 'meetingReplies.panel.cancelled',
    defaultMessage: 'The meeting is cancelled: everyone invited was sent the cancellation.',
  },
  resendAction: { id: 'meetingReplies.resend.action', defaultMessage: 'Resend…' },
  colName: { id: 'meetingReplies.col.name', defaultMessage: 'Name' },
  colRole: { id: 'meetingReplies.col.role', defaultMessage: 'Role' },
  colReply: { id: 'meetingReplies.col.reply', defaultMessage: 'Reply' },
  colHow: { id: 'meetingReplies.col.how', defaultMessage: 'How' },
  colInvitation: { id: 'meetingReplies.col.invitation', defaultMessage: 'Invitation' },
  colActions: { id: 'meetingReplies.col.actions', defaultMessage: 'Actions' },
  none: { id: 'meetingReplies.none', defaultMessage: '—' },
  nobody: { id: 'meetingReplies.panel.nobody', defaultMessage: 'Nobody is invited to this meeting yet.' },
  // Roles
  MEMBER: { id: 'meetingReplies.role.member', defaultMessage: 'Member' },
  ALTERNATE: { id: 'meetingReplies.role.alternate', defaultMessage: 'Alternate' },
  GUEST: { id: 'meetingReplies.role.guest', defaultMessage: 'Guest' },
  // Replies as officers read them
  replyInPerson: { id: 'meetingReplies.reply.inPerson', defaultMessage: 'Yes, in person' },
  replyRemote: { id: 'meetingReplies.reply.remote', defaultMessage: 'Yes, online' },
  replyApologies: { id: 'meetingReplies.reply.apologies', defaultMessage: 'Apologies' },
  replyApologiesAlternate: {
    id: 'meetingReplies.reply.apologiesAlternate',
    defaultMessage: 'Apologies — alternate attends',
  },
  replyNone: { id: 'meetingReplies.reply.none', defaultMessage: 'Not yet' },
  // How they answered
  IN_APP: { id: 'meetingReplies.how.inApp', defaultMessage: 'in memo' },
  EMAIL: { id: 'meetingReplies.how.email', defaultMessage: 'by email' },
  PHONE: { id: 'meetingReplies.how.phone', defaultMessage: 'by phone' },
  HOW_IN_PERSON: { id: 'meetingReplies.how.inPerson', defaultMessage: 'in person' },
  OTHER: { id: 'meetingReplies.how.other', defaultMessage: 'otherwise' },
  // Invitation states
  notSent: { id: 'meetingReplies.invite.notSent', defaultMessage: 'Not sent yet' },
  notInvitable: {
    id: 'meetingReplies.invite.notInvitable',
    defaultMessage: 'No invitation: they cannot sign in or are not cleared for this meeting',
  },
  appOnly: { id: 'meetingReplies.invite.appOnly', defaultMessage: 'In memo only (invitation emails switched off)' },
  waiting: { id: 'meetingReplies.invite.waiting', defaultMessage: 'Waiting to be sent' },
  INVITATION: { id: 'meetingReplies.kind.invitation', defaultMessage: 'invitation' },
  UPDATE: { id: 'meetingReplies.kind.update', defaultMessage: 'update' },
  RESEND: { id: 'meetingReplies.kind.resend', defaultMessage: 'sent again' },
  UNINVITE: { id: 'meetingReplies.kind.uninvite', defaultMessage: 'invitation withdrawn' },
  CANCEL: { id: 'meetingReplies.kind.cancel', defaultMessage: 'cancellation' },
  // Record an answer (flow W2)
  record: { id: 'meetingReplies.record.action', defaultMessage: 'Record an answer…' },
  recordAnswer: { id: 'meetingReplies.record.answer', defaultMessage: 'Answer' },
  recordAlternate: { id: 'meetingReplies.record.alternate', defaultMessage: 'Their alternate will attend' },
  recordHow: { id: 'meetingReplies.record.how', defaultMessage: 'How did they tell you?' },
  howEmail: { id: 'meetingReplies.record.email', defaultMessage: 'Email' },
  howPhone: { id: 'meetingReplies.record.phone', defaultMessage: 'Phone' },
  howInPerson: { id: 'meetingReplies.record.inPerson', defaultMessage: 'In person' },
  howOther: { id: 'meetingReplies.record.other', defaultMessage: 'Other' },
  recordNote: { id: 'meetingReplies.record.note', defaultMessage: 'Note' },
  recordNoteHint: { id: 'meetingReplies.record.noteHint', defaultMessage: 'For example “called on 9 December”.' },
  recordSubmit: { id: 'meetingReplies.record.submit', defaultMessage: 'Record the answer' },
  recordAnswerRequired: { id: 'meetingReplies.record.answerRequired', defaultMessage: 'Choose the answer.' },
  HOW_REQUIRED: {
    id: 'meetingReplies.problem.howRequired',
    defaultMessage: 'Say how they told you: by email, by phone, in person or otherwise.',
  },
  NOT_INVITABLE: {
    id: 'meetingReplies.problem.notInvitable',
    defaultMessage: 'This person does not answer this meeting. Your own answer is given on the meeting page.',
  },
  recorded: { id: 'meetingReplies.record.done', defaultMessage: 'The answer is recorded.' },
  // Resend (flow W3)
  resendWho: { id: 'meetingReplies.resend.who', defaultMessage: 'Resend the current invitation to' },
  resendSubmit: { id: 'meetingReplies.resend.submit', defaultMessage: 'Resend the invitation' },
  resendPerson: { id: 'meetingReplies.resend.person', defaultMessage: 'Resend the invitation' },
  resendNobody: { id: 'meetingReplies.resend.nobody', defaultMessage: 'Nobody holds the invitation yet.' },
  RESEND_TOO_SOON: {
    id: 'meetingReplies.problem.resendTooSoon',
    defaultMessage: 'An invitation can be resent to a person once every 10 minutes.',
  },
  resendWrongState: {
    id: 'meetingReplies.problem.resendWrongState',
    defaultMessage: 'Invitations are resent only while the meeting is scheduled and after they were first sent.',
  },
  alternateBusyTheirs: {
    id: 'meetingReplies.problem.alternateBusyTheirs',
    defaultMessage: 'Their alternate is already attending in place of another member.',
  },
  rateLimited: {
    id: 'meetingReplies.problem.rateLimited',
    defaultMessage: 'Invitations for this meeting were resent many times this hour. Please try again later.',
  },
})

/** Messages with values. */
export const rmv = defineMessages<{
  youSaid: { answer: string }
  youSaidAt: { answer: string; at: string }
  apologiesAt: { at: string }
  replyBy: { date: string }
  staleWas: { answer: string; when: string }
  staleNow: { when: string }
  standingInFor: { name: string }
  alternateAttends: { name: string }
  yourAlternate: { name: string }
  alternateChoice: { name: string }
  alternateAsked: { name: string }
  recordedBy: { name: string; how: string }
  invitationsSent: { at: string }
  sentWithLast: { at: string; last: string }
  repliesLine: { yes: number; inPerson: number; remote: number; apologies: number; noAnswer: number; stale: number }
  membersLine: { attending: number; total: number }
  alternateFor: { name: string }
  staleReply: { answer: string }
  recordedNote: { name: string; at: string; note: string }
  recordedOn: { name: string; at: string }
  memberNote: { note: string }
  sentOn: { kind: string; at: string }
  failedOn: { kind: string; at: string }
  retrying: { attempts: number }
  withdrawnOn: { at: string }
  cancelSentOn: { at: string }
  actionsFor: { name: string }
  recordTitle: { name: string }
  recordTold: { name: string }
  resendTitle: { name: string }
  resendNotReplied: { count: number }
  resendEveryone: { count: number }
  resendOnly: { name: string }
  resent: { count: number }
  tooSoonOne: { name: string; at: string }
  tooSoonMany: { count: number; at: string }
  ALTERNATE_BUSY: { name: string }
  alternateNoneFor: { name: string }
}>({
  youSaid: { id: 'meetingReplies.youSaid', defaultMessage: 'You said: {answer}' },
  youSaidAt: { id: 'meetingReplies.youSaidAt', defaultMessage: 'You said: {answer} ({at})' },
  apologiesAt: { id: 'meetingReplies.apologiesAt', defaultMessage: 'You sent your apologies ({at})' },
  replyBy: { id: 'meetingReplies.replyBy', defaultMessage: 'Please reply by {date}' },
  staleWas: {
    id: 'meetingReplies.stale.was',
    defaultMessage: 'You replied “{answer}” for the old time ({when}).',
  },
  staleNow: { id: 'meetingReplies.stale.now', defaultMessage: 'The meeting is now on {when}. Will you attend?' },
  standingInFor: { id: 'meetingReplies.standingInFor', defaultMessage: 'You are attending in place of {name}.' },
  alternateAttends: { id: 'meetingReplies.alternate.attends', defaultMessage: '{name} will attend in your place.' },
  yourAlternate: { id: 'meetingReplies.alternate.yours', defaultMessage: 'Your alternate is {name}.' },
  alternateChoice: { id: 'meetingReplies.alternate.choice', defaultMessage: '{name} will attend in my place' },
  alternateAsked: {
    id: 'meetingReplies.alternate.asked',
    defaultMessage:
      'Your apologies are recorded. {name} has been asked to attend in your place and will receive the invitation.',
  },
  recordedBy: { id: 'meetingReplies.recordedBy', defaultMessage: 'Recorded by {name}: you told them {how}.' },
  invitationsSent: { id: 'meetingReplies.panel.sent', defaultMessage: 'Invitations sent {at}' },
  sentWithLast: {
    id: 'meetingReplies.panel.sentWithLast',
    defaultMessage: 'Invitations sent {at} · last message {last}',
  },
  repliesLine: {
    id: 'meetingReplies.panel.replies',
    defaultMessage:
      'Replies: {yes} yes ({inPerson} in person, {remote} online) · {apologies} {apologies, plural, one {apology} other {apologies}} · {noAnswer} not yet · {stale} for the old time',
  },
  membersLine: {
    id: 'meetingReplies.panel.members',
    defaultMessage: 'Members: {attending} of {total} said they will attend.',
  },
  alternateFor: { id: 'meetingReplies.role.alternateFor', defaultMessage: 'Alternate for {name}' },
  staleReply: { id: 'meetingReplies.reply.stale', defaultMessage: '{answer} (for the old time)' },
  recordedNote: {
    id: 'meetingReplies.reply.recordedNote',
    defaultMessage: 'Recorded by {name} on {at}: “{note}”',
  },
  recordedOn: { id: 'meetingReplies.reply.recordedOn', defaultMessage: 'Recorded by {name} on {at}' },
  memberNote: { id: 'meetingReplies.reply.note', defaultMessage: 'Note: “{note}”' },
  sentOn: { id: 'meetingReplies.invite.sentOn', defaultMessage: 'Sent {at} ({kind})' },
  failedOn: { id: 'meetingReplies.invite.failedOn', defaultMessage: 'Not delivered: the {kind} of {at} failed' },
  retrying: {
    id: 'meetingReplies.invite.retrying',
    defaultMessage: 'Not sent yet: {attempts, plural, one {# attempt} other {# attempts}}, trying again',
  },
  withdrawnOn: { id: 'meetingReplies.invite.withdrawnOn', defaultMessage: 'Invitation withdrawn {at}' },
  cancelSentOn: { id: 'meetingReplies.invite.cancelSentOn', defaultMessage: 'Cancellation sent {at}' },
  actionsFor: { id: 'meetingReplies.actionsFor', defaultMessage: 'Actions for {name}' },
  recordTitle: { id: 'meetingReplies.record.title', defaultMessage: 'Record an answer for {name}' },
  recordTold: {
    id: 'meetingReplies.record.told',
    defaultMessage: '{name} will be told that you recorded this answer.',
  },
  resendTitle: { id: 'meetingReplies.resend.title', defaultMessage: 'Resend the invitation to {name}' },
  resendNotReplied: {
    id: 'meetingReplies.resend.notReplied',
    defaultMessage: 'Everyone who has not replied ({count})',
  },
  resendEveryone: { id: 'meetingReplies.resend.everyone', defaultMessage: 'Everyone ({count})' },
  resendOnly: { id: 'meetingReplies.resend.only', defaultMessage: '{name} only' },
  resent: {
    id: 'meetingReplies.resend.done',
    defaultMessage: 'The invitation was sent again to {count, plural, one {# person} other {# people}}.',
  },
  tooSoonOne: {
    id: 'meetingReplies.problem.tooSoonOne',
    defaultMessage: 'The invitation was sent to {name} less than 10 minutes ago. You can resend it after {at}.',
  },
  tooSoonMany: {
    id: 'meetingReplies.problem.tooSoonMany',
    defaultMessage:
      '{count, plural, one {# person} other {# people}} got the invitation less than 10 minutes ago. You can resend to them after {at}.',
  },
  ALTERNATE_BUSY: {
    id: 'meetingReplies.problem.alternateBusy',
    defaultMessage: '{name} is already attending in place of another member.',
  },
  alternateNoneFor: {
    id: 'meetingReplies.problem.alternateNoneFor',
    defaultMessage: '{name}’s seat has no alternate who can attend this meeting.',
  },
})

/** Messages that name the committee with the organisation's terms. */
export const rmt = defineMessages<Record<'ALTERNATE_IS_MEMBER' | 'alternateIsMemberFor', TermValues>>({
  ALTERNATE_IS_MEMBER: {
    id: 'meetingReplies.problem.alternateIsMember',
    defaultMessage: 'Your alternate is also a member of this {forum} and cannot attend in your place.',
  },
  alternateIsMemberFor: {
    id: 'meetingReplies.problem.alternateIsMemberFor',
    defaultMessage: 'Their alternate is also a member of this {forum} and cannot attend in their place.',
  },
})
