import { defineMessages, type IntlShape, type MessageDescriptor } from 'react-intl'

/** Strings of the notification centre, preferences and the email settings (design E06 §8). */
export const nm = defineMessages({
  title: { id: 'notifications.title', defaultMessage: 'Notifications' },
  lead: { id: 'notifications.lead', defaultMessage: 'What happened to your account and your work.' },
  markAll: { id: 'notifications.markAll', defaultMessage: 'Mark all as read' },
  loadMore: { id: 'notifications.loadMore', defaultMessage: 'Show older' },
  empty: {
    id: 'notifications.empty',
    defaultMessage: 'Nothing new. Notifications about your account and work appear here.',
  },

  prefsTitle: { id: 'notifications.prefs.title', defaultMessage: 'Notification settings' },
  prefsLead: {
    id: 'notifications.prefs.lead',
    defaultMessage: 'Choose how you hear about each kind of event. Security notices always reach you.',
  },
  prefsCaption: { id: 'notifications.prefs.caption', defaultMessage: 'Channels per kind of notification' },
  colCategory: { id: 'notifications.prefs.colCategory', defaultMessage: 'Kind' },
  channelInApp: { id: 'notifications.prefs.inApp', defaultMessage: 'In memo' },
  channelEmail: { id: 'notifications.prefs.email', defaultMessage: 'Email' },
  mandatory: { id: 'notifications.prefs.mandatory', defaultMessage: 'Always on, to protect your account' },
  saved: { id: 'notifications.prefs.saved', defaultMessage: 'Notification settings saved.' },
  saveFailed: { id: 'notifications.prefs.saveFailed', defaultMessage: 'That was not saved. Try again.' },
  watchTitle: { id: 'notifications.watch.title', defaultMessage: 'Memos you follow' },
  watchLead: {
    id: 'notifications.watch.lead',
    defaultMessage:
      'Followed memos tell you when a step is decided, a note is added or the memo closes. Anything that needs you always reaches you at once.',
  },
  autoWatch: { id: 'notifications.watch.auto', defaultMessage: 'Follow memos I create or act on' },
  autoWatchHint: {
    id: 'notifications.watch.autoHint',
    defaultMessage: 'You can still follow or unfollow any memo yourself.',
  },
  watchEmail: { id: 'notifications.watch.email', defaultMessage: 'Email me about followed memos' },
  watchEmailDIGEST: { id: 'notifications.watch.email.digest', defaultMessage: 'Daily digest (default)' },
  watchEmailIMMEDIATE: { id: 'notifications.watch.email.immediate', defaultMessage: 'At once' },
  watchEmailOFF: { id: 'notifications.watch.email.off', defaultMessage: 'Never' },
  categorySECURITY: { id: 'notifications.category.security', defaultMessage: 'Security' },
  categoryADMINISTRATION: { id: 'notifications.category.administration', defaultMessage: 'Access and administration' },
  categoryWORKFLOW: { id: 'notifications.category.workflow', defaultMessage: 'Memo approvals' },
  categoryWATCHING: { id: 'notifications.category.watching', defaultMessage: 'Memos you follow' },
  categoryCALENDAR: { id: 'notifications.category.calendar', defaultMessage: 'Meeting invitations' },

  mailTitle: { id: 'mail.title', defaultMessage: 'Email delivery' },
  mailLead: {
    id: 'mail.lead',
    defaultMessage:
      "Send this organisation's email through its own mail server. Without one, the platform's server is used.",
  },
  mailPlatform: { id: 'mail.platform', defaultMessage: "Email goes through the platform's mail server." },
  mailHost: { id: 'mail.host', defaultMessage: 'Mail server' },
  mailPort: { id: 'mail.port', defaultMessage: 'Port' },
  mailTls: { id: 'mail.tls', defaultMessage: 'Encryption' },
  mailTlsStarttls: { id: 'mail.tls.starttls', defaultMessage: 'STARTTLS (usually port 587)' },
  mailTlsTls: { id: 'mail.tls.tls', defaultMessage: 'TLS (usually port 465)' },
  mailTlsNone: { id: 'mail.tls.none', defaultMessage: 'None (test installations only)' },
  mailUsername: { id: 'mail.username', defaultMessage: 'User name' },
  mailPassword: { id: 'mail.password', defaultMessage: 'Password' },
  mailPasswordKeep: { id: 'mail.passwordKeep', defaultMessage: 'A password is stored. Leave empty to keep it.' },
  mailFromAddress: { id: 'mail.fromAddress', defaultMessage: 'Sender address' },
  mailFromName: { id: 'mail.fromName', defaultMessage: 'Sender name' },
  optional: { id: 'mail.optional', defaultMessage: '(optional)' },
  save: { id: 'mail.save', defaultMessage: 'Save' },
  remove: { id: 'mail.remove', defaultMessage: "Use the platform's server" },
  test: { id: 'mail.test', defaultMessage: 'Send a test email to me' },
  testOk: { id: 'mail.testOk', defaultMessage: 'Test email sent. Check your inbox.' },
  mailSaved: { id: 'mail.saved', defaultMessage: 'Mail server saved.' },
  mailRemoved: { id: 'mail.removed', defaultMessage: "Email goes through the platform's server again." },
  logTitle: { id: 'mail.log.title', defaultMessage: 'Recent emails' },
  logCaption: { id: 'mail.log.caption', defaultMessage: 'Delivery of recent emails, newest first' },
  logEmpty: { id: 'mail.log.empty', defaultMessage: 'No emails yet.' },
  colTo: { id: 'mail.log.to', defaultMessage: 'To' },
  colTemplate: { id: 'mail.log.template', defaultMessage: 'Email' },
  colStatus: { id: 'mail.log.status', defaultMessage: 'Status' },
  colAttempts: { id: 'mail.log.attempts', defaultMessage: 'Attempts' },
  colWhen: { id: 'mail.log.when', defaultMessage: 'Queued' },
  statusSENT: { id: 'mail.log.sent', defaultMessage: 'Sent' },
  statusPENDING: { id: 'mail.log.pending', defaultMessage: 'Waiting' },
  statusFAILED: { id: 'mail.log.failed', defaultMessage: 'Failed' },
})

/** Messages with values (typed, so a missing value is a compile error). */
export const nmv = defineMessages<{
  unreadItem: { text: string }
  prefCell: { category: string; channel: string }
  testFailed: { detail: string }
  mailInvalid: { detail: string }
}>({
  unreadItem: { id: 'notifications.unreadItem', defaultMessage: 'Unread: {text}' },
  prefCell: { id: 'notifications.prefs.cell', defaultMessage: '{category}: {channel}' },
  testFailed: { id: 'mail.testFailed', defaultMessage: 'The mail server did not accept the test email ({detail}).' },
  mailInvalid: { id: 'mail.invalid', defaultMessage: 'Check the mail server settings: {detail}' },
})

const kindsWithValues = defineMessages<{
  signInAlert: { device: string }
  roleGranted: { role: string }
  taskAssigned: { reference: string }
  memoReturned: { reference: string }
  memoRejected: { reference: string }
  memoApproved: { reference: string }
  taskReassigned: { reference: string }
  taskBlocked: { reference: string }
  memoWithdrawn: { reference: string }
  memoUpdate: { reference: string }
  memoCopied: { reference: string }
  memoMentioned: { reference: string }
  taskReminder: { reference: string }
  taskOverdue: { reference: string }
  editNotSaved: { name: string }
  memoWatched: { reference: string; count: number; reason: string }
  forumMembershipChanged: { committee: string }
  forumTermEnding: { committee: string; member: string }
  forumSubmissionReceived: { committee: string }
  forumSubmissionTriaged: { committee: string }
  forumSubmissionWaiting: { committee: string }
  meetingCancelled: { committee: string; date: string }
  meetingLatePaper: { committee: string; date: string }
  meetingAgendaPublished: { committee: string; date: string }
  meetingAgendaChanged: { committee: string; date: string }
  meetingItemWithdrawn: { committee: string; date: string }
  meetingChairCommented: { committee: string; date: string }
  meetingDiscussionRequested: { committee: string; date: string }
}>({
  signInAlert: { id: 'notifications.kind.signInAlert', defaultMessage: 'New sign-in to your account from {device}' },
  roleGranted: { id: 'notifications.kind.roleGranted', defaultMessage: 'You were given the role {role}' },
  taskAssigned: {
    id: 'notifications.kind.taskAssigned',
    defaultMessage: 'Memo {reference} is waiting for your approval',
  },
  memoReturned: { id: 'notifications.kind.memoReturned', defaultMessage: 'Memo {reference} was returned to you' },
  memoRejected: { id: 'notifications.kind.memoRejected', defaultMessage: 'Memo {reference} was rejected' },
  memoApproved: { id: 'notifications.kind.memoApproved', defaultMessage: 'Memo {reference} was approved' },
  taskReassigned: {
    id: 'notifications.kind.taskReassigned',
    defaultMessage: 'The approval of memo {reference} was reassigned',
  },
  taskBlocked: { id: 'notifications.kind.taskBlocked', defaultMessage: 'Memo {reference} is waiting for reassignment' },
  memoWithdrawn: { id: 'notifications.kind.memoWithdrawn', defaultMessage: 'Memo {reference} was withdrawn' },
  memoUpdate: { id: 'notifications.kind.memoUpdate', defaultMessage: 'There is news about memo {reference}' },
  memoCopied: {
    id: 'notifications.kind.memoCopied',
    defaultMessage: 'Memo {reference} was copied to you for information',
  },
  memoMentioned: {
    id: 'notifications.kind.memoMentioned',
    defaultMessage: 'You were mentioned in a note on memo {reference}',
  },
  taskReminder: {
    id: 'notifications.kind.taskReminder',
    defaultMessage: 'Reminder: memo {reference} is waiting for your decision',
  },
  taskOverdue: { id: 'notifications.kind.taskOverdue', defaultMessage: 'Memo {reference} is overdue' },
  editNotSaved: {
    id: 'notifications.kind.editNotSaved',
    defaultMessage: 'Your changes to {name} in the browser editor could not be saved',
  },
  memoWatched: {
    id: 'notifications.kind.memoWatched',
    defaultMessage:
      '{count, plural, one {News} other {# updates}} on memo {reference}: {reason, select, INITIATED {you started it} ACTED {you acted on it} MENTIONED {you were mentioned on it} other {you follow it}}',
  },
  forumMembershipChanged: {
    id: 'notifications.kind.forumMembershipChanged',
    defaultMessage: 'The membership of {committee} changed',
  },
  forumTermEnding: {
    id: 'notifications.kind.forumTermEnding',
    defaultMessage: 'The term of {member} on {committee} ends soon',
  },
  forumSubmissionReceived: {
    id: 'notifications.kind.forumSubmissionReceived',
    defaultMessage: 'A paper is waiting for {committee}',
  },
  forumSubmissionTriaged: {
    id: 'notifications.kind.forumSubmissionTriaged',
    defaultMessage: 'News from {committee} on your paper',
  },
  forumSubmissionWaiting: {
    id: 'notifications.kind.forumSubmissionWaiting',
    defaultMessage: 'A paper for {committee} is still waiting for triage',
  },
  meetingCancelled: {
    id: 'notifications.kind.meetingCancelled',
    defaultMessage: 'The meeting of {committee} on {date} was cancelled',
  },
  meetingLatePaper: {
    id: 'notifications.kind.meetingLatePaper',
    defaultMessage: 'A late paper for {committee} on {date} needs your agreement',
  },
  meetingAgendaPublished: {
    id: 'notifications.kind.meetingAgendaPublished',
    defaultMessage: 'The agenda for {committee} on {date} was published',
  },
  meetingAgendaChanged: {
    id: 'notifications.kind.meetingAgendaChanged',
    defaultMessage: 'The agenda for {committee} on {date} has changed',
  },
  meetingItemWithdrawn: {
    id: 'notifications.kind.meetingItemWithdrawn',
    defaultMessage: 'A paper left the published agenda for {committee} on {date}: publish a new revision',
  },
  meetingChairCommented: {
    id: 'notifications.kind.meetingChairCommented',
    defaultMessage: 'The chair commented on the agenda for {committee} on {date}',
  },
  meetingDiscussionRequested: {
    id: 'notifications.kind.meetingDiscussionRequested',
    defaultMessage: 'A member asked to discuss a consent item of {committee} on {date}',
  },
})

const kinds = defineMessages({
  passwordChanged: { id: 'notifications.kind.passwordChanged', defaultMessage: 'Your password was changed' },
  mfaReset: { id: 'notifications.kind.mfaReset', defaultMessage: 'An administrator reset your two-step sign-in' },
  unknown: { id: 'notifications.kind.unknown', defaultMessage: 'Something changed in your account' },
})

/** The values of the meeting kinds: the committee and the meeting's date (never an item's title, ADR-0030). */
function meetingValues(params: Record<string, string>): { committee: string; date: string } {
  return { committee: params.committee ?? '', date: params.date ?? '' }
}

/** The text of a notification of [kind] (ADR-0030); unknown kinds (a newer server) fall back to a generic text. */
export function notificationText(intl: IntlShape, kind: string, params: Record<string, string>): string {
  const reference = { reference: params.reference ?? '' }
  switch (kind) {
    case 'SIGN_IN_ALERT':
      return intl.formatMessage(kindsWithValues.signInAlert, { device: params.device ?? '' })
    case 'ROLE_GRANTED':
      return intl.formatMessage(kindsWithValues.roleGranted, { role: params.role ?? '' })
    case 'TASK_ASSIGNED':
      return intl.formatMessage(kindsWithValues.taskAssigned, reference)
    case 'MEMO_RETURNED':
      return intl.formatMessage(kindsWithValues.memoReturned, reference)
    case 'MEMO_REJECTED':
      return intl.formatMessage(kindsWithValues.memoRejected, reference)
    case 'MEMO_APPROVED':
      return intl.formatMessage(kindsWithValues.memoApproved, reference)
    case 'TASK_REASSIGNED':
      return intl.formatMessage(kindsWithValues.taskReassigned, reference)
    case 'TASK_BLOCKED':
      return intl.formatMessage(kindsWithValues.taskBlocked, reference)
    case 'MEMO_WITHDRAWN':
      return intl.formatMessage(kindsWithValues.memoWithdrawn, reference)
    case 'MEMO_UPDATE':
      return intl.formatMessage(kindsWithValues.memoUpdate, reference)
    case 'MEMO_COPIED':
      return intl.formatMessage(kindsWithValues.memoCopied, reference)
    case 'MEMO_MENTIONED':
      return intl.formatMessage(kindsWithValues.memoMentioned, reference)
    case 'TASK_REMINDER':
      return intl.formatMessage(kindsWithValues.taskReminder, reference)
    case 'TASK_OVERDUE':
      return intl.formatMessage(kindsWithValues.taskOverdue, reference)
    case 'EDIT_NOT_SAVED':
      return intl.formatMessage(kindsWithValues.editNotSaved, { name: params.name ?? '' })
    case 'MEMO_WATCHED':
      return intl.formatMessage(kindsWithValues.memoWatched, {
        reference: params.reference ?? '',
        count: Number(params.count ?? '1'),
        reason: params.reason ?? 'MANUAL',
      })
    case 'FORUM_MEMBERSHIP_CHANGED':
      return intl.formatMessage(kindsWithValues.forumMembershipChanged, { committee: params.committee ?? '' })
    case 'FORUM_TERM_ENDING':
      return intl.formatMessage(kindsWithValues.forumTermEnding, {
        committee: params.committee ?? '',
        member: params.member ?? '',
      })
    case 'FORUM_SUBMISSION_RECEIVED':
      return intl.formatMessage(kindsWithValues.forumSubmissionReceived, { committee: params.committee ?? '' })
    case 'FORUM_SUBMISSION_TRIAGED':
      return intl.formatMessage(kindsWithValues.forumSubmissionTriaged, { committee: params.committee ?? '' })
    case 'FORUM_SUBMISSION_WAITING':
      return intl.formatMessage(kindsWithValues.forumSubmissionWaiting, { committee: params.committee ?? '' })
    case 'MEETING_CANCELLED':
      return intl.formatMessage(kindsWithValues.meetingCancelled, {
        committee: params.committee ?? '',
        date: params.date ?? '',
      })
    case 'MEETING_LATE_PAPER':
      return intl.formatMessage(kindsWithValues.meetingLatePaper, meetingValues(params))
    case 'MEETING_AGENDA_PUBLISHED':
      return intl.formatMessage(kindsWithValues.meetingAgendaPublished, meetingValues(params))
    case 'MEETING_AGENDA_CHANGED':
      return intl.formatMessage(kindsWithValues.meetingAgendaChanged, meetingValues(params))
    case 'MEETING_ITEM_WITHDRAWN':
      return intl.formatMessage(kindsWithValues.meetingItemWithdrawn, meetingValues(params))
    case 'MEETING_CHAIR_COMMENTED':
      return intl.formatMessage(kindsWithValues.meetingChairCommented, meetingValues(params))
    case 'MEETING_DISCUSSION_REQUESTED':
      return intl.formatMessage(kindsWithValues.meetingDiscussionRequested, meetingValues(params))
    case 'PASSWORD_CHANGED':
      return intl.formatMessage(kinds.passwordChanged)
    case 'MFA_RESET':
      return intl.formatMessage(kinds.mfaReset)
    default:
      return intl.formatMessage(kinds.unknown)
  }
}

const categoryMessages: Record<string, MessageDescriptor | undefined> = {
  SECURITY: nm.categorySECURITY,
  ADMINISTRATION: nm.categoryADMINISTRATION,
  WORKFLOW: nm.categoryWORKFLOW,
  WATCHING: nm.categoryWATCHING,
  CALENDAR: nm.categoryCALENDAR,
}

export function categoryLabel(intl: IntlShape, category: string): string {
  const message = categoryMessages[category]
  return message ? intl.formatMessage(message) : category
}

const mailTemplates = defineMessages({
  INVITATION: { id: 'mail.template.invitation', defaultMessage: 'Invitation' },
  PASSWORD_RESET: { id: 'mail.template.passwordReset', defaultMessage: 'Password reset' },
  PASSWORD_CHANGED: { id: 'mail.template.passwordChanged', defaultMessage: 'Password changed' },
  MFA_RESET: { id: 'mail.template.mfaReset', defaultMessage: 'Two-step sign-in reset' },
  SIGN_IN_ALERT: { id: 'mail.template.signInAlert', defaultMessage: 'New sign-in' },
  ROLE_GRANTED: { id: 'mail.template.roleGranted', defaultMessage: 'Role granted' },
  TEST: { id: 'mail.template.test', defaultMessage: 'Test email' },
  TASK_ASSIGNED: { id: 'mail.template.taskAssigned', defaultMessage: 'Memo waiting for approval' },
  MEMO_RETURNED: { id: 'mail.template.memoReturned', defaultMessage: 'Memo returned' },
  MEMO_REJECTED: { id: 'mail.template.memoRejected', defaultMessage: 'Memo rejected' },
  MEMO_APPROVED: { id: 'mail.template.memoApproved', defaultMessage: 'Memo approved' },
  TASK_REASSIGNED: { id: 'mail.template.taskReassigned', defaultMessage: 'Memo task reassigned' },
  TASK_BLOCKED: { id: 'mail.template.taskBlocked', defaultMessage: 'Memo waiting for reassignment' },
  MEMO_WITHDRAWN: { id: 'mail.template.memoWithdrawn', defaultMessage: 'Memo withdrawn' },
  MEMO_UPDATE: { id: 'mail.template.memoUpdate', defaultMessage: 'News about a memo' },
  MEMO_COPIED: { id: 'mail.template.memoCopied', defaultMessage: 'A memo copied to you' },
  MEMO_MENTIONED: { id: 'mail.template.memoMentioned', defaultMessage: 'You were mentioned on a memo' },
  TASK_REMINDER: { id: 'mail.template.taskReminder', defaultMessage: 'Reminder about a memo' },
  TASK_OVERDUE: { id: 'mail.template.taskOverdue', defaultMessage: 'Memo overdue' },
  WATCH_UPDATE: { id: 'mail.template.watchUpdate', defaultMessage: 'News on a memo you follow' },
  WATCH_DIGEST: { id: 'mail.template.watchDigest', defaultMessage: 'Daily summary of followed memos' },
  FORUM_MEMBERSHIP_CHANGED: {
    id: 'mail.template.forumMembershipChanged',
    defaultMessage: 'Committee membership changed',
  },
  FORUM_TERM_ENDING: { id: 'mail.template.forumTermEnding', defaultMessage: 'Term of office ending' },
  FORUM_SUBMISSION_RECEIVED: {
    id: 'mail.template.forumSubmissionReceived',
    defaultMessage: 'Paper sent to a committee',
  },
  FORUM_SUBMISSION_TRIAGED: { id: 'mail.template.forumSubmissionTriaged', defaultMessage: 'Committee news on a paper' },
  FORUM_SUBMISSION_WAITING: { id: 'mail.template.forumSubmissionWaiting', defaultMessage: 'Paper waiting for triage' },
  MEETING_CANCELLED: { id: 'mail.template.meetingCancelled', defaultMessage: 'Meeting cancelled' },
  MEETING_LATE_PAPER: { id: 'mail.template.meetingLatePaper', defaultMessage: 'Late paper for the chair' },
  MEETING_AGENDA_PUBLISHED: { id: 'mail.template.meetingAgendaPublished', defaultMessage: 'Meeting agenda published' },
  MEETING_AGENDA_CHANGED: { id: 'mail.template.meetingAgendaChanged', defaultMessage: 'Meeting agenda changed' },
  MEETING_ITEM_WITHDRAWN: { id: 'mail.template.meetingItemWithdrawn', defaultMessage: 'Item left a published agenda' },
  MEETING_DISCUSSION_REQUESTED: {
    id: 'mail.template.meetingDiscussionRequested',
    defaultMessage: 'Consent item to discuss',
  },
})

const templateMessages: Record<string, MessageDescriptor | undefined> = mailTemplates

export function templateLabel(intl: IntlShape, template: string): string {
  const message = templateMessages[template]
  return message ? intl.formatMessage(message) : template
}

const statusMessages: Record<string, MessageDescriptor | undefined> = {
  SENT: nm.statusSENT,
  PENDING: nm.statusPENDING,
  FAILED: nm.statusFAILED,
}

export function statusLabel(intl: IntlShape, status: string): string {
  const message = statusMessages[status]
  return message ? intl.formatMessage(message) : status
}
