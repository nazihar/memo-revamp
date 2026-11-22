import { defineMessages } from 'react-intl'

/**
 * Texts of the minutes (Phase 4 E06 T11, flows AD–AG): the minutes page per stage (drafting, the chair's review,
 * circulation, approval, the approved record and its corrections), the editor, the copy viewer, the "Minutes" card on
 * the meeting page and the previous minutes' link on the next meeting's agenda. The `minutes` prefix is its own
 * catalogue part, loaded on the `/meetings` pages.
 */
export const mn = defineMessages({
  // The page
  title: { id: 'minutes.title', defaultMessage: 'Minutes' },
  backToMeeting: { id: 'minutes.backToMeeting', defaultMessage: 'Back to the meeting' },
  backToMinutes: { id: 'minutes.backToMinutes', defaultMessage: 'Back to the minutes' },
  AT_NEXT_MEETING: { id: 'minutes.mode.atNextMeeting', defaultMessage: 'approved at the next meeting' },
  BY_CIRCULATION: { id: 'minutes.mode.byCirculation', defaultMessage: 'approved by circulation' },
  // Stages
  DRAFT: { id: 'minutes.stage.draft', defaultMessage: 'Draft' },
  CHAIR_REVIEW: { id: 'minutes.stage.chairReview', defaultMessage: 'With the chair for review' },
  CIRCULATED: { id: 'minutes.stage.circulated', defaultMessage: 'Circulated for comments' },
  AWAITING_CHAIR: { id: 'minutes.stage.awaitingChair', defaultMessage: 'Waiting for the chair’s approval' },
  APPROVED: { id: 'minutes.stage.approved', defaultMessage: 'Approved' },
  // Steps
  steps: { id: 'minutes.steps', defaultMessage: 'Steps' },
  stepDraft: { id: 'minutes.step.draft', defaultMessage: 'Draft' },
  stepReview: { id: 'minutes.step.review', defaultMessage: 'Chair’s review' },
  stepCirculated: { id: 'minutes.step.circulated', defaultMessage: 'Circulated for comments' },
  stepNextMeeting: { id: 'minutes.step.nextMeeting', defaultMessage: 'Approved at the next meeting' },
  stepEnded: { id: 'minutes.step.ended', defaultMessage: 'Comment period ended' },
  stepApproved: { id: 'minutes.step.approved', defaultMessage: 'Approved by the chair' },
  stepDone: { id: 'minutes.step.done', defaultMessage: '(done)' },
  stepNow: { id: 'minutes.step.now', defaultMessage: '(now)' },
  // Drafting (flow AD1)
  noDraft: {
    id: 'minutes.noDraft',
    defaultMessage:
      'No draft yet. It will be made from the committee’s template with the attendance, declarations, decisions and your notes.',
  },
  makeDraft: { id: 'minutes.makeDraft', defaultMessage: 'Make the draft' },
  making: { id: 'minutes.making', defaultMessage: 'Making the draft… This takes a few seconds.' },
  recusedSecretary: {
    id: 'minutes.recusedSecretary',
    defaultMessage:
      'You declared an interest in an item of this meeting, so another secretary drafts and edits the minutes.',
  },
  openEditor: { id: 'minutes.openEditor', defaultMessage: 'Open in the editor' },
  sendToChair: { id: 'minutes.sendToChair', defaultMessage: 'Send to the chair' },
  circulate: { id: 'minutes.circulate', defaultMessage: 'Circulate for comments' },
  seeCopyFor: { id: 'minutes.seeCopyFor', defaultMessage: 'See the copy for…' },
  readMyCopy: { id: 'minutes.readMyCopy', defaultMessage: 'Read my copy' },
  checking: { id: 'minutes.checking', defaultMessage: 'Checking the draft’s item blocks…' },
  structureTitle: { id: 'minutes.structure.title', defaultMessage: 'Something is wrong with the draft' },
  structureCopies: {
    id: 'minutes.structure.copies',
    defaultMessage: 'Copies for people who declared an interest cannot be made until it is put right.',
  },
  structureRemake: {
    id: 'minutes.structure.remake',
    defaultMessage: 'Put the block right in the editor, or make the whole draft again.',
  },
  // Filling again (flow AD3)
  itemsTitle: { id: 'minutes.items.title', defaultMessage: 'Fill an item again' },
  itemsLead: {
    id: 'minutes.items.lead',
    defaultMessage: 'An item’s block is filled again from its decision and your notes. Your edits elsewhere stay.',
  },
  fillAgain: { id: 'minutes.fillAgain', defaultMessage: 'Fill it again' },
  remakeAll: { id: 'minutes.remakeAll', defaultMessage: 'Make the whole draft again…' },
  remakeAllTitle: { id: 'minutes.remakeAll.title', defaultMessage: 'Make the whole draft again?' },
  remakeAllWarning: { id: 'minutes.remakeAll.warning', defaultMessage: 'All your edits will be replaced.' },
  remakeAllConfirm: { id: 'minutes.remakeAll.confirm', defaultMessage: 'Make it again' },
  fillWarning: { id: 'minutes.fill.warning', defaultMessage: 'Your edits to this item will be replaced.' },
  cancel: { id: 'minutes.cancel', defaultMessage: 'Cancel' },
  optional: { id: 'minutes.optional', defaultMessage: '(optional)' },
  // Send to the chair (flow AD5)
  sendTitle: { id: 'minutes.send.title', defaultMessage: 'Send the draft to the chair?' },
  sendLead: {
    id: 'minutes.send.lead',
    defaultMessage:
      'The chair will be asked to review the draft. If the chair declared an interest in an item, the vice-chair reviews it.',
  },
  send: { id: 'minutes.send', defaultMessage: 'Send' },
  sent: { id: 'minutes.sent', defaultMessage: 'The draft is with the chair for review.' },
  noReviewerTitle: { id: 'minutes.noReviewer.title', defaultMessage: 'Circulate without a review?' },
  noReviewer: {
    id: 'minutes.noReviewer',
    defaultMessage:
      'No chair or vice-chair is able to review the draft: they declared an interest, or there is none. You can circulate it for comments now, saying why.',
  },
  circulateTitle: { id: 'minutes.circulate.title', defaultMessage: 'Circulate the minutes for comments?' },
  circulateLead: {
    id: 'minutes.circulate.lead',
    defaultMessage:
      'Each member gets their own copy and is asked for comments by the deadline. Silence counts as no comments.',
  },
  deadline: { id: 'minutes.deadline', defaultMessage: 'Comments by' },
  deadlineHint: {
    id: 'minutes.deadline.hint',
    defaultMessage: 'Leave it empty for the committee’s usual number of working days.',
  },
  whyNoReview: { id: 'minutes.whyNoReview', defaultMessage: 'Why is nobody reviewing the draft?' },
  circulated: { id: 'minutes.circulated', defaultMessage: 'The minutes are circulated for comments.' },
  // The chair's review (flow AE1)
  reviewReady: { id: 'minutes.review.ready', defaultMessage: 'Ready for your review' },
  readDraft: { id: 'minutes.review.read', defaultMessage: 'Read the draft' },
  suggestChanges: { id: 'minutes.review.suggest', defaultMessage: 'Suggest changes in the document' },
  suggestHint: {
    id: 'minutes.review.suggestHint',
    defaultMessage: 'Your changes show as suggestions the secretary accepts or rejects.',
  },
  commentsToSecretary: { id: 'minutes.review.comments', defaultMessage: 'Comments to the secretary' },
  sendBack: { id: 'minutes.review.sendBack', defaultMessage: 'Send back with comments' },
  readyToCirculate: { id: 'minutes.review.agree', defaultMessage: 'Ready to circulate' },
  sayWhat: { id: 'minutes.review.sayWhat', defaultMessage: 'Say what should change.' },
  sentBack: {
    id: 'minutes.review.sentBack',
    defaultMessage: 'The draft is back with the secretary with your comments.',
  },
  agreedCirculated: {
    id: 'minutes.review.agreedCirculated',
    defaultMessage: 'You agreed the draft. It is now circulated for comments.',
  },
  agreedChanges: {
    id: 'minutes.review.agreedChanges',
    defaultMessage:
      'You agreed the draft. The secretary accepts or rejects your suggested changes, then circulates it.',
  },
  // On the chair's behalf
  recordAgreement: { id: 'minutes.onBehalf', defaultMessage: 'Record the chair’s agreement…' },
  recordAgreementTitle: { id: 'minutes.onBehalf.title', defaultMessage: 'Record the chair’s agreement' },
  howAgreed: { id: 'minutes.onBehalf.how', defaultMessage: 'How did they agree?' },
  EMAIL: { id: 'minutes.how.email', defaultMessage: 'By email' },
  PHONE: { id: 'minutes.how.phone', defaultMessage: 'By phone' },
  IN_PERSON: { id: 'minutes.how.inPerson', defaultMessage: 'In person' },
  OTHER: { id: 'minutes.how.other', defaultMessage: 'Another way' },
  agreementNote: { id: 'minutes.onBehalf.note', defaultMessage: 'Note' },
  agreementNoteHint: { id: 'minutes.onBehalf.noteHint', defaultMessage: 'For example “Email of 13 December”.' },
  record: { id: 'minutes.onBehalf.record', defaultMessage: 'Record the agreement' },
  agreementRecorded: { id: 'minutes.onBehalf.recorded', defaultMessage: 'The chair’s agreement is recorded.' },
  // Circulation for the secretaries (flow AF4)
  changeDeadline: { id: 'minutes.deadline.change', defaultMessage: 'Change the deadline' },
  changeDeadlineTitle: { id: 'minutes.deadline.changeTitle', defaultMessage: 'Change the comment deadline' },
  deadlineChanged: { id: 'minutes.deadline.changed', defaultMessage: 'The deadline is changed.' },
  save: { id: 'minutes.save', defaultMessage: 'Save' },
  commentsTitle: { id: 'minutes.comments.title', defaultMessage: 'Comments' },
  noSuggestions: { id: 'minutes.comments.none', defaultMessage: 'No suggestions yet.' },
  inGeneral: { id: 'minutes.comments.general', defaultMessage: 'The minutes in general' },
  adopt: { id: 'minutes.comments.adopt', defaultMessage: 'Adopt' },
  notAdopt: { id: 'minutes.comments.notAdopt', defaultMessage: 'Do not adopt' },
  responseNote: { id: 'minutes.comments.note', defaultMessage: 'Note to the member' },
  ADOPTED: { id: 'minutes.comments.adopted', defaultMessage: 'Adopted' },
  NOT_ADOPTED: { id: 'minutes.comments.notAdopted', defaultMessage: 'Not adopted' },
  askOthers: { id: 'minutes.ask', defaultMessage: 'Ask someone else for comments…' },
  askTitle: { id: 'minutes.ask.title', defaultMessage: 'Ask someone else for comments' },
  askLead: {
    id: 'minutes.ask.lead',
    defaultMessage:
      'For example the head of a department on the items they know. Their copy shows the items you name and nothing else.',
  },
  askPerson: { id: 'minutes.ask.person', defaultMessage: 'Person' },
  askAdd: { id: 'minutes.ask.add', defaultMessage: 'Add' },
  askPeopleList: { id: 'minutes.ask.people', defaultMessage: 'People to ask' },
  itemUnknown: {
    id: 'minutes.suggest.itemUnknown',
    defaultMessage: 'Choose an item you can read, or the minutes in general.',
  },
  askItems: { id: 'minutes.ask.items', defaultMessage: 'On which items?' },
  askSend: { id: 'minutes.ask.send', defaultMessage: 'Ask for comments' },
  askNobody: { id: 'minutes.ask.nobody', defaultMessage: 'Add at least one person.' },
  askNoItem: { id: 'minutes.ask.noItem', defaultMessage: 'Choose at least one item.' },
  askNotCleared: {
    id: 'minutes.ask.notCleared',
    defaultMessage: 'Someone you named could not read any of these items. Choose other items or other people.',
  },
  asked: { id: 'minutes.ask.done', defaultMessage: 'They are asked for comments and get their own copy.' },
  withdraw: { id: 'minutes.withdraw', defaultMessage: 'Take back to draft…' },
  withdrawTitle: { id: 'minutes.withdraw.title', defaultMessage: 'Take the minutes back to the draft?' },
  withdrawLead: {
    id: 'minutes.withdraw.lead',
    defaultMessage:
      'Members can no longer read or comment on them. Their comments so far are kept. You circulate them again when ready.',
  },
  withdrawConfirm: { id: 'minutes.withdraw.confirm', defaultMessage: 'Take back to draft' },
  withdrawn: { id: 'minutes.withdrawn', defaultMessage: 'The minutes are back in the draft.' },
  endEarly: { id: 'minutes.end', defaultMessage: 'End the comment period now…' },
  endTitle: { id: 'minutes.end.title', defaultMessage: 'End the comment period now?' },
  endLead: {
    id: 'minutes.end.lead',
    defaultMessage: 'The minutes go to the chair for approval. Members who have not answered can no longer comment.',
  },
  endConfirm: { id: 'minutes.end.confirm', defaultMessage: 'End the comment period' },
  ended: { id: 'minutes.ended', defaultMessage: 'The comment period has ended. The minutes are with the chair.' },
  reason: { id: 'minutes.reason', defaultMessage: 'Reason' },
  reasonRequired: { id: 'minutes.reason.required', defaultMessage: 'Say why.' },
  editsUpdate: {
    id: 'minutes.editsUpdate',
    defaultMessage: 'Edits you make now update everyone’s copy a few minutes after you save.',
  },
  // Reading (flows AF1–AF3)
  readMinutes: { id: 'minutes.read', defaultMessage: 'Read the minutes' },
  noComments: { id: 'minutes.noComments', defaultMessage: 'No comments' },
  suggestCorrection: { id: 'minutes.suggest', defaultMessage: 'Suggest a correction' },
  noCommentsTitle: {
    id: 'minutes.noComments.title',
    defaultMessage: 'Tell the secretary you have no comments on these minutes?',
  },
  noCommentsSent: { id: 'minutes.noComments.sent', defaultMessage: 'You told the secretary you have no comments.' },
  laterSuggestion: { id: 'minutes.noComments.later', defaultMessage: 'You can still suggest a correction.' },
  whichPart: { id: 'minutes.suggest.part', defaultMessage: 'Which part?' },
  suggestion: { id: 'minutes.suggest.text', defaultMessage: 'Your suggestion' },
  suggestionHint: {
    id: 'minutes.suggest.hint',
    defaultMessage: 'The secretary and the chair will see this; other members will not.',
  },
  writeSuggestion: { id: 'minutes.suggest.required', defaultMessage: 'Write your suggestion.' },
  sendSuggestion: { id: 'minutes.suggest.send', defaultMessage: 'Send' },
  suggestionSent: {
    id: 'minutes.suggest.sent',
    defaultMessage: 'Your suggestion is sent. The secretary will reply here.',
  },
  myComments: { id: 'minutes.mine', defaultMessage: 'Your comments' },
  awaitingReply: { id: 'minutes.mine.awaiting', defaultMessage: 'No reply yet.' },
  linkedReader: {
    id: 'minutes.linkedReader',
    defaultMessage: 'The next meeting is asked to approve these minutes. Read your copy before it.',
  },
  // The copy viewer (flow AF2)
  copyBeingMade: {
    id: 'minutes.copy.making',
    defaultMessage: 'The copy is being made. It opens by itself when it is ready.',
  },
  recordBeingMade: {
    id: 'minutes.copy.recordMaking',
    defaultMessage: 'The approved minutes are being made into the record. They open by themselves when ready.',
  },
  copyMissing: { id: 'minutes.copy.missing', defaultMessage: 'This copy is not available to you.' },
  copyFailed: { id: 'minutes.copy.failed', defaultMessage: 'The copy could not be shown. Try again later.' },
  loadingCopy: { id: 'minutes.copy.loading', defaultMessage: 'Opening the minutes…' },
  copyChoose: { id: 'minutes.copy.choose', defaultMessage: 'Whose copy?' },
  everyoneElse: { id: 'minutes.copy.everyoneElse', defaultMessage: 'Everyone else — the full minutes' },
  showCopy: { id: 'minutes.copy.show', defaultMessage: 'Show the copy' },
  RECUSED: { id: 'minutes.redacted.recused', defaultMessage: 'declared an interest' },
  RESTRICTED: { id: 'minutes.redacted.restricted', defaultMessage: 'restricted' },
  HIDDEN: { id: 'minutes.redacted.hidden', defaultMessage: 'hidden' },
  // Approval (flow AG3)
  approveTitle: { id: 'minutes.approve.title', defaultMessage: 'Ready for your approval' },
  readFinal: { id: 'minutes.approve.read', defaultMessage: 'Read the final minutes' },
  approveLead: {
    id: 'minutes.approve.lead',
    defaultMessage: 'When you approve, the minutes are locked and kept as the permanent record.',
  },
  notYet: { id: 'minutes.approve.notYet', defaultMessage: 'Not yet' },
  approve: { id: 'minutes.approve', defaultMessage: 'Approve the minutes' },
  approveStepUp: {
    id: 'minutes.approve.stepUp',
    defaultMessage: 'You will confirm with your authenticator.',
  },
  approvedNow: { id: 'minutes.approve.done', defaultMessage: 'The minutes are approved and kept as the record.' },
  endedByCirculation: { id: 'minutes.approve.ended', defaultMessage: 'The comment period has ended.' },
  // The approved minutes (flow AG4)
  recordTitle: { id: 'minutes.record.title', defaultMessage: 'The record' },
  recordPending: { id: 'minutes.record.pending', defaultMessage: 'The record is being made…' },
  recordFailed: {
    id: 'minutes.record.failed',
    defaultMessage: 'The record could not be made yet. It is tried again; operations have been told.',
  },
  notCompliant: { id: 'minutes.record.notCompliant', defaultMessage: 'not confirmed as PDF/A' },
  current: { id: 'minutes.record.current', defaultMessage: '(current)' },
  versions: { id: 'minutes.record.versions', defaultMessage: 'Versions of the record' },
  check: { id: 'minutes.check', defaultMessage: 'Check' },
  checkMatches: {
    id: 'minutes.check.matches',
    defaultMessage: 'The record matches its approval: nothing has changed since it was approved.',
  },
  checkProblems: { id: 'minutes.check.problems', defaultMessage: 'The check found a problem. Tell operations:' },
  DOCUMENT_CHANGED: {
    id: 'minutes.check.documentChanged',
    defaultMessage: 'The approved document no longer matches its fingerprint.',
  },
  DOCUMENT_MISSING: { id: 'minutes.check.documentMissing', defaultMessage: 'The approved document is missing.' },
  FILE_CHANGED: { id: 'minutes.check.fileChanged', defaultMessage: 'Your copy no longer matches its fingerprint.' },
  FILE_MISSING: { id: 'minutes.check.fileMissing', defaultMessage: 'Your copy is missing.' },
  NOT_READY: { id: 'minutes.check.notReady', defaultMessage: 'The record is not made yet.' },
  checkOther: { id: 'minutes.check.other', defaultMessage: 'The approval’s chain or fingerprint does not match.' },
  CHAIR: { id: 'minutes.role.chair', defaultMessage: 'Chair' },
  VICE_CHAIR: { id: 'minutes.role.viceChair', defaultMessage: 'Vice-chair' },
  withAuthenticator: { id: 'minutes.factors.totp', defaultMessage: 'password and authenticator' },
  withPassword: { id: 'minutes.factors.password', defaultMessage: 'password' },
  // Corrections (flow AG4, E06 T8)
  correctionsTitle: { id: 'minutes.corrections.title', defaultMessage: 'Corrections' },
  correctionsNone: { id: 'minutes.corrections.none', defaultMessage: 'Corrections: none' },
  proposeCorrection: { id: 'minutes.corrections.propose', defaultMessage: 'Propose a correction' },
  proposeLead: {
    id: 'minutes.corrections.proposeLead',
    defaultMessage:
      'Approved minutes are never changed. The chair approves the correction with their authenticator, and the record gains a Corrections page in a new version.',
  },
  correctionText: { id: 'minutes.corrections.text', defaultMessage: 'The correction' },
  correctionReason: { id: 'minutes.corrections.reason', defaultMessage: 'Why it is needed' },
  correctionRequired: { id: 'minutes.corrections.required', defaultMessage: 'Write the correction.' },
  propose: { id: 'minutes.corrections.send', defaultMessage: 'Send to the chair' },
  proposed: { id: 'minutes.corrections.proposed', defaultMessage: 'The correction is sent to the chair for approval.' },
  PROPOSED: { id: 'minutes.corrections.state.proposed', defaultMessage: 'Waiting for the chair' },
  DECLINED: { id: 'minutes.corrections.state.declined', defaultMessage: 'Declined' },
  approveCorrection: { id: 'minutes.corrections.approve', defaultMessage: 'Approve the correction' },
  declineCorrection: { id: 'minutes.corrections.decline', defaultMessage: 'Decline…' },
  declineTitle: { id: 'minutes.corrections.declineTitle', defaultMessage: 'Decline the correction?' },
  declineNote: { id: 'minutes.corrections.declineNote', defaultMessage: 'Note to the secretary' },
  declineConfirm: { id: 'minutes.corrections.declineConfirm', defaultMessage: 'Decline' },
  correctionApproved: {
    id: 'minutes.corrections.approved',
    defaultMessage: 'The correction is approved. A new version of the record is being made.',
  },
  correctionDeclined: { id: 'minutes.corrections.declined', defaultMessage: 'The correction is declined.' },
  // The editor (flow AD2)
  editorFailed: { id: 'minutes.editor.failed', defaultMessage: 'The editor could not be opened.' },
  editorSaving: { id: 'minutes.editor.saving', defaultMessage: 'Saving your changes as a new version…' },
  editorNotSaved: {
    id: 'minutes.editor.notSaved',
    defaultMessage: 'Your changes could not be saved: the minutes can no longer be changed at this stage.',
  },
  editorUnavailable: {
    id: 'minutes.editor.unavailable',
    defaultMessage: 'The draft cannot be opened in the editor now.',
  },
  // The meeting page's card
  cardTitle: { id: 'minutes.card.title', defaultMessage: 'Minutes' },
  openMinutes: { id: 'minutes.card.open', defaultMessage: 'Open the minutes' },
  // Refusals in words
  NO_DRAFT: { id: 'minutes.problem.noDraft', defaultMessage: 'Make the draft first.' },
  NOT_AGREED: {
    id: 'minutes.problem.notAgreed',
    defaultMessage: 'The chair has not agreed the draft yet. Send it to the chair first.',
  },
  UNRESOLVED_CHANGES: {
    id: 'minutes.problem.unresolved',
    defaultMessage: 'Accept or reject the chair’s suggested changes and resolve the comments in the editor first.',
  },
  WRONG_STATE: {
    id: 'minutes.problem.wrongState',
    defaultMessage: 'The minutes have moved on since this page opened. It now shows where they stand.',
  },
  FROZEN: { id: 'minutes.problem.frozen', defaultMessage: 'The minutes are approved and can no longer be changed.' },
  GENERATING: { id: 'minutes.problem.generating', defaultMessage: 'The draft is being made. Wait for it to finish.' },
  DRAFT_EXISTS: {
    id: 'minutes.problem.draftExists',
    defaultMessage: 'There is a draft already. Make it again only to replace all your edits.',
  },
  BEING_EDITED: {
    id: 'minutes.problem.beingEdited',
    defaultMessage: 'Someone has the draft open in the editor. Ask them to close it first, so their changes are kept.',
  },
  RECUSED_STEP: {
    id: 'minutes.problem.recused',
    defaultMessage: 'You declared an interest in an item, so another secretary does this.',
  },
  NOT_CONFIRMER: {
    id: 'minutes.problem.notConfirmer',
    defaultMessage: 'Only the chair approves these minutes (the vice-chair when the chair declared an interest).',
  },
  NOT_REVIEWER: { id: 'minutes.problem.notReviewer', defaultMessage: 'The draft is not with you for review.' },
  SECRETARY: { id: 'minutes.problem.secretary', defaultMessage: 'Secretaries answer comments; they do not send them.' },
  ALREADY_ANSWERED: { id: 'minutes.problem.answered', defaultMessage: 'This has been answered already.' },
  TOO_MANY_COPIES: {
    id: 'minutes.problem.tooManyCopies',
    defaultMessage: 'The readers would need more than 50 different copies. Ask your administrator.',
  },
  DECIDED: { id: 'minutes.problem.decided', defaultMessage: 'This correction has been decided already.' },
  stale: {
    id: 'minutes.problem.stale',
    defaultMessage: 'Someone changed the minutes meanwhile. The page now shows the latest: try again.',
  },
  forbidden: { id: 'minutes.problem.forbidden', defaultMessage: 'You cannot do this.' },
  failed: { id: 'minutes.problem.failed', defaultMessage: 'That did not work. Try again.' },
  tooLong: { id: 'minutes.problem.tooLong', defaultMessage: 'This is too long.' },
  dateRange: {
    id: 'minutes.problem.dateRange',
    defaultMessage: 'Choose a day from tomorrow to a year ahead.',
  },
  INVALID_TEMPLATE: {
    id: 'minutes.work.invalidTemplate',
    defaultMessage: 'The committee’s minutes template has a problem. Ask your administrator to check it.',
  },
  MISSING_VALUE: {
    id: 'minutes.work.missingValue',
    defaultMessage: 'The template asks for something this meeting does not have. Ask your administrator to check it.',
  },
  CONTROLS: {
    id: 'minutes.work.controls',
    defaultMessage: 'The item’s block could not be found or copied. Make the whole draft again.',
  },
  workFailed: { id: 'minutes.work.failed', defaultMessage: 'The draft could not be made. Try again.' },
})

export const mnv = defineMessages<{
  pageTitle: { meeting: string }
  lead: { date: string; mode: string }
  undecided: { count: number; items: string }
  filling: { number: string }
  draftLine: { number: number; when: string }
  draftLineBy: { number: number; when: string; name: string }
  returned: { name: string; date: string }
  agreed: { name: string; date: string }
  agreedBehalf: { name: string; date: string; by: string; how: string }
  agreedNext: { name: string }
  reviewComment: { comment: string }
  marks: { changes: number; comments: number }
  structureItem: { number: string }
  structureDuplicated: { number: string }
  structureNested: { number: string }
  structureUnlocked: { number: string }
  structureUnknown: { tag: string }
  restore: { number: string }
  fillAgainNamed: { number: string }
  fillTitle: { number: string }
  withReviewer: { name: string }
  circulatedOn: { date: string; until: string }
  linkedTo: { meeting: string }
  answers: { readers: number; noComments: number; suggestions: number; notYet: number }
  noCommentsFrom: { names: string }
  commentLine: { name: string; part: string; date: string }
  responseLine: { response: string; name: string }
  responseLineNote: { response: string; name: string; note: string }
  item: { number: string }
  itemTitle: { number: string; title: string }
  commentsBy: { date: string }
  draftOf: { meeting: string }
  approvedAt: { meeting: string; date: string }
  stepApprovedAt: { meeting: string }
  amend: { name: string }
  waitingFor: { name: string }
  periodEnded: { noComments: number; suggestions: number; notYet: number }
  approveOf: { meeting: string; date: string }
  approvedOn: { date: string }
  correctedOn: { date: string }
  recordLine: { version: number; fingerprint: string }
  versionLine: { version: number; date: string }
  readVersion: { version: number }
  approvedBy: { name: string; role: string; factors: string }
  approvedByAge: { name: string; role: string; factors: string; minutes: number }
  correctionApprovedBy: { name: string; role: string; factors: string }
  yourCopyLeavesOut: { items: string }
  redactedItem: { number: string; why: string }
  copyFor: { names: string; items: string }
  copyOf: { names: string }
  correctionLine: { part: string; name: string; date: string }
  correctionReasonLine: { reason: string }
  correctionDecided: { state: string; name: string; date: string }
  correctionWithheld: { number: string }
  previousMinutes: { meeting: string; stage: string }
  previousMinutesNoted: { meeting: string; date: string }
  editing: { meeting: string }
  reviewing: { meeting: string }
  removePerson: { name: string }
  cardStage: { stage: string }
  respondNamed: { action: string; name: string; part: string }
  paren: { text: string }
  correctionDecidedNote: { state: string; name: string; date: string; note: string }
  partSent: { part: string; date: string }
  reviewTitle: { meeting: string }
}>({
  pageTitle: { id: 'minutes.v.title', defaultMessage: 'Minutes — {meeting}' },
  lead: { id: 'minutes.v.lead', defaultMessage: '{date} · {mode}' },
  undecided: {
    id: 'minutes.v.undecided',
    defaultMessage:
      '{count, plural, one {Item {items} has no decision} other {Items {items} have no decision}}. Record one before you send the minutes to the chair.',
  },
  filling: { id: 'minutes.v.filling', defaultMessage: 'Filling item {number} again…' },
  draftLine: { id: 'minutes.v.draftLine', defaultMessage: 'Draft ready (version {number}, {when})' },
  draftLineBy: { id: 'minutes.v.draftLineBy', defaultMessage: 'Draft ready (version {number}, {when}, by {name})' },
  returned: { id: 'minutes.v.returned', defaultMessage: '{name} sent the draft back on {date}.' },
  agreed: {
    id: 'minutes.v.agreed',
    defaultMessage: '{name} agreed the draft on {date}.',
  },
  agreedBehalf: {
    id: 'minutes.v.agreedBehalf',
    defaultMessage: '{name} agreed the draft on {date} ({how}, recorded by {by}).',
  },
  agreedNext: {
    id: 'minutes.v.agreedNext',
    defaultMessage: 'Accept or reject {name}’s suggested changes in the editor, then circulate the minutes.',
  },
  reviewComment: { id: 'minutes.v.reviewComment', defaultMessage: '“{comment}”' },
  marks: {
    id: 'minutes.v.marks',
    defaultMessage:
      'The draft has {changes, plural, =0 {no suggested changes} one {# suggested change} other {# suggested changes}} and {comments, plural, =0 {no comments} one {# comment} other {# comments}} left. Accept or reject them in the editor before the minutes are circulated.',
  },
  structureItem: { id: 'minutes.v.structureItem', defaultMessage: 'The block for item {number} is missing.' },
  structureDuplicated: {
    id: 'minutes.v.structureDuplicated',
    defaultMessage: 'The block for item {number} is there twice.',
  },
  structureNested: {
    id: 'minutes.v.structureNested',
    defaultMessage: 'The block for item {number} is inside another item’s block.',
  },
  structureUnlocked: {
    id: 'minutes.v.structureUnlocked',
    defaultMessage: 'The block for item {number} has lost its lock.',
  },
  structureUnknown: {
    id: 'minutes.v.structureUnknown',
    defaultMessage: 'The draft has a block that belongs to no item ({tag}).',
  },
  restore: { id: 'minutes.v.restore', defaultMessage: 'Restore item {number}' },
  fillAgainNamed: { id: 'minutes.v.fillAgainNamed', defaultMessage: 'Fill item {number} again' },
  fillTitle: {
    id: 'minutes.v.fillTitle',
    defaultMessage: 'Fill item {number} again from the decision and notes?',
  },
  withReviewer: { id: 'minutes.v.withReviewer', defaultMessage: 'The draft is with {name} for review.' },
  circulatedOn: { id: 'minutes.v.circulatedOn', defaultMessage: 'Circulated on {date}. Comments by {until}.' },
  linkedTo: {
    id: 'minutes.v.linkedTo',
    defaultMessage: 'On the agenda of {meeting} as “Minutes of the previous meeting”.',
  },
  answers: {
    id: 'minutes.v.answers',
    defaultMessage:
      '{readers, plural, one {# reader} other {# readers}}: {noComments} no comments · {suggestions, plural, one {# suggestion} other {# suggestions}} · {notYet} not yet',
  },
  noCommentsFrom: { id: 'minutes.v.noCommentsFrom', defaultMessage: 'No comments: {names}' },
  commentLine: { id: 'minutes.v.commentLine', defaultMessage: '{name} · {part} · {date}' },
  responseLine: { id: 'minutes.v.responseLine', defaultMessage: '{response} — {name}' },
  responseLineNote: { id: 'minutes.v.responseLineNote', defaultMessage: '{response} — {name}: {note}' },
  item: { id: 'minutes.v.item', defaultMessage: 'Item {number}' },
  itemTitle: { id: 'minutes.v.itemTitle', defaultMessage: 'Item {number} — {title}' },
  commentsBy: { id: 'minutes.v.commentsBy', defaultMessage: 'Please send any comments by {date}.' },
  draftOf: { id: 'minutes.v.draftOf', defaultMessage: 'Draft minutes of {meeting}' },
  approvedAt: {
    id: 'minutes.v.approvedAt',
    defaultMessage: 'Approved at {meeting} on {date}.',
  },
  stepApprovedAt: { id: 'minutes.v.stepApprovedAt', defaultMessage: 'Approved at {meeting}' },
  amend: {
    id: 'minutes.v.amend',
    defaultMessage: 'Apply any amendments in the editor, then {name} approves the minutes.',
  },
  waitingFor: { id: 'minutes.v.waitingFor', defaultMessage: 'Waiting for {name} to approve.' },
  periodEnded: {
    id: 'minutes.v.periodEnded',
    defaultMessage:
      'The comment period has ended: {noComments} no comments, {suggestions, plural, one {# suggestion} other {# suggestions}}, {notYet} no answer.',
  },
  approveOf: { id: 'minutes.v.approveOf', defaultMessage: 'Approve the minutes of {meeting} ({date})' },
  approvedOn: { id: 'minutes.v.approvedOn', defaultMessage: 'Approved on {date}' },
  correctedOn: { id: 'minutes.v.correctedOn', defaultMessage: 'Corrected on {date}' },
  recordLine: {
    id: 'minutes.v.recordLine',
    defaultMessage: 'Record version {version}: PDF/A, fingerprint {fingerprint}',
  },
  versionLine: { id: 'minutes.v.versionLine', defaultMessage: 'Version {version}, made {date}' },
  readVersion: { id: 'minutes.v.readVersion', defaultMessage: 'Read version {version}' },
  approvedBy: {
    id: 'minutes.v.approvedBy',
    defaultMessage: 'Approved by {name}, {role} — authenticated with {factors}',
  },
  approvedByAge: {
    id: 'minutes.v.approvedByAge',
    defaultMessage:
      'Approved by {name}, {role} — authenticated with {factors}, {minutes, plural, =0 {less than a minute} one {# minute} other {# minutes}} before',
  },
  correctionApprovedBy: {
    id: 'minutes.v.correctionApprovedBy',
    defaultMessage: 'Correction approved by {name}, {role} — authenticated with {factors}',
  },
  yourCopyLeavesOut: { id: 'minutes.v.yourCopyLeavesOut', defaultMessage: 'Your copy leaves out {items}.' },
  redactedItem: { id: 'minutes.v.redactedItem', defaultMessage: 'item {number} ({why})' },
  copyFor: { id: 'minutes.v.copyFor', defaultMessage: '{names} — {items} left out' },
  copyOf: { id: 'minutes.v.copyOf', defaultMessage: 'The copy for {names}' },
  correctionLine: { id: 'minutes.v.correctionLine', defaultMessage: '{part} — proposed by {name} on {date}' },
  correctionReasonLine: { id: 'minutes.v.correctionReasonLine', defaultMessage: 'Why: {reason}' },
  correctionDecided: { id: 'minutes.v.correctionDecided', defaultMessage: '{state} by {name} on {date}' },
  correctionWithheld: {
    id: 'minutes.v.correctionWithheld',
    defaultMessage: 'A correction to item {number}, which your copy leaves out.',
  },
  previousMinutes: {
    id: 'minutes.v.previousMinutes',
    defaultMessage: 'Minutes of {meeting} ({stage, select, APPROVED {approved} other {draft}}) — read your copy',
  },
  previousMinutesNoted: {
    id: 'minutes.v.previousMinutesNoted',
    defaultMessage: 'Minutes of {meeting}, approved by circulation on {date}, for noting — read your copy',
  },
  editing: { id: 'minutes.v.editing', defaultMessage: 'Editing the draft minutes of {meeting}' },
  reviewing: { id: 'minutes.v.reviewing', defaultMessage: 'Suggesting changes to the draft minutes of {meeting}' },
  removePerson: { id: 'minutes.v.removePerson', defaultMessage: 'Remove {name}' },
  cardStage: { id: 'minutes.v.cardStage', defaultMessage: 'The minutes: {stage}.' },
  respondNamed: { id: 'minutes.v.respondNamed', defaultMessage: '{action}: {name}, {part}' },
  paren: { id: 'minutes.v.paren', defaultMessage: '({text})' },
  correctionDecidedNote: {
    id: 'minutes.v.correctionDecidedNote',
    defaultMessage: '{state} by {name} on {date}: {note}',
  },
  partSent: { id: 'minutes.v.partSent', defaultMessage: '{part} · sent on {date}' },
  reviewTitle: { id: 'minutes.v.reviewTitle', defaultMessage: 'Draft minutes of {meeting} — ready for your review' },
})
