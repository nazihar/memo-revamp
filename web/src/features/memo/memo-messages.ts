import { defineMessages } from 'react-intl'

/** Texts of the memo screens (design E01 §13, ux-review.md). */
export const mm = defineMessages({
  inboxTitle: { id: 'memo.inbox.title', defaultMessage: 'Inbox' },
  inboxLead: { id: 'memo.inbox.lead', defaultMessage: 'Memos waiting for you, and the memos you write.' },
  tabTasks: { id: 'memo.inbox.tab.tasks', defaultMessage: 'My tasks' },
  tabDrafts: { id: 'memo.inbox.tab.drafts', defaultMessage: 'Drafts' },
  tabSent: { id: 'memo.inbox.tab.sent', defaultMessage: 'Sent' },
  tabClosed: { id: 'memo.inbox.tab.closed', defaultMessage: 'Closed' },
  newMemo: { id: 'memo.new', defaultMessage: 'New memo' },
  emptyTasks: { id: 'memo.inbox.empty.tasks', defaultMessage: 'Nothing is waiting for you.' },
  emptyDrafts: { id: 'memo.inbox.empty.drafts', defaultMessage: 'You have no drafts.' },
  emptySent: { id: 'memo.inbox.empty.sent', defaultMessage: 'None of your memos is in review.' },
  emptyClosed: { id: 'memo.inbox.empty.closed', defaultMessage: 'No closed memos yet.' },
  loadMore: { id: 'memo.loadMore', defaultMessage: 'Show more' },
  restricted: { id: 'memo.restricted', defaultMessage: 'Restricted memo' },
  restrictedHint: {
    id: 'memo.restricted.hint',
    defaultMessage: 'You are no longer cleared to read it. An administrator can reassign the task.',
  },
  blocked: { id: 'memo.blocked', defaultMessage: 'Waiting for reassignment' },

  createTitle: { id: 'memo.create.title', defaultMessage: 'New memo' },
  createLead: {
    id: 'memo.create.lead',
    defaultMessage: 'Start with the subject and type. You add the document and the approvers next.',
  },
  subject: { id: 'memo.field.subject', defaultMessage: 'Subject' },
  type: { id: 'memo.field.type', defaultMessage: 'Memo type' },
  classification: { id: 'memo.field.classification', defaultMessage: 'Classification' },
  classificationHint: {
    id: 'memo.field.classification.hint',
    defaultMessage: 'At least the type’s default, and at most your own clearance.',
  },
  typeDefault: { id: 'memo.field.classification.default', defaultMessage: 'The type’s default' },
  create: { id: 'memo.create.submit', defaultMessage: 'Create draft' },
  save: { id: 'memo.save', defaultMessage: 'Save' },
  cancel: { id: 'memo.cancel', defaultMessage: 'Cancel' },
  required: { id: 'memo.required', defaultMessage: 'Required.' },

  document: { id: 'memo.document', defaultMessage: 'Document' },
  documentNone: { id: 'memo.document.none', defaultMessage: 'No document yet. Upload the memo as a Word file.' },
  documentUpload: { id: 'memo.document.upload', defaultMessage: 'Upload the document' },
  documentNewVersion: { id: 'memo.document.newVersion', defaultMessage: 'Upload a new version' },
  documentHint: { id: 'memo.document.hint', defaultMessage: 'Word (.docx), up to 25 MB.' },
  download: { id: 'memo.document.download', defaultMessage: 'Download' },
  editInBrowser: { id: 'memo.document.editInBrowser', defaultMessage: 'Edit in the browser' },
  openInBrowser: { id: 'memo.document.openInBrowser', defaultMessage: 'Open in the browser' },
  beingEdited: {
    id: 'memo.refused.beingEdited',
    defaultMessage: 'The document is open in the editor. Close it and wait for the save, then submit.',
  },
  route: { id: 'memo.route', defaultMessage: 'Approvers' },
  routeLead: {
    id: 'memo.route.lead',
    defaultMessage: 'In order. Each approver gets the memo after the one before has approved it.',
  },
  routeEmpty: { id: 'memo.route.empty', defaultMessage: 'No approvers yet.' },
  routeAdd: { id: 'memo.route.add', defaultMessage: 'Add approver' },
  routePicker: { id: 'memo.route.picker', defaultMessage: 'Approver' },
  routeSave: { id: 'memo.route.save', defaultMessage: 'Save approvers' },
  routeSaved: { id: 'memo.route.saved', defaultMessage: 'Approvers saved.' },
  history: { id: 'memo.history', defaultMessage: 'History' },
  historyEmpty: { id: 'memo.history.empty', defaultMessage: 'Nothing has happened yet: the memo is a draft.' },
  historyEarlier: { id: 'memo.history.earlier', defaultMessage: 'Earlier round: its approvals no longer count.' },
  holders: { id: 'memo.holders', defaultMessage: 'Waiting for' },

  actionEdit: { id: 'memo.action.edit', defaultMessage: 'Edit' },
  actionSubmit: { id: 'memo.action.submit', defaultMessage: 'Submit' },
  actionDiscard: { id: 'memo.action.discard', defaultMessage: 'Discard draft' },
  actionResubmit: { id: 'memo.action.resubmit', defaultMessage: 'Resubmit' },
  actionWithdraw: { id: 'memo.action.withdraw', defaultMessage: 'Withdraw' },
  actionApprove: { id: 'memo.action.approve', defaultMessage: 'Approve' },
  actionReturn: { id: 'memo.action.return', defaultMessage: 'Return' },
  actionReject: { id: 'memo.action.reject', defaultMessage: 'Reject' },
  actions: { id: 'memo.actions', defaultMessage: 'Actions' },

  remark: { id: 'memo.remark', defaultMessage: 'Remark' },
  remarkOptional: { id: 'memo.remark.optional', defaultMessage: 'Remark (optional)' },
  remarkRequired: { id: 'memo.remark.required', defaultMessage: 'A remark is required.' },
  done: { id: 'memo.done', defaultMessage: 'Done.' },
  conflict: { id: 'memo.conflict', defaultMessage: 'Someone acted on this memo meanwhile. It was reloaded.' },
  failed: { id: 'memo.failed', defaultMessage: 'That did not work. Try again.' },
  notFound: { id: 'memo.notFound', defaultMessage: 'This memo does not exist, or you cannot see it.' },
  documentRequired: { id: 'memo.refused.document', defaultMessage: 'Upload the document first.' },
  routeRequired: { id: 'memo.refused.route', defaultMessage: 'Add at least one approver first.' },
})

/** Route problems as the server names them (design E01 §5). */
export const routeProblems = defineMessages({
  self: { id: 'memo.route.problem.self', defaultMessage: 'You cannot approve your own memo.' },
  duplicate: { id: 'memo.route.problem.duplicate', defaultMessage: 'Already on the route.' },
  'approver-inactive': { id: 'memo.route.problem.inactive', defaultMessage: 'Cannot sign in at the moment.' },
  'approver-not-cleared': {
    id: 'memo.route.problem.notCleared',
    defaultMessage: 'Not cleared for this classification.',
  },
  'too-long': { id: 'memo.route.problem.tooLong', defaultMessage: 'Too many approvers.' },
  empty: { id: 'memo.route.problem.empty', defaultMessage: 'Add at least one approver.' },
})

export const statusLabels = defineMessages({
  DRAFT: { id: 'memo.status.draft', defaultMessage: 'Draft' },
  IN_REVIEW: { id: 'memo.status.inReview', defaultMessage: 'In review' },
  RETURNED: { id: 'memo.status.returned', defaultMessage: 'Returned' },
  APPROVED: { id: 'memo.status.approved', defaultMessage: 'Approved' },
  REJECTED: { id: 'memo.status.rejected', defaultMessage: 'Rejected' },
  WITHDRAWN: { id: 'memo.status.withdrawn', defaultMessage: 'Withdrawn' },
  DISCARDED: { id: 'memo.status.discarded', defaultMessage: 'Discarded' },
})

export const actionLabels = defineMessages({
  SUBMIT: { id: 'memo.history.action.submit', defaultMessage: 'Submitted' },
  RESUBMIT: { id: 'memo.history.action.resubmit', defaultMessage: 'Resubmitted' },
  APPROVE: { id: 'memo.history.action.approve', defaultMessage: 'Approved' },
  RETURN: { id: 'memo.history.action.return', defaultMessage: 'Returned' },
  REJECT: { id: 'memo.history.action.reject', defaultMessage: 'Rejected' },
  WITHDRAW: { id: 'memo.history.action.withdraw', defaultMessage: 'Withdrew' },
  REASSIGN: { id: 'memo.history.action.reassign', defaultMessage: 'Reassigned' },
  RECOMMEND: { id: 'memo.history.action.recommend', defaultMessage: 'Recommended' },
  ROUTE: { id: 'memo.history.action.route', defaultMessage: 'Routed by the workflow' },
  NOTIFY: { id: 'memo.history.action.notify', defaultMessage: 'Notification sent' },
  ESCALATE_TO_FORUM: { id: 'memo.history.action.escalateToForum', defaultMessage: 'Escalated to a forum' },
  SEND_BACK: { id: 'memo.history.action.sendBack', defaultMessage: 'Sent back to an earlier step' },
  FORWARD: { id: 'memo.history.action.forward', defaultMessage: 'Forwarded' },
  DELEGATE: { id: 'memo.history.action.delegate', defaultMessage: 'Delegated' },
  ASK: { id: 'memo.history.action.ask', defaultMessage: 'Asked for clarification' },
  ANSWER: { id: 'memo.history.action.answer', defaultMessage: 'Answered' },
  REFER: { id: 'memo.history.action.refer', defaultMessage: 'Asked for opinions' },
  OPINION: { id: 'memo.history.action.opinion', defaultMessage: 'Gave an opinion' },
  RECALL: { id: 'memo.history.action.recall', defaultMessage: 'Recalled' },
  CANCEL: { id: 'memo.history.action.cancel', defaultMessage: 'Cancelled' },
  MIGRATE: { id: 'memo.history.action.migrate', defaultMessage: 'Moved to a new workflow version' },
  CARRY: { id: 'memo.history.action.carry', defaultMessage: 'Approval carried over (document unchanged)' },
})

/** Texts with values (react-intl checks them against the message). */
export const mmv = defineMessages<{
  round: { round: number }
  assignedAt: { time: string }
  updatedAt: { time: string }
  referenceRound: { reference: string; round: number }
  referenceHolders: { reference: string; names: string }
  from: { name: string }
  documentVersion: { number: number; name: string }
  routeUp: { name: string }
  routeDown: { name: string }
  routeRemove: { name: string }
  historyDocument: { number: number }
}>({
  round: { id: 'memo.round', defaultMessage: 'Round {round}' },
  assignedAt: { id: 'memo.assignedAt', defaultMessage: 'Waiting since {time}' },
  updatedAt: { id: 'memo.updatedAt', defaultMessage: 'Changed {time}' },
  referenceRound: { id: 'memo.referenceRound', defaultMessage: '{reference} · Round {round}' },
  referenceHolders: { id: 'memo.referenceHolders', defaultMessage: '{reference} · With {names}' },
  from: { id: 'memo.from', defaultMessage: 'From {name}' },
  documentVersion: { id: 'memo.document.version', defaultMessage: 'Version {number} · {name}' },
  routeUp: { id: 'memo.route.up', defaultMessage: 'Move {name} up' },
  routeDown: { id: 'memo.route.down', defaultMessage: 'Move {name} down' },
  routeRemove: { id: 'memo.route.remove', defaultMessage: 'Remove {name}' },
  historyDocument: { id: 'memo.history.document', defaultMessage: 'Document version {number}' },
})

/** Action dialog titles; each may name the memo by its reference. */
export const dialogTitles = defineMessages<{
  submit: { reference: string }
  resubmit: { reference: string }
  withdraw: { reference: string }
  discard: { reference: string }
  approve: { reference: string }
  return: { reference: string }
  reject: { reference: string }
}>({
  submit: { id: 'memo.dialog.submit', defaultMessage: 'Submit for approval?' },
  resubmit: { id: 'memo.dialog.resubmit', defaultMessage: 'Resubmit?' },
  withdraw: { id: 'memo.dialog.withdraw', defaultMessage: 'Withdraw this memo?' },
  discard: { id: 'memo.dialog.discard', defaultMessage: 'Discard this draft?' },
  approve: { id: 'memo.dialog.approve', defaultMessage: 'Approve {reference}?' },
  return: { id: 'memo.dialog.return', defaultMessage: 'Return {reference} to its author?' },
  reject: { id: 'memo.dialog.reject', defaultMessage: 'Reject {reference}?' },
})

/** Action dialog leads; each may name the document version. */
export const dialogLeads = defineMessages<{
  submit: { number: number }
  resubmit: { number: number }
  withdraw: { number: number }
  discard: { number: number }
  approve: { number: number }
  return: { number: number }
  reject: { number: number }
}>({
  submit: {
    id: 'memo.dialog.submit.lead',
    defaultMessage: 'The first approver gets it now. You cannot change it while it is in review.',
  },
  resubmit: {
    id: 'memo.dialog.resubmit.lead',
    defaultMessage: 'A new round starts with the first approver. Approvals of earlier rounds no longer count.',
  },
  withdraw: { id: 'memo.dialog.withdraw.lead', defaultMessage: 'The approval ends for good.' },
  discard: { id: 'memo.dialog.discard.lead', defaultMessage: 'It moves to Closed and cannot be submitted.' },
  approve: {
    id: 'memo.dialog.approve.lead',
    defaultMessage: 'You approve document version {number}. You may be asked to confirm it is you.',
  },
  return: {
    id: 'memo.dialog.return.lead',
    defaultMessage: 'Say what needs to change. They can resubmit it.',
  },
  reject: { id: 'memo.dialog.reject.lead', defaultMessage: 'The memo ends here. Say why.' },
})

/** The label of each action dialog's button, and of the button that opens it. */
export const actionButtons = {
  submit: mm.actionSubmit,
  resubmit: mm.actionResubmit,
  withdraw: mm.actionWithdraw,
  discard: mm.actionDiscard,
  approve: mm.actionApprove,
  return: mm.actionReturn,
  reject: mm.actionReject,
}
