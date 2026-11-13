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
  bellMore: { id: 'notifications.bellMore', defaultMessage: '9+' },

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
  categorySECURITY: { id: 'notifications.category.security', defaultMessage: 'Security' },
  categoryADMINISTRATION: { id: 'notifications.category.administration', defaultMessage: 'Access and administration' },
  categoryWORKFLOW: { id: 'notifications.category.workflow', defaultMessage: 'Memo approvals' },

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
  bell: { count: number }
  unreadItem: { text: string }
  prefCell: { category: string; channel: string }
  testFailed: { detail: string }
  mailInvalid: { detail: string }
}>({
  bell: {
    id: 'notifications.bell',
    defaultMessage: '{count, plural, =0 {Notifications} one {Notifications, # unread} other {Notifications, # unread}}',
  },
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
  taskReminder: { reference: string }
  taskOverdue: { reference: string }
  editNotSaved: { name: string }
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
  taskReminder: {
    id: 'notifications.kind.taskReminder',
    defaultMessage: 'Reminder: memo {reference} is waiting for your decision',
  },
  taskOverdue: { id: 'notifications.kind.taskOverdue', defaultMessage: 'Memo {reference} is overdue' },
  editNotSaved: {
    id: 'notifications.kind.editNotSaved',
    defaultMessage: 'Your changes to {name} in the browser editor could not be saved',
  },
})

const kinds = defineMessages({
  passwordChanged: { id: 'notifications.kind.passwordChanged', defaultMessage: 'Your password was changed' },
  mfaReset: { id: 'notifications.kind.mfaReset', defaultMessage: 'An administrator reset your two-step sign-in' },
  unknown: { id: 'notifications.kind.unknown', defaultMessage: 'Something changed in your account' },
})

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
    case 'TASK_REMINDER':
      return intl.formatMessage(kindsWithValues.taskReminder, reference)
    case 'TASK_OVERDUE':
      return intl.formatMessage(kindsWithValues.taskOverdue, reference)
    case 'EDIT_NOT_SAVED':
      return intl.formatMessage(kindsWithValues.editNotSaved, { name: params.name ?? '' })
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
  TASK_REMINDER: { id: 'mail.template.taskReminder', defaultMessage: 'Reminder about a memo' },
  TASK_OVERDUE: { id: 'mail.template.taskOverdue', defaultMessage: 'Memo overdue' },
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
