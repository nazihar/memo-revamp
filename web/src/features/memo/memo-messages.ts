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
  kindOpinion: { id: 'memo.inbox.kind.opinion', defaultMessage: 'Opinion asked' },
  kindClarify: { id: 'memo.inbox.kind.clarify', defaultMessage: 'Question for you' },

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
  actionRecommend: { id: 'memo.action.recommend', defaultMessage: 'Recommend' },
  actionRecall: { id: 'memo.action.recall', defaultMessage: 'Recall' },
  actionSendBack: { id: 'memo.action.sendBack', defaultMessage: 'Send back' },
  actionForward: { id: 'memo.action.forward', defaultMessage: 'Forward' },
  actionDelegate: { id: 'memo.action.delegate', defaultMessage: 'Delegate' },
  actionAsk: { id: 'memo.action.ask', defaultMessage: 'Ask a question' },
  actionRefer: { id: 'memo.action.refer', defaultMessage: 'Ask for opinions' },
  actionAnswer: { id: 'memo.action.answer', defaultMessage: 'Answer' },
  actionOpinion: { id: 'memo.action.opinion', defaultMessage: 'Give your opinion' },
  sharedBadge: { id: 'memo.inbox.shared', defaultMessage: 'Shared' },
  actionRecuse: { id: 'memo.action.recuse', defaultMessage: 'Declare an interest' },
  actionClaim: { id: 'memo.action.claim', defaultMessage: 'Claim' },
  actionRelease: { id: 'memo.action.release', defaultMessage: 'Release' },
  claimed: { id: 'memo.claimed', defaultMessage: 'You claimed this task. The others cannot act until you release it.' },
  shared: {
    id: 'memo.shared',
    defaultMessage: 'Others can act on this too. The first decision completes the step; claim it to work on it alone.',
  },
  interests: { id: 'memo.interests', defaultMessage: 'Declared interests' },
  interestsLead: {
    id: 'memo.interests.lead',
    defaultMessage: 'People with an interest in this memo cannot approve it. The reason stays with the memo.',
  },
  interestsNone: { id: 'memo.interests.none', defaultMessage: 'Nobody has declared an interest.' },
  interestPerson: { id: 'memo.interests.person', defaultMessage: 'Person' },
  interestReason: { id: 'memo.interests.reason', defaultMessage: 'Reason' },
  interestAdd: { id: 'memo.interests.add', defaultMessage: 'Declare interest' },
  interestReasonRequired: { id: 'memo.interests.reasonRequired', defaultMessage: 'Give a reason.' },
  doaPreview: { id: 'memo.doa.preview', defaultMessage: 'Who approves this?' },
  doaPreviewTitle: { id: 'memo.doa.preview.title', defaultMessage: 'Delegation of authority' },
  doaNone: { id: 'memo.doa.none', defaultMessage: 'No delegation of authority is in force.' },
  doaNoRule: { id: 'memo.doa.noRule', defaultMessage: 'No rule of the delegation of authority applies to this memo.' },
  doaNobody: { id: 'memo.doa.nobody', defaultMessage: 'Nobody holds these positions now.' },
  close: { id: 'memo.close', defaultMessage: 'Close' },
  actions: { id: 'memo.actions', defaultMessage: 'Actions' },
  sendBackTo: { id: 'memo.sendBack.to', defaultMessage: 'Send back to' },
  handTo: { id: 'memo.handOn.to', defaultMessage: 'Person' },
  referTo: { id: 'memo.refer.to', defaultMessage: 'People (up to 10)' },
  personRequired: { id: 'memo.handOn.personRequired', defaultMessage: 'Choose a person.' },
  peopleRequired: { id: 'memo.refer.peopleRequired', defaultMessage: 'Choose at least one person.' },
  stepRequired: { id: 'memo.sendBack.stepRequired', defaultMessage: 'Choose a step.' },
  answerText: { id: 'memo.answer.text', defaultMessage: 'Your answer' },
  opinionText: { id: 'memo.opinion.text', defaultMessage: 'Your opinion' },
  waitingForAnswer: {
    id: 'memo.task.waiting',
    defaultMessage: 'You asked a question. You can decide once it is answered.',
  },
  roleOpinion: { id: 'memo.task.role.opinion', defaultMessage: 'Your opinion is asked for' },
  roleClarify: { id: 'memo.task.role.clarify', defaultMessage: 'A question for you' },

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
  invalid: {
    id: 'memo.route.problem.invalid',
    defaultMessage: 'An entry is one person, one position, or a group of two or more.',
  },
  interested: { id: 'memo.route.problem.interested', defaultMessage: 'Has declared an interest in this memo.' },
  'position-unknown': { id: 'memo.route.problem.positionUnknown', defaultMessage: 'This position no longer exists.' },
  'not-allowed': { id: 'memo.route.problem.notAllowed', defaultMessage: 'Not allowed to approve this type of memo.' },
})

export const statusLabels = defineMessages({
  DRAFT: { id: 'memo.status.draft', defaultMessage: 'Draft' },
  IN_REVIEW: { id: 'memo.status.inReview', defaultMessage: 'In review' },
  RETURNED: { id: 'memo.status.returned', defaultMessage: 'Returned' },
  APPROVED: { id: 'memo.status.approved', defaultMessage: 'Approved' },
  REJECTED: { id: 'memo.status.rejected', defaultMessage: 'Rejected' },
  WITHDRAWN: { id: 'memo.status.withdrawn', defaultMessage: 'Withdrawn' },
  DISCARDED: { id: 'memo.status.discarded', defaultMessage: 'Discarded' },
  CANCELLED: { id: 'memo.status.cancelled', defaultMessage: 'Cancelled' },
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
  CLAIM: { id: 'memo.history.action.claim', defaultMessage: 'Took the task' },
  RELEASE: { id: 'memo.history.action.release', defaultMessage: 'Gave the task back' },
  RECUSE: { id: 'memo.history.action.recuse', defaultMessage: 'Declared an interest and stood aside' },
  ESCALATE: { id: 'memo.history.action.escalate', defaultMessage: 'Escalated by the workflow: overdue' },
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
  sendBackTarget: { step: string; name: string }
  step: { step: string }
  historyTo: { names: string }
  historyMigrated: { from: number; to: number }
  historyBranch: { inputs: string }
  claimedBy: { name: string }
  actingFor: { name: string }
  dueAt: { time: string }
  overdueSince: { time: string }
  doaGroup: { group: number; needed: number; covered: number }
  doaVersion: { version: number; rule: string }
}>({
  claimedBy: { id: 'memo.claimedBy', defaultMessage: 'Claimed by {name}' },
  actingFor: { id: 'memo.actingFor', defaultMessage: 'For {name}' },
  dueAt: { id: 'memo.due', defaultMessage: 'Due {time}' },
  overdueSince: { id: 'memo.overdue', defaultMessage: 'Overdue since {time}' },
  doaGroup: {
    id: 'memo.doa.group',
    defaultMessage:
      'Group {group}: {needed, plural, one {# approval} other {# approvals}} needed; your route has {covered}.',
  },
  doaVersion: { id: 'memo.doa.version', defaultMessage: 'Version {version}, rule “{rule}”' },
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
  sendBackTarget: { id: 'memo.sendBack.target', defaultMessage: '{step} ({name})' },
  step: { id: 'memo.step', defaultMessage: 'Step: {step}' },
  historyTo: { id: 'memo.history.to', defaultMessage: 'To {names}' },
  historyMigrated: { id: 'memo.history.migrated', defaultMessage: 'From version {from} to version {to}' },
  historyBranch: { id: 'memo.history.branch', defaultMessage: 'Based on {inputs}' },
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
  recommend: { reference: string }
  recall: { reference: string }
  sendBack: { reference: string }
  forward: { reference: string }
  delegate: { reference: string }
  ask: { reference: string }
  refer: { reference: string }
  answer: { reference: string }
  opinion: { reference: string }
  recuse: { reference: string }
}>({
  recuse: { id: 'memo.dialog.recuse', defaultMessage: 'Stand aside from {reference}?' },
  recommend: { id: 'memo.dialog.recommend', defaultMessage: 'Recommend {reference}?' },
  recall: { id: 'memo.dialog.recall', defaultMessage: 'Recall {reference}?' },
  sendBack: { id: 'memo.dialog.sendBack', defaultMessage: 'Send {reference} back?' },
  forward: { id: 'memo.dialog.forward', defaultMessage: 'Forward {reference}?' },
  delegate: { id: 'memo.dialog.delegate', defaultMessage: 'Delegate {reference}?' },
  ask: { id: 'memo.dialog.ask', defaultMessage: 'Ask about {reference}?' },
  refer: { id: 'memo.dialog.refer', defaultMessage: 'Ask for opinions on {reference}?' },
  answer: { id: 'memo.dialog.answer', defaultMessage: 'Answer the question on {reference}' },
  opinion: { id: 'memo.dialog.opinion', defaultMessage: 'Your opinion on {reference}' },
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
  recommend: { number: number }
  recall: { number: number }
  sendBack: { number: number }
  forward: { number: number }
  delegate: { number: number }
  ask: { number: number }
  refer: { number: number }
  answer: { number: number }
  opinion: { number: number }
  recuse: { number: number }
}>({
  recuse: {
    id: 'memo.dialog.recuse.lead',
    defaultMessage: 'Say what your interest is. The task leaves you and the step goes to someone else.',
  },
  recommend: {
    id: 'memo.dialog.recommend.lead',
    defaultMessage: 'You recommend document version {number}. The next step gets it with your recommendation.',
  },
  recall: {
    id: 'memo.dialog.recall.lead',
    defaultMessage: 'Nobody has acted on it yet. It comes back to you to change and resubmit.',
  },
  sendBack: {
    id: 'memo.dialog.sendBack.lead',
    defaultMessage: 'The step you choose runs again, and the steps after it too. Say why.',
  },
  forward: { id: 'memo.dialog.forward.lead', defaultMessage: 'The person you choose holds the step instead of you.' },
  delegate: {
    id: 'memo.dialog.delegate.lead',
    defaultMessage: 'The person you choose acts for you; the step stays yours.',
  },
  ask: { id: 'memo.dialog.ask.lead', defaultMessage: 'You can decide once your question is answered.' },
  refer: { id: 'memo.dialog.refer.lead', defaultMessage: 'You keep the task and decide when you are ready.' },
  answer: { id: 'memo.dialog.answer.lead', defaultMessage: 'Whoever asked can decide once you answer.' },
  opinion: { id: 'memo.dialog.opinion.lead', defaultMessage: 'It goes into the history for whoever decides.' },
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
  recommend: mm.actionRecommend,
  recall: mm.actionRecall,
  sendBack: mm.actionSendBack,
  forward: mm.actionForward,
  delegate: mm.actionDelegate,
  ask: mm.actionAsk,
  refer: mm.actionRefer,
  answer: mm.actionAnswer,
  opinion: mm.actionOpinion,
  recuse: mm.actionRecuse,
}
