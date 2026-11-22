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
  meetingAgendaDue: { committee: string; date: string }
  meetingNotHeld: { committee: string; date: string }
  meetingDeclarationRecorded: { committee: string; date: string; item: string; change: string }
  meetingDeclarationMade: { committee: string; date: string; item: string; change: string }
  meetingRulingNeeded: { committee: string; date: string; item: string }
  meetingDeclarationRuled: { committee: string; date: string; item: string; outcome: string }
  meetingDeclarationsDue: { committee: string; date: string }
  meetingInvitation: { committee: string; date: string }
  meetingUpdated: { committee: string; date: string }
  meetingUninvited: { committee: string; date: string }
  meetingRsvpRecorded: { committee: string; date: string; answer: string; how: string }
  meetingRsvpChanged: { committee: string; date: string; member: string; answer: string }
  meetingAlternateAsked: { committee: string; date: string; member: string }
  meetingAlternateReleased: { committee: string; date: string; member: string }
  meetingQuorumLost: { committee: string; date: string; present: string; needed: string }
  meetingQuorumRestored: { committee: string; date: string; present: string; needed: string }
  meetingQuorumOverridden: { committee: string; date: string; present: string; needed: string }
  meetingReplyReminder: { committee: string; date: string }
  meetingReminder: { committee: string; date: string; unanswered: string; toReview: number }
  meetingQuorumAtRisk: { committee: string; date: string }
  actionAssigned: { committee: string; reference: string; date: string }
  actionSourceCorrected: { committee: string; reference: string }
  actionOwnerChanged: { committee: string; reference: string }
  actionRiskAccepted: { committee: string; reference: string }
  actionCancelled: { committee: string; reference: string }
  actionReopened: { committee: string; reference: string; date: string }
  actionDueRequested: { committee: string; reference: string; date: string }
  actionDueDecided: { committee: string; reference: string; date: string; decision: string }
  actionDueChanged: { committee: string; reference: string; date: string }
  actionDueSoon: { committee: string; reference: string; date: string }
  actionOverdue: { committee: string; reference: string; date: string; stage: string }
  actionToVerify: { committee: string; reference: string }
  actionClosed: { committee: string; reference: string }
  actionNotRunning: { committee: string; reference: string }
  actionEscalated: { committee: string; reference: string; date: string; holder: string }
  actionSentBack: { committee: string; reference: string; date: string }
}>({
  signInAlert: { id: 'notificationKinds.signInAlert', defaultMessage: 'New sign-in to your account from {device}' },
  roleGranted: { id: 'notificationKinds.roleGranted', defaultMessage: 'You were given the role {role}' },
  taskAssigned: {
    id: 'notificationKinds.taskAssigned',
    defaultMessage: 'Memo {reference} is waiting for your approval',
  },
  memoReturned: { id: 'notificationKinds.memoReturned', defaultMessage: 'Memo {reference} was returned to you' },
  memoRejected: { id: 'notificationKinds.memoRejected', defaultMessage: 'Memo {reference} was rejected' },
  memoApproved: { id: 'notificationKinds.memoApproved', defaultMessage: 'Memo {reference} was approved' },
  taskReassigned: {
    id: 'notificationKinds.taskReassigned',
    defaultMessage: 'The approval of memo {reference} was reassigned',
  },
  taskBlocked: { id: 'notificationKinds.taskBlocked', defaultMessage: 'Memo {reference} is waiting for reassignment' },
  memoWithdrawn: { id: 'notificationKinds.memoWithdrawn', defaultMessage: 'Memo {reference} was withdrawn' },
  memoUpdate: { id: 'notificationKinds.memoUpdate', defaultMessage: 'There is news about memo {reference}' },
  memoCopied: {
    id: 'notificationKinds.memoCopied',
    defaultMessage: 'Memo {reference} was copied to you for information',
  },
  memoMentioned: {
    id: 'notificationKinds.memoMentioned',
    defaultMessage: 'You were mentioned in a note on memo {reference}',
  },
  taskReminder: {
    id: 'notificationKinds.taskReminder',
    defaultMessage: 'Reminder: memo {reference} is waiting for your decision',
  },
  taskOverdue: { id: 'notificationKinds.taskOverdue', defaultMessage: 'Memo {reference} is overdue' },
  editNotSaved: {
    id: 'notificationKinds.editNotSaved',
    defaultMessage: 'Your changes to {name} in the browser editor could not be saved',
  },
  memoWatched: {
    id: 'notificationKinds.memoWatched',
    defaultMessage:
      '{count, plural, one {News} other {# updates}} on memo {reference}: {reason, select, INITIATED {you started it} ACTED {you acted on it} MENTIONED {you were mentioned on it} other {you follow it}}',
  },
  forumMembershipChanged: {
    id: 'notificationKinds.forumMembershipChanged',
    defaultMessage: 'The membership of {committee} changed',
  },
  forumTermEnding: {
    id: 'notificationKinds.forumTermEnding',
    defaultMessage: 'The term of {member} on {committee} ends soon',
  },
  forumSubmissionReceived: {
    id: 'notificationKinds.forumSubmissionReceived',
    defaultMessage: 'A paper is waiting for {committee}',
  },
  forumSubmissionTriaged: {
    id: 'notificationKinds.forumSubmissionTriaged',
    defaultMessage: 'News from {committee} on your paper',
  },
  forumSubmissionWaiting: {
    id: 'notificationKinds.forumSubmissionWaiting',
    defaultMessage: 'A paper for {committee} is still waiting for triage',
  },
  meetingCancelled: {
    id: 'notificationKinds.meetingCancelled',
    defaultMessage: 'The meeting of {committee} on {date} was cancelled',
  },
  meetingLatePaper: {
    id: 'notificationKinds.meetingLatePaper',
    defaultMessage: 'A late paper for {committee} on {date} needs your agreement',
  },
  meetingAgendaPublished: {
    id: 'notificationKinds.meetingAgendaPublished',
    defaultMessage: 'The agenda for {committee} on {date} was published',
  },
  meetingAgendaChanged: {
    id: 'notificationKinds.meetingAgendaChanged',
    defaultMessage: 'The agenda for {committee} on {date} has changed',
  },
  meetingItemWithdrawn: {
    id: 'notificationKinds.meetingItemWithdrawn',
    defaultMessage: 'A paper left the published agenda for {committee} on {date}: publish a new revision',
  },
  meetingChairCommented: {
    id: 'notificationKinds.meetingChairCommented',
    defaultMessage: 'The chair commented on the agenda for {committee} on {date}',
  },
  meetingDiscussionRequested: {
    id: 'notificationKinds.meetingDiscussionRequested',
    defaultMessage: 'A member asked to discuss a consent item of {committee} on {date}',
  },
  meetingAgendaDue: {
    id: 'notificationKinds.meetingAgendaDue',
    defaultMessage: 'Papers for {committee} on {date} were due and the agenda is not published',
  },
  meetingNotHeld: {
    id: 'notificationKinds.meetingNotHeld',
    defaultMessage: 'The meeting of {committee} on {date} is not marked as held',
  },
  meetingDeclarationRecorded: {
    id: 'notificationKinds.meetingDeclarationRecorded',
    defaultMessage:
      '{change, select, WITHDRAWN {Your declaration of interest in item {item} of {committee} on {date} was withdrawn} other {A declaration of interest in item {item} of {committee} on {date} was recorded for you}}',
  },
  meetingDeclarationMade: {
    id: 'notificationKinds.meetingDeclarationMade',
    defaultMessage:
      '{change, select, WITHDRAWN {A declaration of interest in item {item} of {committee} on {date} was withdrawn} other {An interest was declared in item {item} of {committee} on {date}}}',
  },
  meetingRulingNeeded: {
    id: 'notificationKinds.meetingRulingNeeded',
    defaultMessage: 'A declaration of interest in item {item} of {committee} on {date} needs your ruling',
  },
  meetingDeclarationRuled: {
    id: 'notificationKinds.meetingDeclarationRuled',
    defaultMessage:
      '{outcome, select, TAKE_PART {The chair ruled that you may take part in item {item} of {committee} on {date}} other {The chair ruled that you leave the room for item {item} of {committee} on {date}}}',
  },
  meetingDeclarationsDue: {
    id: 'notificationKinds.meetingDeclarationsDue',
    defaultMessage: 'Please declare any interests in the agenda for {committee} on {date}',
  },
  meetingInvitation: {
    id: 'notificationKinds.meetingInvitation',
    defaultMessage: 'You are invited to the meeting of {committee} on {date}: tell us whether you will attend',
  },
  meetingUpdated: {
    id: 'notificationKinds.meetingUpdated',
    defaultMessage: 'The meeting of {committee} on {date} has changed',
  },
  meetingUninvited: {
    id: 'notificationKinds.meetingUninvited',
    defaultMessage: 'You are no longer invited to the meeting of {committee} on {date}',
  },
  meetingRsvpRecorded: {
    id: 'notificationKinds.meetingRsvpRecorded',
    defaultMessage:
      'The secretary recorded your answer for {committee} on {date}: {answer, select, IN_PERSON {Yes, in person} REMOTE {Yes, online} other {No, apologies}} ({how, select, EMAIL {by email} PHONE {by phone} IN_PERSON {in person} other {otherwise}})',
  },
  meetingRsvpChanged: {
    id: 'notificationKinds.meetingRsvpChanged',
    defaultMessage:
      '{member} answered for {committee} on {date}: {answer, select, IN_PERSON {Yes, in person} REMOTE {Yes, online} other {No, apologies}}',
  },
  meetingAlternateAsked: {
    id: 'notificationKinds.meetingAlternateAsked',
    defaultMessage: '{member} asked you to attend {committee} on {date} in their place',
  },
  meetingAlternateReleased: {
    id: 'notificationKinds.meetingAlternateReleased',
    defaultMessage: '{member} no longer needs you to attend {committee} on {date} in their place',
  },
  meetingQuorumLost: {
    id: 'notificationKinds.meetingQuorumLost',
    defaultMessage: 'The meeting of {committee} on {date} lost its quorum: {present} of {needed} present',
  },
  meetingQuorumRestored: {
    id: 'notificationKinds.meetingQuorumRestored',
    defaultMessage: 'The meeting of {committee} on {date} is quorate again: {present} of {needed} present',
  },
  meetingQuorumOverridden: {
    id: 'notificationKinds.meetingQuorumOverridden',
    defaultMessage:
      'A decision at the meeting of {committee} on {date} was recorded without a quorum ({present} of {needed} present), to be ratified at a quorate meeting',
  },
  meetingReplyReminder: {
    id: 'notificationKinds.meetingReplyReminder',
    defaultMessage: 'Replies for {committee} on {date} are due today: please tell us whether you will attend',
  },
  meetingReminder: {
    id: 'notificationKinds.meetingReminder',
    defaultMessage:
      '{committee} meets on {date}{unanswered, select, true {. Please tell us whether you will attend} other {}}{toReview, plural, =0 {} one {. You still have # item to review for declarations} other {. You still have # items to review for declarations}}',
  },
  meetingQuorumAtRisk: {
    id: 'notificationKinds.meetingQuorumAtRisk',
    defaultMessage:
      'From the replies so far, the meeting of {committee} on {date} or some of its items may not be quorate',
  },
  actionAssigned: {
    id: 'notificationKinds.actionAssigned',
    defaultMessage: '{committee} has given you an action, due {date} ({reference})',
  },
  actionSourceCorrected: {
    id: 'notificationKinds.actionSourceCorrected',
    defaultMessage: 'The decision that action {reference} of {committee} came from was corrected',
  },
  actionOwnerChanged: {
    id: 'notificationKinds.actionOwnerChanged',
    defaultMessage: 'The owner of action {reference} of {committee} changed',
  },
  actionRiskAccepted: {
    id: 'notificationKinds.actionRiskAccepted',
    defaultMessage: 'Action {reference} of {committee} was cancelled with the risk accepted on your behalf',
  },
  actionCancelled: {
    id: 'notificationKinds.actionCancelled',
    defaultMessage: 'Action {reference} of {committee} was cancelled',
  },
  actionReopened: {
    id: 'notificationKinds.actionReopened',
    defaultMessage: 'Action {reference} of {committee} was reopened, due {date}',
  },
  actionDueRequested: {
    id: 'notificationKinds.actionDueRequested',
    defaultMessage: 'The owner of action {reference} of {committee} asks for a new date: {date}',
  },
  actionDueDecided: {
    id: 'notificationKinds.actionDueDecided',
    defaultMessage:
      'Your request to move action {reference} of {committee} to {date} was {decision, select, AGREED {agreed} other {declined}}',
  },
  actionDueChanged: {
    id: 'notificationKinds.actionDueChanged',
    defaultMessage: 'Action {reference} of {committee} is now due {date}',
  },
  actionDueSoon: {
    id: 'notificationKinds.actionDueSoon',
    defaultMessage: 'Action {reference} of {committee} is due soon, on {date}',
  },
  actionOverdue: {
    id: 'notificationKinds.actionOverdue',
    defaultMessage:
      'Action {reference} of {committee}, due {date}, {stage, select, ESCALATED {is overdue and was escalated} REMINDER {is still overdue} other {is overdue}}',
  },
  actionToVerify: {
    id: 'notificationKinds.actionToVerify',
    defaultMessage: 'Action {reference} of {committee} was marked as done: please verify it',
  },
  actionClosed: {
    id: 'notificationKinds.actionClosed',
    defaultMessage: 'Action {reference} of {committee} was verified and closed',
  },
  actionNotRunning: {
    id: 'notificationKinds.actionNotRunning',
    defaultMessage: 'Action {reference} of {committee} has no running workflow: check it',
  },
  actionEscalated: {
    id: 'notificationKinds.actionEscalated',
    defaultMessage: 'Action {reference} of {committee}, held by {holder}, is overdue since {date}',
  },
  actionSentBack: {
    id: 'notificationKinds.actionSentBack',
    defaultMessage: 'Action {reference} of {committee}, due {date}, was sent back to you',
  },
})

const kinds = defineMessages({
  passwordChanged: { id: 'notificationKinds.passwordChanged', defaultMessage: 'Your password was changed' },
  mfaReset: { id: 'notificationKinds.mfaReset', defaultMessage: 'An administrator reset your two-step sign-in' },
  unknown: { id: 'notificationKinds.unknown', defaultMessage: 'Something changed in your account' },
})

/** The values of the meeting kinds: the committee and the meeting's date (never an item's title, ADR-0030). */
function meetingValues(params: Record<string, string>): { committee: string; date: string } {
  return { committee: params.committee ?? '', date: params.date ?? '' }
}

/** The values of the action kinds: the committee and the reference (never the action's title or text, design E07 §2.3). */
function actionValues(params: Record<string, string>): { committee: string; reference: string } {
  return { committee: params.committee ?? '', reference: params.reference ?? '' }
}

/** The values of the declaration kinds: the meeting and the item's number (never its title or the nature, ADR-0062). */
function declarationValues(params: Record<string, string>): {
  committee: string
  date: string
  item: string
  change: string
  outcome: string
} {
  return {
    ...meetingValues(params),
    item: params.item ?? '',
    change: params.change ?? '',
    outcome: params.outcome ?? '',
  }
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
    case 'MEETING_AGENDA_DUE':
      return intl.formatMessage(kindsWithValues.meetingAgendaDue, meetingValues(params))
    case 'MEETING_NOT_HELD':
      return intl.formatMessage(kindsWithValues.meetingNotHeld, meetingValues(params))
    case 'MEETING_DECLARATION_RECORDED':
      return intl.formatMessage(kindsWithValues.meetingDeclarationRecorded, declarationValues(params))
    case 'MEETING_DECLARATION_MADE':
      return intl.formatMessage(kindsWithValues.meetingDeclarationMade, declarationValues(params))
    case 'MEETING_RULING_NEEDED':
      return intl.formatMessage(kindsWithValues.meetingRulingNeeded, declarationValues(params))
    case 'MEETING_DECLARATION_RULED':
      return intl.formatMessage(kindsWithValues.meetingDeclarationRuled, declarationValues(params))
    case 'MEETING_DECLARATIONS_DUE':
      return intl.formatMessage(kindsWithValues.meetingDeclarationsDue, meetingValues(params))
    case 'MEETING_INVITATION':
      return intl.formatMessage(kindsWithValues.meetingInvitation, meetingValues(params))
    case 'MEETING_UPDATED':
      return intl.formatMessage(kindsWithValues.meetingUpdated, meetingValues(params))
    case 'MEETING_UNINVITED':
      return intl.formatMessage(kindsWithValues.meetingUninvited, meetingValues(params))
    case 'MEETING_RSVP_RECORDED':
      return intl.formatMessage(kindsWithValues.meetingRsvpRecorded, {
        ...meetingValues(params),
        answer: params.answer ?? '',
        how: params.how ?? '',
      })
    case 'MEETING_RSVP_CHANGED':
      return intl.formatMessage(kindsWithValues.meetingRsvpChanged, {
        ...meetingValues(params),
        member: params.member ?? '',
        answer: params.answer ?? '',
      })
    case 'MEETING_ALTERNATE_ASKED':
      return intl.formatMessage(kindsWithValues.meetingAlternateAsked, {
        ...meetingValues(params),
        member: params.member ?? '',
      })
    case 'MEETING_ALTERNATE_RELEASED':
      return intl.formatMessage(kindsWithValues.meetingAlternateReleased, {
        ...meetingValues(params),
        member: params.member ?? '',
      })
    case 'MEETING_QUORUM_LOST':
    case 'MEETING_QUORUM_RESTORED':
      return intl.formatMessage(
        kind === 'MEETING_QUORUM_LOST' ? kindsWithValues.meetingQuorumLost : kindsWithValues.meetingQuorumRestored,
        { ...meetingValues(params), present: params.present ?? '', needed: params.needed ?? '' },
      )
    case 'MEETING_QUORUM_OVERRIDDEN':
      return intl.formatMessage(kindsWithValues.meetingQuorumOverridden, {
        ...meetingValues(params),
        present: params.present ?? '',
        needed: params.needed ?? '',
      })
    case 'MEETING_REPLY_REMINDER':
      return intl.formatMessage(kindsWithValues.meetingReplyReminder, meetingValues(params))
    case 'MEETING_REMINDER':
      return intl.formatMessage(kindsWithValues.meetingReminder, {
        ...meetingValues(params),
        unanswered: params.unanswered === 'true' ? 'true' : 'false',
        toReview: Number(params.toReview ?? '0') || 0,
      })
    case 'MEETING_QUORUM_AT_RISK':
      return intl.formatMessage(kindsWithValues.meetingQuorumAtRisk, meetingValues(params))
    case 'ACTION_ASSIGNED':
      return intl.formatMessage(kindsWithValues.actionAssigned, {
        committee: params.committee ?? '',
        reference: params.reference ?? '',
        date: params.date ?? '',
      })
    case 'ACTION_SOURCE_CORRECTED':
      return intl.formatMessage(kindsWithValues.actionSourceCorrected, {
        committee: params.committee ?? '',
        reference: params.reference ?? '',
      })
    case 'ACTION_OWNER_CHANGED':
      return intl.formatMessage(kindsWithValues.actionOwnerChanged, actionValues(params))
    case 'ACTION_RISK_ACCEPTED':
      return intl.formatMessage(kindsWithValues.actionRiskAccepted, actionValues(params))
    case 'ACTION_CANCELLED':
      return intl.formatMessage(kindsWithValues.actionCancelled, actionValues(params))
    case 'ACTION_REOPENED':
      return intl.formatMessage(kindsWithValues.actionReopened, { ...actionValues(params), date: params.date ?? '' })
    case 'ACTION_DUE_REQUESTED':
      return intl.formatMessage(kindsWithValues.actionDueRequested, {
        ...actionValues(params),
        date: params.date ?? '',
      })
    case 'ACTION_DUE_DECIDED':
      return intl.formatMessage(kindsWithValues.actionDueDecided, {
        ...actionValues(params),
        date: params.date ?? '',
        decision: params.decision ?? '',
      })
    case 'ACTION_DUE_CHANGED':
      return intl.formatMessage(kindsWithValues.actionDueChanged, { ...actionValues(params), date: params.date ?? '' })
    case 'ACTION_DUE_SOON':
      return intl.formatMessage(kindsWithValues.actionDueSoon, { ...actionValues(params), date: params.date ?? '' })
    case 'ACTION_OVERDUE':
      return intl.formatMessage(kindsWithValues.actionOverdue, {
        ...actionValues(params),
        date: params.date ?? '',
        stage: params.stage ?? '',
      })
    case 'ACTION_TO_VERIFY':
      return intl.formatMessage(kindsWithValues.actionToVerify, actionValues(params))
    case 'ACTION_CLOSED':
      return intl.formatMessage(kindsWithValues.actionClosed, actionValues(params))
    case 'ACTION_NOT_RUNNING':
      return intl.formatMessage(kindsWithValues.actionNotRunning, actionValues(params))
    case 'ACTION_ESCALATED':
      return intl.formatMessage(kindsWithValues.actionEscalated, {
        ...actionValues(params),
        date: params.date ?? '',
        holder: params.holder ?? '',
      })
    case 'ACTION_SENT_BACK':
      return intl.formatMessage(kindsWithValues.actionSentBack, { ...actionValues(params), date: params.date ?? '' })
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
  MEETING_AGENDA_DUE: { id: 'mail.template.meetingAgendaDue', defaultMessage: 'Agenda not published by the deadline' },
  MEETING_NOT_HELD: { id: 'mail.template.meetingNotHeld', defaultMessage: 'Meeting not marked as held' },
  MEETING_DECLARATION_RECORDED: {
    id: 'mail.template.meetingDeclarationRecorded',
    defaultMessage: 'Declaration of interest recorded for you',
  },
  MEETING_DECLARATION_MADE: {
    id: 'mail.template.meetingDeclarationMade',
    defaultMessage: 'Declaration of interest made',
  },
  MEETING_RULING_NEEDED: { id: 'mail.template.meetingRulingNeeded', defaultMessage: 'Ruling on a declaration needed' },
  MEETING_DECLARATION_RULED: {
    id: 'mail.template.meetingDeclarationRuled',
    defaultMessage: 'Ruling on your declaration',
  },
  MEETING_DECLARATIONS_DUE: {
    id: 'mail.template.meetingDeclarationsDue',
    defaultMessage: 'Declarations of interest due',
  },
  MEETING_INVITATION: { id: 'mail.template.meetingInvitation', defaultMessage: 'Meeting invitation' },
  MEETING_UPDATED: { id: 'mail.template.meetingUpdated', defaultMessage: 'Meeting update' },
  MEETING_UNINVITED: { id: 'mail.template.meetingUninvited', defaultMessage: 'Meeting invitation withdrawn' },
  MEETING_RSVP_RECORDED: { id: 'mail.template.meetingRsvpRecorded', defaultMessage: 'Answer recorded for you' },
  MEETING_ALTERNATE_ASKED: {
    id: 'mail.template.meetingAlternateAsked',
    defaultMessage: "Asked to attend in a member's place",
  },
  MEETING_ALTERNATE_RELEASED: {
    id: 'mail.template.meetingAlternateReleased',
    defaultMessage: "No longer needed in a member's place",
  },
  MEETING_REPLY_REMINDER: { id: 'mail.template.meetingReplyReminder', defaultMessage: 'Meeting reply reminder' },
  MEETING_REMINDER: { id: 'mail.template.meetingReminder', defaultMessage: 'Meeting reminder' },
  MEETING_QUORUM_AT_RISK: { id: 'mail.template.meetingQuorumAtRisk', defaultMessage: 'Meeting quorum at risk' },
  ACTION_ASSIGNED: { id: 'mail.template.actionAssigned', defaultMessage: 'Committee action assigned' },
  ACTION_OWNER_CHANGED: { id: 'mail.template.actionOwnerChanged', defaultMessage: 'Committee action owner changed' },
  ACTION_RISK_ACCEPTED: { id: 'mail.template.actionRiskAccepted', defaultMessage: 'Committee action risk accepted' },
  ACTION_ESCALATED: { id: 'mail.template.actionEscalated', defaultMessage: 'Committee action escalated' },
  ACTION_SENT_BACK: { id: 'mail.template.actionSentBack', defaultMessage: 'Committee action sent back' },
  ACTIONS_DIGEST: { id: 'mail.template.actionsDigest', defaultMessage: 'Daily digest of committee actions' },
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
