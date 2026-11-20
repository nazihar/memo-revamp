import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of the agenda builder (Phase 4 E03 T7, flows O, Q and R): the builder, the item sheet, the tray, publishing,
 * the chair's side and the committee type's Agenda tab. The `agendaBuilder` prefix is its own catalogue part, loaded
 * only on the builder and on the committee-type pages. Messages that name the committee take the organisation's terms
 * (ADR-0032).
 */
export const ap = defineMessages({
  // Page and states
  backToMeeting: { id: 'agendaBuilder.backToMeeting', defaultMessage: 'Back to the meeting' },
  notPublished: { id: 'agendaBuilder.notPublished', defaultMessage: 'Draft · not published yet' },
  notOfficer: {
    id: 'agendaBuilder.notOfficer',
    defaultMessage:
      'The draft agenda is for the secretaries and the chair. The meeting page shows the published agenda.',
  },
  notStartedYet: {
    id: 'agendaBuilder.notStartedYet',
    defaultMessage: 'The secretary has not started the agenda yet.',
  },
  dirty: {
    id: 'agendaBuilder.dirty',
    defaultMessage: 'There are changes the members do not see yet. Publish a revision to show them.',
  },
  clean: { id: 'agendaBuilder.clean', defaultMessage: 'The members see the agenda as it is here.' },
  readOnly: {
    id: 'agendaBuilder.readOnly',
    defaultMessage: 'You read the draft agenda. Only the secretaries change it.',
  },
  frozen: {
    id: 'agendaBuilder.frozen',
    defaultMessage: 'The meeting has been held or cancelled: the agenda can no longer change.',
  },
  tabledOnly: {
    id: 'agendaBuilder.tabledOnly',
    defaultMessage: 'The meeting is in progress: you can add items and papers, marked “Tabled at the meeting”.',
  },
  publishedOnTime: { id: 'agendaBuilder.publishedOnTime', defaultMessage: 'published on time' },
  deadlinePassedCHAIR_APPROVAL: {
    id: 'agendaBuilder.deadlinePassed.chair',
    defaultMessage: 'The deadline has passed: late papers go to the chair first.',
  },
  deadlinePassedALLOW: {
    id: 'agendaBuilder.deadlinePassed.allow',
    defaultMessage: 'The deadline has passed: late papers are marked “Late paper”.',
  },
  deadlinePassedBLOCK: {
    id: 'agendaBuilder.deadlinePassed.block',
    defaultMessage: 'The deadline has passed: late papers are not accepted.',
  },
  // Starting (O1)
  howToStart: { id: 'agendaBuilder.start.how', defaultMessage: 'How would you like to start?' },
  fromMeeting: { id: 'agendaBuilder.start.fromMeeting', defaultMessage: 'Copy an earlier meeting' },
  fromMeetingHint: {
    id: 'agendaBuilder.start.fromMeetingHint',
    defaultMessage: 'Copies sections, items, times and presenters. Never papers or queue items.',
  },
  noEarlier: { id: 'agendaBuilder.start.noEarlier', defaultMessage: 'There is no earlier meeting to copy.' },
  copyFrom: { id: 'agendaBuilder.start.copyFrom', defaultMessage: 'Meeting to copy' },
  start: { id: 'agendaBuilder.start.submit', defaultMessage: 'Start' },
  // The agenda
  noSections: {
    id: 'agendaBuilder.noSections',
    defaultMessage: 'The agenda has no sections yet. Add a section first.',
  },
  noNumber: { id: 'agendaBuilder.noNumber', defaultMessage: '–' },
  emptySection: { id: 'agendaBuilder.emptySection', defaultMessage: 'No items in this section.' },
  consent: { id: 'agendaBuilder.section.consent', defaultMessage: 'Consent items' },
  membersOnly: { id: 'agendaBuilder.section.membersOnly', defaultMessage: 'Members only' },
  landsHere: { id: 'agendaBuilder.section.landsHere', defaultMessage: 'New items land here' },
  addItem: { id: 'agendaBuilder.addItem', defaultMessage: 'Add item' },
  addSection: { id: 'agendaBuilder.addSection', defaultMessage: 'Add section' },
  // Lateness as officers read it
  lateLATE: { id: 'agendaBuilder.late.late', defaultMessage: 'Late paper' },
  lateAWAITING_CHAIR: {
    id: 'agendaBuilder.late.awaitingChair',
    defaultMessage: 'Late paper · waiting for the chair (not published or timed until agreed)',
  },
  lateCHAIR_AGREED: { id: 'agendaBuilder.late.chairAgreed', defaultMessage: 'Late paper (chair agreed)' },
  lateTABLED: { id: 'agendaBuilder.late.tabled', defaultMessage: 'Tabled at the meeting' },
  paperAwaitingChair: {
    id: 'agendaBuilder.late.paperAwaitingChair',
    defaultMessage: 'A late paper is waiting for the chair',
  },
  // Row menu
  edit: { id: 'agendaBuilder.menu.edit', defaultMessage: 'Edit…' },
  papersAction: { id: 'agendaBuilder.menu.papers', defaultMessage: 'Papers…' },
  whoCanSee: { id: 'agendaBuilder.menu.whoCanSee', defaultMessage: 'Who can see this…' },
  moveUp: { id: 'agendaBuilder.menu.moveUp', defaultMessage: 'Move up' },
  moveDown: { id: 'agendaBuilder.menu.moveDown', defaultMessage: 'Move down' },
  moveToSection: { id: 'agendaBuilder.menu.moveToSection', defaultMessage: 'Move to section…' },
  indent: { id: 'agendaBuilder.menu.indent', defaultMessage: 'Make a sub-item of the item above' },
  outdent: { id: 'agendaBuilder.menu.outdent', defaultMessage: 'Make an item again' },
  moveToPosition: { id: 'agendaBuilder.menu.moveToPosition', defaultMessage: 'Move to position…' },
  addSubItem: { id: 'agendaBuilder.menu.addSubItem', defaultMessage: 'Add a sub-item…' },
  remove: { id: 'agendaBuilder.menu.remove', defaultMessage: 'Remove from agenda' },
  // Section menu
  renameSection: { id: 'agendaBuilder.section.rename', defaultMessage: 'Rename and settings…' },
  consentOnAction: { id: 'agendaBuilder.section.consentOn', defaultMessage: 'Make it a consent section' },
  consentOffAction: { id: 'agendaBuilder.section.consentOff', defaultMessage: 'Make it an ordinary section' },
  membersOnlyOnAction: { id: 'agendaBuilder.section.membersOnlyOn', defaultMessage: 'Members only: on' },
  membersOnlyOffAction: { id: 'agendaBuilder.section.membersOnlyOff', defaultMessage: 'Members only: off' },
  landsHereAction: { id: 'agendaBuilder.section.landsHereAction', defaultMessage: 'New items land here' },
  moveSectionUp: { id: 'agendaBuilder.section.moveUp', defaultMessage: 'Move section up' },
  moveSectionDown: { id: 'agendaBuilder.section.moveDown', defaultMessage: 'Move section down' },
  removeSection: { id: 'agendaBuilder.section.remove', defaultMessage: 'Remove section' },
  removeSectionText: {
    id: 'agendaBuilder.section.removeText',
    defaultMessage: 'The section is empty. It is taken off the draft.',
  },
  // Section dialog
  sectionTitle: { id: 'agendaBuilder.section.title', defaultMessage: 'Section title' },
  consentField: { id: 'agendaBuilder.section.consentField', defaultMessage: 'Consent section' },
  consentHint: {
    id: 'agendaBuilder.section.consentHint',
    defaultMessage: 'Taken together without discussion. Any member can ask for an item to be discussed.',
  },
  membersOnlyField: { id: 'agendaBuilder.section.membersOnlyField', defaultMessage: 'Members only' },
  membersOnlyHint: {
    id: 'agendaBuilder.section.membersOnlyHint',
    defaultMessage: 'For the chair, members and secretaries only. Guests do not see it.',
  },
  landsHereField: { id: 'agendaBuilder.section.landsHereField', defaultMessage: 'New items land here' },
  landsHereHint: {
    id: 'agendaBuilder.section.landsHereHint',
    defaultMessage: 'Papers added from Ready for an agenda go to the end of this section. One section at most.',
  },
  addSectionSubmit: { id: 'agendaBuilder.section.add', defaultMessage: 'Add section' },
  // Moves
  move: { id: 'agendaBuilder.move.submit', defaultMessage: 'Move' },
  section: { id: 'agendaBuilder.move.section', defaultMessage: 'Section' },
  where: { id: 'agendaBuilder.move.where', defaultMessage: 'Where in the section' },
  atEnd: { id: 'agendaBuilder.move.atEnd', defaultMessage: 'At the end' },
  atStart: { id: 'agendaBuilder.move.atStart', defaultMessage: 'At the start' },
  position: { id: 'agendaBuilder.move.position', defaultMessage: 'Position' },
  // Remove
  removeText: { id: 'agendaBuilder.remove.text', defaultMessage: 'The item is taken off the draft.' },
  removeWithSubItems: {
    id: 'agendaBuilder.remove.withSubItems',
    defaultMessage: 'The item is taken off the draft. Its sub-items stay and take its place.',
  },
  removeSubmission: {
    id: 'agendaBuilder.remove.submission',
    defaultMessage: 'The item is taken off the draft. Its paper goes back to Ready for an agenda, still accepted.',
  },
  removeConfirm: { id: 'agendaBuilder.remove.confirm', defaultMessage: 'Remove' },
  keep: { id: 'agendaBuilder.keep', defaultMessage: 'Keep it' },
  // Add item
  whatToAdd: { id: 'agendaBuilder.add.what', defaultMessage: 'What to add' },
  kindITEM: { id: 'agendaBuilder.add.item', defaultMessage: 'An item' },
  kindBREAK: { id: 'agendaBuilder.add.break', defaultMessage: 'A break' },
  kindSTANDING: { id: 'agendaBuilder.add.standing', defaultMessage: 'A standing item' },
  standingKind: { id: 'agendaBuilder.add.standingKind', defaultMessage: 'Standing item' },
  standingTitleHint: {
    id: 'agendaBuilder.add.standingTitleHint',
    defaultMessage: 'Leave it empty to use the standing item’s usual title.',
  },
  breakTitle: { id: 'agendaBuilder.add.breakTitle', defaultMessage: 'Break' },
  itemTitle: { id: 'agendaBuilder.item.title', defaultMessage: 'Title on the agenda' },
  purpose: { id: 'agendaBuilder.item.purpose', defaultMessage: 'Purpose' },
  minutes: { id: 'agendaBuilder.item.minutes', defaultMessage: 'Time in minutes' },
  subItemNoTime: { id: 'agendaBuilder.item.subItemNoTime', defaultMessage: 'A sub-item has no time of its own.' },
  addItemSubmit: { id: 'agendaBuilder.add.submit', defaultMessage: 'Add' },
  tabledHint: {
    id: 'agendaBuilder.add.tabledHint',
    defaultMessage: 'The meeting is in progress: the item is marked “Tabled at the meeting”.',
  },
  optional: { id: 'agendaBuilder.optional', defaultMessage: '(optional)' },
  // Item sheet
  sheetTabs: { id: 'agendaBuilder.sheet.tabs', defaultMessage: 'Parts of the item' },
  tabDetails: { id: 'agendaBuilder.sheet.details', defaultMessage: 'Details' },
  tabPapers: { id: 'agendaBuilder.sheet.papers', defaultMessage: 'Papers' },
  tabAccess: { id: 'agendaBuilder.sheet.access', defaultMessage: 'Who can see this' },
  resolutionSought: { id: 'agendaBuilder.item.resolution', defaultMessage: 'Resolution sought' },
  description: { id: 'agendaBuilder.item.description', defaultMessage: 'Note for the members' },
  presentersField: { id: 'agendaBuilder.item.presenters', defaultMessage: 'Presenters' },
  presenterPerson: { id: 'agendaBuilder.item.presenterPerson', defaultMessage: 'Add a person' },
  presenterName: { id: 'agendaBuilder.item.presenterName', defaultMessage: 'Or type a name' },
  addName: { id: 'agendaBuilder.item.addName', defaultMessage: 'Add the name' },
  attendeesField: { id: 'agendaBuilder.item.attendees', defaultMessage: 'In attendance' },
  attendeesHint: {
    id: 'agendaBuilder.item.attendeesHint',
    defaultMessage: 'For this item only; they see this item’s papers. Presenters are in attendance too.',
  },
  addAttendee: { id: 'agendaBuilder.item.addAttendee', defaultMessage: 'Add a person in attendance' },
  nobody: { id: 'agendaBuilder.item.nobody', defaultMessage: 'Nobody yet.' },
  save: { id: 'agendaBuilder.save', defaultMessage: 'Save' },
  saved: { id: 'agendaBuilder.saved', defaultMessage: 'Saved.' },
  close: { id: 'agendaBuilder.close', defaultMessage: 'Close' },
  cancel: { id: 'agendaBuilder.cancel', defaultMessage: 'Cancel' },
  loading: { id: 'agendaBuilder.loading', defaultMessage: 'Loading…' },
  // Who can see this
  whoCanSeeField: { id: 'agendaBuilder.access.field', defaultMessage: 'Who can see this' },
  everyone: { id: 'agendaBuilder.access.everyone', defaultMessage: 'Everyone attending the meeting' },
  everyoneWhoSeesItem: { id: 'agendaBuilder.access.everyoneWhoSeesItem', defaultMessage: 'Everyone who sees the item' },
  onlyThese: { id: 'agendaBuilder.access.onlyThese', defaultMessage: 'Only these people' },
  onlyTheseHint: {
    id: 'agendaBuilder.access.onlyTheseHint',
    defaultMessage: 'People attending the meeting. The restriction stays when a paper is replaced.',
  },
  hideTitleHint: {
    id: 'agendaBuilder.access.hideTitleHint',
    defaultMessage: 'They see “Restricted item” instead of the title.',
  },
  hideTitle: {
    id: 'agendaBuilder.access.hideTitle',
    defaultMessage: 'Hide the title from people who cannot see the item',
  },
  classification: { id: 'agendaBuilder.access.classification', defaultMessage: 'Classification' },
  conflictsLater: {
    id: 'agendaBuilder.access.conflictsLater',
    defaultMessage:
      'A member who declares a conflict will not see the papers (from the next release: conflicts of interest).',
  },
  // Papers
  papersLead: { id: 'agendaBuilder.papers.lead', defaultMessage: 'Papers in the order members see them.' },
  noPapers: { id: 'agendaBuilder.papers.none', defaultMessage: 'No papers yet.' },
  outOfPack: { id: 'agendaBuilder.papers.outOfPack', defaultMessage: 'Memo files left out of the pack' },
  putBack: { id: 'agendaBuilder.papers.putBack', defaultMessage: 'Put back in the pack' },
  uploadPaper: { id: 'agendaBuilder.papers.upload', defaultMessage: 'Upload a paper' },
  uploadHint: {
    id: 'agendaBuilder.papers.uploadHint',
    defaultMessage: 'PDF, Word, Excel, PowerPoint or an image, up to 50 MB. Members read it as a PDF.',
  },
  uploadTabledHint: {
    id: 'agendaBuilder.papers.uploadTabledHint',
    defaultMessage: 'The meeting is in progress: the paper is marked “Tabled at the meeting”.',
  },
  paperAdded: { id: 'agendaBuilder.papers.added', defaultMessage: 'Paper added.' },
  rename: { id: 'agendaBuilder.papers.rename', defaultMessage: 'Rename…' },
  replace: { id: 'agendaBuilder.papers.replace', defaultMessage: 'Replace with a new version…' },
  onlyTheseAction: { id: 'agendaBuilder.papers.onlyThese', defaultMessage: 'Only these people…' },
  removeFromPack: { id: 'agendaBuilder.papers.remove', defaultMessage: 'Remove from the pack' },
  removeMemoPaperText: {
    id: 'agendaBuilder.papers.removeMemoText',
    defaultMessage: 'The file stays with the memo and can be put back in the pack.',
  },
  removeUploadText: {
    id: 'agendaBuilder.papers.removeUploadText',
    defaultMessage: 'The paper is taken off the item. Published revisions keep the version they carried.',
  },
  paperTitle: { id: 'agendaBuilder.papers.title', defaultMessage: 'Title of the paper' },
  paperAccessLead: {
    id: 'agendaBuilder.papers.accessLead',
    defaultMessage: 'A restricted paper is not listed at all for the others.',
  },
  replaceLead: {
    id: 'agendaBuilder.papers.replaceLead',
    defaultMessage:
      'Members will see “Paper replaced” when you publish. The earlier version stays in the revisions already published.',
  },
  newVersion: { id: 'agendaBuilder.papers.newVersion', defaultMessage: 'New version' },
  replaced: { id: 'agendaBuilder.papers.replaced', defaultMessage: 'Paper replaced.' },
  // Tray
  trayEmpty: { id: 'agendaBuilder.tray.empty', defaultMessage: 'No accepted papers are waiting.' },
  addToAgenda: { id: 'agendaBuilder.tray.add', defaultMessage: 'Add to agenda' },
  // Publish
  publish: { id: 'agendaBuilder.publish.submit', defaultMessage: 'Publish' },
  publishLead: {
    id: 'agendaBuilder.publish.lead',
    defaultMessage: 'Members see only what they are allowed to see. Until then they read the last revision.',
  },
  publishFirstLead: {
    id: 'agendaBuilder.publish.firstLead',
    defaultMessage:
      'The first publication: the meeting takes its number (for example “BRC 3/2026”) and the members can read the agenda.',
  },
  publishFirstChanges: { id: 'agendaBuilder.publish.firstChanges', defaultMessage: 'What will be published' },
  note: { id: 'agendaBuilder.publish.note', defaultMessage: 'Note to members' },
  noteHint: {
    id: 'agendaBuilder.publish.noteHint',
    defaultMessage: 'One line on what changed, for example “Updated credit paper”.',
  },
  noteRequired: {
    id: 'agendaBuilder.publish.noteRequired',
    defaultMessage: 'Say in a few words what changed; the members read it.',
  },
  notify: { id: 'agendaBuilder.publish.notify', defaultMessage: 'Tell the members' },
  notifyHint: { id: 'agendaBuilder.publish.notifyHint', defaultMessage: 'Turn it off for a minor correction.' },
  // Chair
  awaitingAgreement: { id: 'agendaBuilder.chair.awaiting', defaultMessage: 'Waiting for the chair’s agreement' },
  recordAgreement: { id: 'agendaBuilder.chair.record', defaultMessage: 'Record the chair’s agreement…' },
  recordAgreementTitle: { id: 'agendaBuilder.chair.recordTitle', defaultMessage: 'Record the chair’s agreement' },
  recordAgreementLead: {
    id: 'agendaBuilder.chair.recordLead',
    defaultMessage:
      'For an agreement the chair gave by email or in person. It is recorded in your name, for the chair.',
  },
  recordAgreementSubmit: { id: 'agendaBuilder.chair.recordSubmit', defaultMessage: 'Record the agreement' },
  howGivenField: { id: 'agendaBuilder.chair.howGiven', defaultMessage: 'When and how it was given' },
  howGivenHint: { id: 'agendaBuilder.chair.howGivenHint', defaultMessage: 'For example “By email on 4 Dec”.' },
  howGiven: { id: 'agendaBuilder.chair.howGivenRequired', defaultMessage: 'Say when and how the chair gave it.' },
  agreementRecorded: { id: 'agendaBuilder.chair.recorded', defaultMessage: 'The chair’s agreement is recorded.' },
  chairWaiting: {
    id: 'agendaBuilder.chair.waiting',
    defaultMessage: 'The draft agenda is waiting for your agreement',
  },
  chairComments: { id: 'agendaBuilder.chair.comments', defaultMessage: 'Comments from the chair' },
  commentField: { id: 'agendaBuilder.chair.commentField', defaultMessage: 'Comment to the secretary' },
  sendComments: { id: 'agendaBuilder.chair.send', defaultMessage: 'Send comments' },
  commentSent: { id: 'agendaBuilder.chair.sent', defaultMessage: 'Your comment was sent to the secretaries.' },
  agreeAgenda: { id: 'agendaBuilder.chair.agree', defaultMessage: 'Agree the agenda' },
  agreeTitle: { id: 'agendaBuilder.chair.agreeTitle', defaultMessage: 'Agree the agenda?' },
  agreeText: {
    id: 'agendaBuilder.chair.agreeText',
    defaultMessage: 'You agree the agenda as it stands now. The secretary can then publish it to the members.',
  },
  notYet: { id: 'agendaBuilder.chair.notYet', defaultMessage: 'Not yet' },
  agreedDone: { id: 'agendaBuilder.chair.agreedDone', defaultMessage: 'You agreed the agenda.' },
  lateForYou: { id: 'agendaBuilder.late.forYou', defaultMessage: 'Late papers: your agreement is needed' },
  lateWaiting: { id: 'agendaBuilder.late.waiting', defaultMessage: 'Late papers waiting for the chair' },
  notThisMeeting: { id: 'agendaBuilder.late.decline', defaultMessage: 'Not this meeting' },
  agreeInclude: { id: 'agendaBuilder.late.agree', defaultMessage: 'Agree to include it' },
  declineText: {
    id: 'agendaBuilder.late.declineText',
    defaultMessage: 'The item is taken off the agenda and its paper goes back to Ready for an agenda.',
  },
  declinePaperText: {
    id: 'agendaBuilder.late.declinePaperText',
    defaultMessage: 'The paper is taken off the item and not published.',
  },
  recordAnswer: { id: 'agendaBuilder.late.record', defaultMessage: 'Record the chair’s answer…' },
  recordAnswerLead: {
    id: 'agendaBuilder.late.recordLead',
    defaultMessage: 'For an answer the chair gave by email or in person. It is recorded in your name, for the chair.',
  },
  recordAnswerSubmit: { id: 'agendaBuilder.late.recordSubmit', defaultMessage: 'Record the answer' },
  chairAnswer: { id: 'agendaBuilder.late.answer', defaultMessage: 'The chair’s answer' },
  // Preview
  previewAs: { id: 'agendaBuilder.preview.as', defaultMessage: 'Preview as' },
  preview: { id: 'agendaBuilder.preview.open', defaultMessage: 'Preview' },
  previewNothing: { id: 'agendaBuilder.preview.nothing', defaultMessage: 'This person would see no item.' },
  untitledSection: { id: 'agendaBuilder.preview.untitledSection', defaultMessage: 'Section' },
  consentTaken: { id: 'agendaBuilder.preview.consent', defaultMessage: '— taken together without discussion' },
  restrictedItem: { id: 'agendaBuilder.preview.restrictedItem', defaultMessage: 'Restricted item' },
  // Drag
  dragInstructions: {
    id: 'agendaBuilder.drag.instructions',
    defaultMessage:
      'To move with the keyboard, press Space, then the arrow keys, then Space to drop or Escape to cancel. Every move is also in the item’s menu.',
  },
  // Refusals
  conflict: {
    id: 'agendaBuilder.problem.conflict',
    defaultMessage:
      'Someone changed the agenda after you opened it. The latest agenda is shown now; check it and try again.',
  },
  FROZEN: {
    id: 'agendaBuilder.problem.frozen',
    defaultMessage:
      'The agenda cannot change in this way now. Once the meeting has started only items and papers can be added; once it is held or cancelled nothing changes.',
  },
  NO_AGENDA: { id: 'agendaBuilder.problem.noAgenda', defaultMessage: 'The agenda has not been started yet.' },
  ONE_LEVEL: {
    id: 'agendaBuilder.problem.oneLevel',
    defaultMessage:
      'Items have one level of sub-items: a sub-item has no sub-items of its own, and a break is never a sub-item.',
  },
  ONE_PLACEMENT: {
    id: 'agendaBuilder.problem.onePlacement',
    defaultMessage: 'This paper is already on the agenda of another meeting.',
  },
  SUBMISSION_STATE: {
    id: 'agendaBuilder.problem.submissionState',
    defaultMessage:
      'This paper is no longer ready for an agenda: it was returned, declined, withdrawn or decided. The list shows the latest.',
  },
  CHAIR_AGREEMENT_REQUIRED: {
    id: 'agendaBuilder.problem.chairAgreement',
    defaultMessage:
      'The chair agrees the agenda before it is first published. Ask the chair, or record their agreement.',
  },
  NOTHING_TO_PUBLISH: {
    id: 'agendaBuilder.problem.nothingToPublish',
    defaultMessage: 'Nothing changed since the last revision, so there is nothing to publish.',
  },
  MEETING_NOT_SCHEDULED: {
    id: 'agendaBuilder.problem.notScheduled',
    defaultMessage: 'Schedule the meeting before publishing its agenda: members do not see a draft meeting.',
  },
  SECTION_NOT_EMPTY: {
    id: 'agendaBuilder.problem.sectionNotEmpty',
    defaultMessage: 'Only an empty section can be removed. Move or remove its items first.',
  },
  ALREADY_IN_PACK: { id: 'agendaBuilder.problem.alreadyInPack', defaultMessage: 'This file is already in the pack.' },
  WRONG_KIND: { id: 'agendaBuilder.problem.wrongKind', defaultMessage: 'A break has no papers.' },
  AGENDA_STARTED: {
    id: 'agendaBuilder.problem.agendaStarted',
    defaultMessage: 'The agenda was already started. The page shows it now.',
  },
  ALREADY_AGREED: {
    id: 'agendaBuilder.problem.alreadyAgreed',
    defaultMessage: 'The chair has already agreed the agenda.',
  },
  WRONG_STATE: {
    id: 'agendaBuilder.problem.wrongState',
    defaultMessage: 'This cannot be done now: the agenda has moved on. The page shows its latest state.',
  },
  forbidden: { id: 'agendaBuilder.problem.forbidden', defaultMessage: 'You cannot change this agenda.' },
  RECUSED: {
    id: 'agendaBuilder.problem.recused',
    defaultMessage: 'You declared an interest in this item, so another secretary or an administrator handles it.',
  },
  notFound: {
    id: 'agendaBuilder.problem.notFound',
    defaultMessage: 'This is no longer on the agenda. The page shows the latest.',
  },
  rateLimited: {
    id: 'agendaBuilder.problem.rateLimited',
    defaultMessage: 'Too many changes in a short time. Wait a moment and try again.',
  },
  failed: { id: 'agendaBuilder.problem.failed', defaultMessage: 'That did not work. Try again.' },
  required: { id: 'agendaBuilder.problem.required', defaultMessage: 'Fill this in.' },
  tooLong: { id: 'agendaBuilder.problem.tooLong', defaultMessage: 'This is too long.' },
  range: { id: 'agendaBuilder.problem.range', defaultMessage: 'This number is out of range.' },
  tooMany: { id: 'agendaBuilder.problem.tooMany', defaultMessage: 'This is more than an agenda holds.' },
  minutesRange: { id: 'agendaBuilder.problem.minutesRange', defaultMessage: 'Give a number of minutes from 0 to 600.' },
  minutesSubItem: { id: 'agendaBuilder.problem.minutesSubItem', defaultMessage: 'A sub-item has no time of its own.' },
  commentLength: {
    id: 'agendaBuilder.problem.commentLength',
    defaultMessage: 'A comment has at most 2,000 characters.',
  },
  restrictionEmpty: {
    id: 'agendaBuilder.problem.restrictionEmpty',
    defaultMessage: 'Choose at least one person, or let everyone see it.',
  },
  restrictionAudience: {
    id: 'agendaBuilder.problem.restrictionAudience',
    defaultMessage: 'Only people attending the meeting can be chosen.',
  },
  notCleared: {
    id: 'agendaBuilder.problem.notCleared',
    defaultMessage: 'Someone chosen is not cleared for this classification.',
  },
  unknownPerson: { id: 'agendaBuilder.problem.unknownPerson', defaultMessage: 'Choose people from the list.' },
  levelRange: {
    id: 'agendaBuilder.problem.levelRange',
    defaultMessage: 'Choose a level no lower than the meeting’s and no higher than your own clearance.',
  },
  presenterFormat: {
    id: 'agendaBuilder.problem.presenterFormat',
    defaultMessage: 'Choose a person or type a name for each presenter.',
  },
  standingDuplicate: {
    id: 'agendaBuilder.problem.standingDuplicate',
    defaultMessage: 'This standing item is already on the agenda.',
  },
  copyUnknown: { id: 'agendaBuilder.problem.copyUnknown', defaultMessage: 'Choose a meeting from the list.' },
  copySame: { id: 'agendaBuilder.problem.copySame', defaultMessage: 'Choose another meeting than this one.' },
  copyNoAgenda: { id: 'agendaBuilder.problem.copyNoAgenda', defaultMessage: 'That meeting has no agenda to copy.' },
  sectionFirst: { id: 'agendaBuilder.problem.sectionFirst', defaultMessage: 'Add a section first.' },
  submissionAbove: {
    id: 'agendaBuilder.problem.submissionAbove',
    defaultMessage: 'This paper is classified above your clearance.',
  },
  fileUnknown: { id: 'agendaBuilder.problem.fileUnknown', defaultMessage: 'The file was not found.' },
  tooManyPapers: { id: 'agendaBuilder.problem.tooManyPapers', defaultMessage: 'An item has at most 20 papers.' },
})

/** Messages with values. */
export const apv = defineMessages<{
  outOfPackFile: { title: string | number; source: string | number }
  pageTitle: { name: string | number }
  lastPublished: { revision: string | number }
  papersDue: { date: string | number }
  publishRevision: { revision: string | number }
  meetingOption: { name: string | number; date: string | number }
  copyDropped: { count: string | number; names: string | number }
  copySkipped: { count: string | number; titles: string | number }
  total: { total: string | number; ends: string | number; planned: string | number }
  overrun: { total: string | number; ends: string | number; minutes: string | number }
  subItemsOf: { title: string | number }
  presenters: { names: string | number }
  papers: { count: string | number; pages: string | number }
  pages: { count: string | number }
  restricted: { count: string | number }
  restrictedHidden: { count: string | number }
  itemActions: { title: string | number }
  sectionActions: { title: string | number }
  dragHandle: { title: string | number }
  dragTray: { title: string | number }
  dragStart: { title: string | number }
  dragOver: { title: string | number; position: string | number; section: string | number }
  dragEnd: { title: string | number; position: string | number; section: string | number }
  dragOutside: { title: string | number }
  dragCancel: { title: string | number }
  movedNumbered: {
    number: string | number
    title: string | number
    position: string | number
    section: string | number
  }
  moved: { title: string | number; position: string | number; section: string | number }
  movedUnder: { title: string | number; position: string | number; parent: string | number }
  addedTo: { title: string | number; position: string | number; section: string | number }
  addedUnder: { title: string | number; position: string | number; parent: string | number }
  removed: { title: string | number }
  moveToSectionTitle: { title: string | number }
  moveToPositionTitle: { title: string | number }
  positionLead: { count: string | number; where: string | number }
  positionRange: { count: string | number }
  underItem: { title: string | number }
  removeTitle: { title: string | number }
  removeSectionTitle: { title: string | number }
  editSectionTitle: { title: string | number }
  sectionAdded: { title: string | number }
  consentOn: { title: string | number }
  consentOff: { title: string | number }
  membersOnlyOn: { title: string | number }
  membersOnlyOff: { title: string | number }
  landsHereOn: { title: string | number }
  addSubItemTitle: { title: string | number }
  sheetTitle: { number: string | number; title: string | number }
  fromQueue: { reference: string | number }
  itemSaved: { title: string | number }
  removePerson: { name: string | number }
  paperRestricted: { count: string | number }
  paperActions: { title: string | number }
  removePaperTitle: { title: string | number }
  renamePaperTitle: { title: string | number }
  paperAccessTitle: { title: string | number }
  replaceTitle: { title: string | number }
  trayTitle: { count: string | number }
  acceptedOn: { date: string | number }
  addToAgendaNamed: { title: string | number }
  placeTitle: { title: string | number }
  publishTitle: { revision: string | number }
  publishChanges: { revision: string | number }
  changeItem: { number: string | number; title: string | number }
  changeLine: { flags: string | number; item: string | number }
  changeLineWas: { flags: string | number; item: string | number; was: string | number }
  awaitingLeftOut: { count: string | number; titles: string | number }
  published: { revision: string | number }
  PAPER_NOT_READABLE: { count: string | number; titles: string | number }
  agreed: { chair: string | number; when: string | number }
  agreedOnBehalf: { chair: string | number; when: string | number; secretary: string | number; note: string | number }
  commentBy: { name: string | number; when: string | number }
  paperOfItem: { paper: string | number; item: string | number }
  dueOn: { date: string | number }
  lateAgreed: { title: string | number }
  lateDeclined: { title: string | number }
  declineTitle: { title: string | number }
  recordAnswerTitle: { title: string | number }
  previewTitle: { name: string | number }
  previewBanner: { name: string | number }
  titleRestricted: { title: string | number }
}>({
  outOfPackFile: { id: 'agendaBuilder.papers.outOfPackFile', defaultMessage: '{title} ({source})' },
  pageTitle: { id: 'agendaBuilder.v.pageTitle', defaultMessage: 'Agenda: {name}' },
  lastPublished: { id: 'agendaBuilder.v.lastPublished', defaultMessage: 'Draft · last published: revision {revision}' },
  papersDue: { id: 'agendaBuilder.v.papersDue', defaultMessage: 'Papers due {date}' },
  publishRevision: { id: 'agendaBuilder.v.publishRevision', defaultMessage: 'Publish revision {revision}' },
  meetingOption: { id: 'agendaBuilder.v.meetingOption', defaultMessage: '{name} — {date}' },
  copyDropped: {
    id: 'agendaBuilder.v.copyDropped',
    defaultMessage:
      '{count, plural, one {# restriction named someone} other {# restrictions named people}} not on this meeting, now left out: {names}.',
  },
  copySkipped: {
    id: 'agendaBuilder.v.copySkipped',
    defaultMessage:
      '{count, plural, one {# item was} other {# items were}} not copied, because nobody on this meeting may see {count, plural, one {it} other {them}}: {titles}.',
  },
  total: { id: 'agendaBuilder.v.total', defaultMessage: 'Total {total} · ends {ends} · planned end {planned}' },
  overrun: {
    id: 'agendaBuilder.v.overrun',
    defaultMessage:
      'Total {total}: ends {ends}, {minutes, plural, one {# minute} other {# minutes}} after the planned end.',
  },
  subItemsOf: { id: 'agendaBuilder.v.subItemsOf', defaultMessage: 'Sub-items of {title}' },
  presenters: { id: 'agendaBuilder.v.presenters', defaultMessage: 'Presenter: {names}' },
  papers: {
    id: 'agendaBuilder.v.papers',
    defaultMessage:
      '{count, plural, one {# paper} other {# papers}}{pages, plural, =0 {} one { (# page)} other { (# pages)}}',
  },
  pages: { id: 'agendaBuilder.v.pages', defaultMessage: '{count, plural, one {# page} other {# pages}}' },
  restricted: {
    id: 'agendaBuilder.v.restricted',
    defaultMessage: 'Only {count, plural, one {# person} other {# people}} can see this',
  },
  restrictedHidden: {
    id: 'agendaBuilder.v.restrictedHidden',
    defaultMessage: 'Only {count, plural, one {# person} other {# people}} can see this · title hidden from others',
  },
  itemActions: { id: 'agendaBuilder.v.itemActions', defaultMessage: 'Actions for {title}' },
  sectionActions: { id: 'agendaBuilder.v.sectionActions', defaultMessage: 'Actions for the section {title}' },
  dragHandle: { id: 'agendaBuilder.v.dragHandle', defaultMessage: 'Drag to move: {title}' },
  dragTray: { id: 'agendaBuilder.v.dragTray', defaultMessage: 'Drag onto the agenda: {title}' },
  dragStart: { id: 'agendaBuilder.v.dragStart', defaultMessage: 'Picked up {title}.' },
  dragOver: { id: 'agendaBuilder.v.dragOver', defaultMessage: '{title}: position {position} in {section}.' },
  dragEnd: { id: 'agendaBuilder.v.dragEnd', defaultMessage: '{title} dropped at position {position} in {section}.' },
  dragOutside: { id: 'agendaBuilder.v.dragOutside', defaultMessage: '{title} is not over the agenda.' },
  dragCancel: { id: 'agendaBuilder.v.dragCancel', defaultMessage: 'Moving {title} was cancelled; nothing changed.' },
  movedNumbered: {
    id: 'agendaBuilder.v.movedNumbered',
    defaultMessage: 'Item {number}, {title}, moved to position {position} in {section}.',
  },
  moved: { id: 'agendaBuilder.v.moved', defaultMessage: '{title} moved to position {position} in {section}.' },
  movedUnder: { id: 'agendaBuilder.v.movedUnder', defaultMessage: '{title} is now sub-item {position} of {parent}.' },
  addedTo: { id: 'agendaBuilder.v.addedTo', defaultMessage: '{title} added at position {position} in {section}.' },
  addedUnder: { id: 'agendaBuilder.v.addedUnder', defaultMessage: '{title} added as sub-item {position} of {parent}.' },
  removed: { id: 'agendaBuilder.v.removed', defaultMessage: '{title} removed.' },
  moveToSectionTitle: { id: 'agendaBuilder.v.moveToSectionTitle', defaultMessage: 'Move {title} to another section' },
  moveToPositionTitle: { id: 'agendaBuilder.v.moveToPositionTitle', defaultMessage: 'Move {title} to a position' },
  positionLead: {
    id: 'agendaBuilder.v.positionLead',
    defaultMessage: 'A position from 1 to {count} in {where}.',
  },
  positionRange: { id: 'agendaBuilder.v.positionRange', defaultMessage: 'Give a number from 1 to {count}.' },
  underItem: { id: 'agendaBuilder.v.underItem', defaultMessage: 'the sub-items of {title}' },
  removeTitle: { id: 'agendaBuilder.v.removeTitle', defaultMessage: 'Remove {title} from the agenda?' },
  removeSectionTitle: { id: 'agendaBuilder.v.removeSectionTitle', defaultMessage: 'Remove the section {title}?' },
  editSectionTitle: { id: 'agendaBuilder.v.editSectionTitle', defaultMessage: 'Section: {title}' },
  sectionAdded: { id: 'agendaBuilder.v.sectionAdded', defaultMessage: 'Section {title} added.' },
  consentOn: { id: 'agendaBuilder.v.consentOn', defaultMessage: '{title} is now a consent section.' },
  consentOff: { id: 'agendaBuilder.v.consentOff', defaultMessage: '{title} is now an ordinary section.' },
  membersOnlyOn: { id: 'agendaBuilder.v.membersOnlyOn', defaultMessage: '{title} is now for members only.' },
  membersOnlyOff: { id: 'agendaBuilder.v.membersOnlyOff', defaultMessage: '{title} is no longer for members only.' },
  landsHereOn: { id: 'agendaBuilder.v.landsHereOn', defaultMessage: 'New items now land in {title}.' },
  addSubItemTitle: { id: 'agendaBuilder.v.addSubItemTitle', defaultMessage: 'Add a sub-item to {title}' },
  sheetTitle: { id: 'agendaBuilder.v.sheetTitle', defaultMessage: 'Item {number} · {title}' },
  fromQueue: { id: 'agendaBuilder.v.fromQueue', defaultMessage: 'From the queue: {reference}' },
  itemSaved: { id: 'agendaBuilder.v.itemSaved', defaultMessage: '{title} saved.' },
  removePerson: { id: 'agendaBuilder.v.removePerson', defaultMessage: 'Remove {name}' },
  paperRestricted: {
    id: 'agendaBuilder.v.paperRestricted',
    defaultMessage: 'Only {count, plural, one {# person} other {# people}}',
  },
  paperActions: { id: 'agendaBuilder.v.paperActions', defaultMessage: 'Actions for the paper {title}' },
  removePaperTitle: { id: 'agendaBuilder.v.removePaperTitle', defaultMessage: 'Remove {title} from the pack?' },
  renamePaperTitle: { id: 'agendaBuilder.v.renamePaperTitle', defaultMessage: 'Rename {title}' },
  paperAccessTitle: { id: 'agendaBuilder.v.paperAccessTitle', defaultMessage: 'Who can see {title}' },
  replaceTitle: { id: 'agendaBuilder.v.replaceTitle', defaultMessage: 'Replace {title}' },
  trayTitle: { id: 'agendaBuilder.v.trayTitle', defaultMessage: 'Ready for an agenda ({count})' },
  acceptedOn: { id: 'agendaBuilder.v.acceptedOn', defaultMessage: 'Accepted {date}' },
  addToAgendaNamed: { id: 'agendaBuilder.v.addToAgendaNamed', defaultMessage: 'Add to agenda: {title}' },
  placeTitle: { id: 'agendaBuilder.v.placeTitle', defaultMessage: 'Add {title} to the agenda' },
  publishTitle: { id: 'agendaBuilder.v.publishTitle', defaultMessage: 'Publish revision {revision} of the agenda' },
  publishChanges: {
    id: 'agendaBuilder.v.publishChanges',
    defaultMessage: 'What changed since revision {revision} (members see only what they are allowed to see)',
  },
  changeItem: { id: 'agendaBuilder.v.changeItem', defaultMessage: 'Item {number} {title}' },
  changeLine: { id: 'agendaBuilder.v.changeLine', defaultMessage: '{flags}: {item}' },
  changeLineWas: { id: 'agendaBuilder.v.changeLineWas', defaultMessage: '{flags}: {item} (was {was})' },
  awaitingLeftOut: {
    id: 'agendaBuilder.v.awaitingLeftOut',
    defaultMessage:
      '{count, plural, one {# item is} other {# items are}} waiting for the chair and will not be published yet: {titles}.',
  },
  published: { id: 'agendaBuilder.v.published', defaultMessage: 'Revision {revision} of the agenda is published.' },
  PAPER_NOT_READABLE: {
    id: 'agendaBuilder.v.paperNotReadable',
    defaultMessage:
      '{count, plural, =0 {Some papers cannot be shown as a PDF. Replace or remove them, then publish.} one {A paper cannot be shown as a PDF: {titles}. Replace or remove it, then publish.} other {Some papers cannot be shown as a PDF: {titles}. Replace or remove them, then publish.}}',
  },
  agreed: { id: 'agendaBuilder.v.agreed', defaultMessage: 'Chair’s agreement: {chair}, {when}' },
  agreedOnBehalf: {
    id: 'agendaBuilder.v.agreedOnBehalf',
    defaultMessage: 'Chair’s agreement: {chair}, {when} (recorded by {secretary}: {note})',
  },
  commentBy: { id: 'agendaBuilder.v.commentBy', defaultMessage: '{name}, {when}' },
  paperOfItem: { id: 'agendaBuilder.v.paperOfItem', defaultMessage: '{paper} (item: {item})' },
  dueOn: { id: 'agendaBuilder.v.dueOn', defaultMessage: 'Papers were due on {date}.' },
  lateAgreed: {
    id: 'agendaBuilder.v.lateAgreed',
    defaultMessage: '{title} is agreed and will be published as a late paper.',
  },
  lateDeclined: { id: 'agendaBuilder.v.lateDeclined', defaultMessage: '{title} is not for this meeting.' },
  declineTitle: { id: 'agendaBuilder.v.declineTitle', defaultMessage: 'Not this meeting: {title}?' },
  recordAnswerTitle: { id: 'agendaBuilder.v.recordAnswerTitle', defaultMessage: 'The chair’s answer: {title}' },
  previewTitle: { id: 'agendaBuilder.v.previewTitle', defaultMessage: 'Preview as {name}' },
  previewBanner: {
    id: 'agendaBuilder.v.previewBanner',
    defaultMessage: 'This is what {name} would see if you published now.',
  },
  titleRestricted: { id: 'agendaBuilder.v.titleRestricted', defaultMessage: '{title} — papers restricted' },
})

/** Messages that name the committee (the organisation's terms). */
export const apt = defineMessages<
  Record<'PAPER_DEADLINE_PASSED' | 'NO_CHAIR' | 'notInAudience' | 'fromTemplate', TermValues>
>({
  fromTemplate: { id: 'agendaBuilder.t.fromTemplate', defaultMessage: 'The {forum}’s standard agenda' },

  PAPER_DEADLINE_PASSED: {
    id: 'agendaBuilder.t.deadlinePassed',
    defaultMessage: 'Late papers are not accepted for this {forum}. Choose a later meeting.',
  },
  NO_CHAIR: {
    id: 'agendaBuilder.t.noChair',
    defaultMessage: 'The {forum} has no chair or vice-chair in office, so there is nobody to agree.',
  },
  notInAudience: {
    id: 'agendaBuilder.t.notInAudience',
    defaultMessage: 'This person is not attending the meeting of this {forum}.',
  },
})

export const purposeLabels = defineMessages({
  DECISION: { id: 'agendaBuilder.purpose.decision', defaultMessage: 'For decision' },
  DISCUSSION: { id: 'agendaBuilder.purpose.discussion', defaultMessage: 'For discussion' },
  INFORMATION: { id: 'agendaBuilder.purpose.information', defaultMessage: 'For noting' },
})

export const sourceLabels = defineMessages({
  MEMO_DOCUMENT: { id: 'agendaBuilder.source.memoDocument', defaultMessage: 'memo document' },
  MEMO_ATTACHMENT: { id: 'agendaBuilder.source.memoAttachment', defaultMessage: 'attachment' },
  MEMO_RECORD: { id: 'agendaBuilder.source.memoRecord', defaultMessage: 'memo record' },
  UPLOAD: { id: 'agendaBuilder.source.upload', defaultMessage: 'uploaded by the secretary' },
})

export const flagLabels = defineMessages({
  ADDED: { id: 'agendaBuilder.flag.added', defaultMessage: 'Added' },
  REMOVED: { id: 'agendaBuilder.flag.removed', defaultMessage: 'Removed' },
  MOVED: { id: 'agendaBuilder.flag.moved', defaultMessage: 'Moved' },
  RETITLED: { id: 'agendaBuilder.flag.retitled', defaultMessage: 'Retitled' },
  TIME_CHANGED: { id: 'agendaBuilder.flag.timeChanged', defaultMessage: 'Time changed' },
  PAPER_ADDED: { id: 'agendaBuilder.flag.paperAdded', defaultMessage: 'Paper added' },
  PAPER_REPLACED: { id: 'agendaBuilder.flag.paperReplaced', defaultMessage: 'Paper replaced' },
  PAPER_REMOVED: { id: 'agendaBuilder.flag.paperRemoved', defaultMessage: 'Paper removed' },
  LATE: { id: 'agendaBuilder.flag.late', defaultMessage: 'Late' },
  TABLED: { id: 'agendaBuilder.flag.tabled', defaultMessage: 'Tabled' },
})

export const STANDING_KINDS = ['APOLOGIES', 'DECLARATIONS', 'PREVIOUS_MINUTES', 'MATTERS_ARISING', 'AOB'] as const

export const standingLabels = defineMessages({
  APOLOGIES: { id: 'agendaBuilder.standing.apologies', defaultMessage: 'Welcome and apologies' },
  DECLARATIONS: { id: 'agendaBuilder.standing.declarations', defaultMessage: 'Declarations of interest' },
  PREVIOUS_MINUTES: { id: 'agendaBuilder.standing.previousMinutes', defaultMessage: 'Minutes of the last meeting' },
  MATTERS_ARISING: { id: 'agendaBuilder.standing.mattersArising', defaultMessage: 'Matters arising and actions' },
  AOB: { id: 'agendaBuilder.standing.aob', defaultMessage: 'Any other business' },
})
