import { defineMessages } from 'react-intl'
import type { TermValues } from '@/features/terms/terms'

/**
 * Texts of the committee-type admin (Phase 4 E01 T4, flow J); the `forumTypes` prefix loads with `/admin/committee-types`
 * (catalogue part `forumAdmin`). Messages that name the committee take the organisation's terms (ADR-0032): `{forum}`,
 * `{forums}`, `{Forum}`, `{Forums}` and `{forumGender}`.
 */
export const ft = defineMessages({
  colName: { id: 'forumTypes.col.name', defaultMessage: 'Name' },
  colCode: { id: 'forumTypes.col.code', defaultMessage: 'Code' },
  colPublished: { id: 'forumTypes.col.published', defaultMessage: 'Published' },
  colChanges: { id: 'forumTypes.col.changes', defaultMessage: 'Changes' },
  colStatus: { id: 'forumTypes.col.status', defaultMessage: 'Status' },
  notPublished: { id: 'forumTypes.notPublished', defaultMessage: 'Not yet' },
  noChanges: { id: 'forumTypes.noChanges', defaultMessage: 'None' },
  active: { id: 'forumTypes.active', defaultMessage: 'Active' },
  inactive: { id: 'forumTypes.inactive', defaultMessage: 'Switched off' },
  // New type
  name: { id: 'forumTypes.name', defaultMessage: 'Name' },
  code: { id: 'forumTypes.code', defaultMessage: 'Code' },
  codeHint: {
    id: 'forumTypes.code.hint',
    defaultMessage: 'Up to 10 capital letters, digits or hyphens. It cannot be changed later.',
  },
  codeFormat: { id: 'forumTypes.code.format', defaultMessage: 'Use 1 to 10 capital letters, digits or hyphens.' },
  startFrom: { id: 'forumTypes.startFrom', defaultMessage: 'Start from' },
  startFromHint: {
    id: 'forumTypes.startFrom.hint',
    defaultMessage: 'An example to edit, taken from published charters. Nothing applies until it is reviewed.',
  },
  blank: { id: 'forumTypes.preset.blank', defaultMessage: 'Blank' },
  BOARD: { id: 'forumTypes.preset.board', defaultMessage: 'Board' },
  BOARD_COMMITTEE: { id: 'forumTypes.preset.boardCommittee', defaultMessage: 'Board committee' },
  MANAGEMENT_COMMITTEE: { id: 'forumTypes.preset.managementCommittee', defaultMessage: 'Management committee' },
  create: { id: 'forumTypes.create', defaultMessage: 'Create draft' },
  cancel: { id: 'forumTypes.cancel', defaultMessage: 'Cancel' },
  required: { id: 'forumTypes.required', defaultMessage: 'Required.' },
  failed: { id: 'forumTypes.failed', defaultMessage: 'That did not work. Try again.' },
  // Type page
  versionLabel: { id: 'forumTypes.version.label', defaultMessage: 'Version' },
  newDraft: { id: 'forumTypes.newDraft', defaultMessage: 'New draft' },
  switchOff: { id: 'forumTypes.switchOff', defaultMessage: 'Switch off' },
  switchOn: { id: 'forumTypes.switchOn', defaultMessage: 'Switch on' },
  readOnly: {
    id: 'forumTypes.readOnly',
    defaultMessage: 'Only a draft can be changed. Start a new draft to change this type.',
  },
  save: { id: 'forumTypes.save', defaultMessage: 'Save' },
  submit: { id: 'forumTypes.submit', defaultMessage: 'Submit for review' },
  unsaved: { id: 'forumTypes.unsaved', defaultMessage: 'Unsaved changes. Save before submitting.' },
  approve: { id: 'forumTypes.approve', defaultMessage: 'Approve and publish' },
  sendBack: { id: 'forumTypes.sendBack', defaultMessage: 'Send back' },
  remark: { id: 'forumTypes.remark', defaultMessage: 'Remark' },
  remarkHint: { id: 'forumTypes.remark.hint', defaultMessage: 'Required to send it back; optional when approving.' },
  done: { id: 'forumTypes.done', defaultMessage: 'Saved.' },
  conflict: {
    id: 'forumTypes.conflict',
    defaultMessage: 'Someone else changed this version. It has been reloaded with their changes.',
  },
  invalid: { id: 'forumTypes.invalid', defaultMessage: 'Fix these before saving:' },
  SAME_PERSON: {
    id: 'forumTypes.reason.samePerson',
    defaultMessage: 'Someone other than the submitter has to review it.',
  },
  DRAFT_EXISTS: {
    id: 'forumTypes.reason.draftExists',
    defaultMessage: 'This type already has a draft or a version in review.',
  },
  WRONG_STATE: {
    id: 'forumTypes.reason.wrongState',
    defaultMessage: 'This version has moved on. Reload and try again.',
  },
  tabPolicy: { id: 'forumTypes.tab.policy', defaultMessage: 'Policy' },
  tabQuorum: { id: 'forumTypes.tab.quorum', defaultMessage: 'Quorum' },
  tabOutcomes: { id: 'forumTypes.tab.outcomes', defaultMessage: 'Outcomes' },
  tabTerms: { id: 'forumTypes.tab.terms', defaultMessage: 'Terms' },
  // Policy
  description: { id: 'forumTypes.description', defaultMessage: 'Description' },
  classification: { id: 'forumTypes.classification', defaultMessage: 'Default classification' },
  lowest: { id: 'forumTypes.lowest', defaultMessage: 'Lowest level' },
  retention: { id: 'forumTypes.retention', defaultMessage: 'Keep records for (years after the meeting)' },
  retentionHint: {
    id: 'forumTypes.retention.hint',
    defaultMessage: 'Kept now, acted on later by records management. Leave it empty for no policy yet.',
  },
  attributes: { id: 'forumTypes.attributes', defaultMessage: 'Member attributes' },
  attributesHint: { id: 'forumTypes.attributes.hint', defaultMessage: 'Used in quorum rules and shown on the roster.' },
  attributeIndependent: { id: 'forumTypes.attribute.independent', defaultMessage: 'Independent' },
  attributeNonExecutive: { id: 'forumTypes.attribute.nonExecutive', defaultMessage: 'Non-executive' },
  attributeManagement: { id: 'forumTypes.attribute.management', defaultMessage: 'Management' },
  addAttribute: { id: 'forumTypes.attribute.add', defaultMessage: 'Add attribute' },
  attributeLabel: { id: 'forumTypes.attribute.label', defaultMessage: 'Label' },
  attributeKey: { id: 'forumTypes.attribute.key', defaultMessage: 'Key' },
  attributeKeyHint: {
    id: 'forumTypes.attribute.key.hint',
    defaultMessage: 'A small letter, then letters or digits (for example nonExecutive). Quorum rules use it.',
  },
  attributeKeyFormat: {
    id: 'forumTypes.attribute.key.format',
    defaultMessage: 'Start with a small letter; then letters or digits, 30 at most.',
  },
  attributeKeyTaken: { id: 'forumTypes.attribute.key.taken', defaultMessage: 'Another attribute has this key.' },
  keepAttribute: { id: 'forumTypes.attribute.keep', defaultMessage: 'Add' },
  attributesFull: { id: 'forumTypes.attributes.full', defaultMessage: 'A type has at most 8 attributes.' },
  // Quorum: builder
  quorumBuilder: { id: 'forumTypes.quorum.builder', defaultMessage: 'Quorum rule' },
  whoCounts: { id: 'forumTypes.quorum.whoCounts', defaultMessage: 'Who counts' },
  countsVoting: { id: 'forumTypes.quorum.counts.voting', defaultMessage: 'Voting members' },
  countsAll: { id: 'forumTypes.quorum.counts.all', defaultMessage: 'All members' },
  countsHint: {
    id: 'forumTypes.quorum.counts.hint',
    defaultMessage: 'Observers and standing invitees never count.',
  },
  outOf: { id: 'forumTypes.quorum.outOf', defaultMessage: 'Out of' },
  basisInOffice: { id: 'forumTypes.quorum.basis.inOffice', defaultMessage: 'Members in office on the day' },
  basisSeats: { id: 'forumTypes.quorum.basis.seats', defaultMessage: 'A fixed number of seats' },
  seats: { id: 'forumTypes.quorum.seats', defaultMessage: 'Number of seats' },
  howMany: { id: 'forumTypes.quorum.howMany', defaultMessage: 'How many' },
  thresholdMajority: { id: 'forumTypes.quorum.threshold.majority', defaultMessage: 'More than half' },
  thresholdFraction: { id: 'forumTypes.quorum.threshold.fraction', defaultMessage: 'A fraction' },
  thresholdCount: { id: 'forumTypes.quorum.threshold.count', defaultMessage: 'A fixed number' },
  numerator: { id: 'forumTypes.quorum.numerator', defaultMessage: 'Fraction, top number' },
  denominator: { id: 'forumTypes.quorum.denominator', defaultMessage: 'Fraction, bottom number' },
  roundedUp: { id: 'forumTypes.quorum.roundedUp', defaultMessage: 'rounded up' },
  fixedNumber: { id: 'forumTypes.quorum.fixedNumber', defaultMessage: 'Fixed number of members' },
  minimum: { id: 'forumTypes.quorum.minimum', defaultMessage: 'Never fewer than' },
  mustInclude: { id: 'forumTypes.quorum.mustInclude', defaultMessage: 'Must include' },
  noRequirement: { id: 'forumTypes.quorum.required.none', defaultMessage: 'No particular role has to be there.' },
  anyOne: { id: 'forumTypes.quorum.required.anyOne', defaultMessage: '(any one of them)' },
  addRole: { id: 'forumTypes.quorum.required.addRole', defaultMessage: 'Add a role…' },
  addRequirement: { id: 'forumTypes.quorum.required.add', defaultMessage: 'Add a requirement' },
  addAnotherRequirement: { id: 'forumTypes.quorum.required.addAnother', defaultMessage: 'Add another requirement' },
  ofThosePresent: { id: 'forumTypes.quorum.composition', defaultMessage: 'Of those present' },
  noCondition: { id: 'forumTypes.quorum.composition.none', defaultMessage: 'No condition on who they are.' },
  atLeast: { id: 'forumTypes.quorum.composition.atLeast', defaultMessage: 'at least' },
  aMajority: { id: 'forumTypes.quorum.composition.majority', defaultMessage: 'a majority' },
  mustBe: { id: 'forumTypes.quorum.composition.mustBe', defaultMessage: 'must be' },
  addCondition: { id: 'forumTypes.quorum.composition.add', defaultMessage: 'Add condition' },
  needAttributes: {
    id: 'forumTypes.quorum.composition.needAttributes',
    defaultMessage: 'Add member attributes on the Policy tab first.',
  },
  alternatesCount: {
    id: 'forumTypes.quorum.alternatesCount',
    defaultMessage: 'Alternates count for the member they replace',
  },
  remoteCounts: { id: 'forumTypes.quorum.remoteCounts', defaultMessage: 'Members attending remotely count' },
  recusedCount: {
    id: 'forumTypes.quorum.recusedCount',
    defaultMessage: 'Members who declared a conflict on an item count for that item',
  },
  CHAIR: { id: 'forumTypes.role.chair', defaultMessage: 'Chair' },
  VICE_CHAIR: { id: 'forumTypes.role.viceChair', defaultMessage: 'Vice-chair' },
  SECRETARY: { id: 'forumTypes.role.secretary', defaultMessage: 'Secretary' },
  MEMBER: { id: 'forumTypes.role.member', defaultMessage: 'Member' },
  // Quorum: sentence
  inWords: { id: 'forumTypes.quorum.inWords', defaultMessage: 'In words' },
  example: { id: 'forumTypes.quorum.example', defaultMessage: 'Example' },
  tryIt: { id: 'forumTypes.quorum.tryIt', defaultMessage: 'Try it' },
  tryItLead: {
    id: 'forumTypes.quorum.tryIt.lead',
    defaultMessage: 'Sample numbers only; nothing is saved.',
  },
  previewUnavailable: {
    id: 'forumTypes.quorum.previewUnavailable',
    defaultMessage: 'Fix the rule to see the example and try it.',
  },
  whoVotingInOffice: { id: 'forumTypes.quorum.who.votingInOffice', defaultMessage: 'voting members in office' },
  whoAllInOffice: { id: 'forumTypes.quorum.who.allInOffice', defaultMessage: 'members in office' },
  CHAIR_phrase: { id: 'forumTypes.quorum.role.chair', defaultMessage: 'the chair' },
  VICE_CHAIR_phrase: { id: 'forumTypes.quorum.role.viceChair', defaultMessage: 'a vice-chair' },
  SECRETARY_phrase: { id: 'forumTypes.quorum.role.secretary', defaultMessage: 'a secretary' },
  MEMBER_phrase: { id: 'forumTypes.quorum.role.member', defaultMessage: 'a member' },
  STANDING_INVITEE_phrase: { id: 'forumTypes.quorum.role.standingInvitee', defaultMessage: 'a standing invitee' },
  OBSERVER_phrase: { id: 'forumTypes.quorum.role.observer', defaultMessage: 'an observer' },
  // Flow Z (Phase 4 E05 T8, ADR-0064): an alternate never meets a role or an attribute condition.
  alternatesYes: {
    id: 'forumTypes.quorum.alternates.numberOnly',
    defaultMessage: 'Alternates count for the member they replace, for the number of members only.',
  },
  alternatesNo: { id: 'forumTypes.quorum.alternates.no', defaultMessage: 'Alternates do not count.' },
  remoteYes: { id: 'forumTypes.quorum.remote.yes', defaultMessage: 'Members attending remotely count.' },
  remoteNo: { id: 'forumTypes.quorum.remote.no', defaultMessage: 'Only members in the room count.' },
  recusedYes: {
    id: 'forumTypes.quorum.recused.yes',
    defaultMessage: 'Members with a conflict on an item still count for that item.',
  },
  recusedNo: {
    id: 'forumTypes.quorum.recused.no',
    defaultMessage: 'Members with a conflict on an item do not count for that item.',
  },
  // Quorum: tester
  testerInOfficeVoting: { id: 'forumTypes.tester.inOffice.voting', defaultMessage: 'Voting members in office' },
  testerInOfficeAll: { id: 'forumTypes.tester.inOffice.all', defaultMessage: 'Members in office' },
  testerPresent: { id: 'forumTypes.tester.present', defaultMessage: 'Present' },
  fractionBar: { id: 'forumTypes.quorum.fractionBar', defaultMessage: '/' },
  notQuorate: { id: 'forumTypes.tester.notQuorate', defaultMessage: 'Not quorate' },
  // Outcomes
  outcomesCaption: { id: 'forumTypes.outcomes.caption', defaultMessage: 'Outcomes and their labels' },
  colOn: { id: 'forumTypes.outcomes.col.on', defaultMessage: 'On' },
  colMeaning: { id: 'forumTypes.outcomes.col.meaning', defaultMessage: 'Meaning (fixed)' },
  colLabel: { id: 'forumTypes.outcomes.col.label', defaultMessage: 'Label' },
  colResolution: { id: 'forumTypes.outcomes.col.resolution', defaultMessage: 'Numbered as a resolution' },
  outcomesHelp: {
    id: 'forumTypes.outcomes.help',
    defaultMessage: 'What each meaning does to a memo waiting for the decision',
  },
  // Terms
  termMonths: { id: 'forumTypes.terms.months', defaultMessage: 'Usual term of office (months)' },
  termMonthsHint: {
    id: 'forumTypes.terms.months.hint',
    defaultMessage: 'Filled in when a member is added; it can be changed for each member.',
  },
  maxTerms: { id: 'forumTypes.terms.maxTerms', defaultMessage: 'Most consecutive terms' },
  maxTermsHint: { id: 'forumTypes.terms.maxTerms.hint', defaultMessage: 'A warning on renewal, never a block.' },
  reminders: { id: 'forumTypes.terms.reminders', defaultMessage: 'Remind the secretary' },
  remindersHint: {
    id: 'forumTypes.terms.reminders.hint',
    defaultMessage: 'Days before a term ends; up to three reminders.',
  },
  noReminders: { id: 'forumTypes.terms.reminders.none', defaultMessage: 'No reminders.' },
  addReminder: { id: 'forumTypes.terms.reminders.add', defaultMessage: 'Add a reminder' },
  daysBefore: { id: 'forumTypes.terms.daysBefore', defaultMessage: 'days before a term ends' },
  // Papers for meetings (Phase 4 E02 T4, ADR-0059)
  papers: { id: 'forumTypes.papers', defaultMessage: 'Papers for meetings' },
  papersLead: {
    id: 'forumTypes.papers.lead',
    defaultMessage: 'Working days follow the organisation’s business calendar.',
  },
  deadline: { id: 'forumTypes.papers.deadline', defaultMessage: 'Papers due (working days before a meeting)' },
  deadlineHint: {
    id: 'forumTypes.papers.deadline.hint',
    defaultMessage: 'From 0 (the day of the meeting) to 30. A paper aimed at a closer meeting is late.',
  },
  lateMode: { id: 'forumTypes.papers.lateMode', defaultMessage: 'Late papers' },
  lateHint: {
    id: 'forumTypes.papers.lateMode.hint',
    defaultMessage: 'Applies when a paper goes on an agenda after the deadline.',
  },
  ALLOW: { id: 'forumTypes.papers.late.allow', defaultMessage: 'Allowed, marked late' },
  BLOCK: { id: 'forumTypes.papers.late.block', defaultMessage: 'Held for the next meeting' },
  CHAIR_APPROVAL: { id: 'forumTypes.papers.late.chair', defaultMessage: 'Allowed only if the chair agrees' },
  triage: { id: 'forumTypes.papers.triage', defaultMessage: 'Remind the secretaries after (working days)' },
  triageHint: {
    id: 'forumTypes.papers.triage.hint',
    defaultMessage: 'From 1 to 30. One reminder when a paper has waited this long without being triaged.',
  },
  // Changes
  changesLead: {
    id: 'forumTypes.changes.lead',
    defaultMessage: 'What this version changes compared with the published one.',
  },
  changesNone: { id: 'forumTypes.changes.none', defaultMessage: 'No changes.' },
  changesFirst: {
    id: 'forumTypes.changes.first',
    defaultMessage: 'The first version of this type: everything is new.',
  },
  changesList: { id: 'forumTypes.changes.list', defaultMessage: 'Changes' },
  changeTo: { id: 'forumTypes.changes.to', defaultMessage: 'changes to' },
  added: { id: 'forumTypes.changes.added', defaultMessage: 'Added' },
  removed: { id: 'forumTypes.changes.removed', defaultMessage: 'Removed' },
  none: { id: 'forumTypes.changes.empty', defaultMessage: 'none' },
  yes: { id: 'forumTypes.yes', defaultMessage: 'Yes' },
  no: { id: 'forumTypes.no', defaultMessage: 'No' },
  whatQuorum: { id: 'forumTypes.changes.quorum', defaultMessage: 'Quorum' },
  whatReminders: { id: 'forumTypes.changes.reminders', defaultMessage: 'Reminders (days before a term ends)' },
  whatChairAgreement: {
    id: 'forumTypes.changes.chairAgreement',
    defaultMessage: 'Agenda: the chair agrees it before it is first published',
  },
  whatAgendaOrder: { id: 'forumTypes.changes.agendaOrder', defaultMessage: 'Agenda: order of the sections' },
  // The meeting policy (Phase 4 E05, design E05 §1.8): the Meetings tab (E05 T8, flow Z) and the changes view.
  tabMeetings: { id: 'forumTypes.tab.meetings', defaultMessage: 'Meetings' },
  meetingsInvitations: { id: 'forumTypes.meetings.invitations', defaultMessage: 'Calendar invitations' },
  meetingsTitle: { id: 'forumTypes.meetings.title', defaultMessage: 'Title in the calendar' },
  meetingsTitleNeutral: { id: 'forumTypes.meetings.title.neutral', defaultMessage: 'A neutral title' },
  meetingsNeutralTitle: { id: 'forumTypes.meetings.neutralTitle', defaultMessage: 'Neutral title' },
  meetingsNeutralTitleHint: {
    id: 'forumTypes.meetings.neutralTitle.hint',
    defaultMessage: 'For example “Board committee meeting”. At most 100 characters.',
  },
  meetingsReplies: { id: 'forumTypes.meetings.replies', defaultMessage: 'Replies' },
  meetingsDeadline: {
    id: 'forumTypes.meetings.deadline',
    defaultMessage: 'Ask for replies by (working days before the meeting)',
  },
  meetingsDeadlineHint: {
    id: 'forumTypes.meetings.deadline.hint',
    defaultMessage: 'From 0 (no deadline) to 10.',
  },
  meetingsReplyReminder: {
    id: 'forumTypes.meetings.replyReminder',
    defaultMessage: 'Remind those who have not replied, on the deadline day',
  },
  meetingsMeetingReminder: {
    id: 'forumTypes.meetings.meetingReminder',
    defaultMessage: 'Remind everyone attending the working day before the meeting',
  },
  meetingsOverride: { id: 'forumTypes.meetings.override', defaultMessage: 'Decisions without a quorum' },
  meetingsOverrideNever: { id: 'forumTypes.meetings.override.never', defaultMessage: 'Never allowed' },
  meetingsOverrideWithReason: {
    id: 'forumTypes.meetings.override.withReason',
    defaultMessage: 'Allowed with a reason, to be ratified at a later quorate meeting',
  },
  meetingsOverrideHint: {
    id: 'forumTypes.meetings.override.hint',
    defaultMessage:
      'The secretary gives the reason when recording the decision, and the chair and the other secretaries are told.',
  },
  whatInvitationTitle: { id: 'forumTypes.changes.invitationTitle', defaultMessage: 'Invitation title in calendars' },
  whatReplyDeadline: {
    id: 'forumTypes.changes.replyDeadline',
    defaultMessage: 'Reply deadline (working days before a meeting)',
  },
  whatReplyReminder: { id: 'forumTypes.changes.replyReminder', defaultMessage: 'Reminder to reply' },
  whatMeetingReminder: { id: 'forumTypes.changes.meetingReminder', defaultMessage: 'Reminder the working day before' },
  whatQuorumOverride: { id: 'forumTypes.changes.quorumOverride', defaultMessage: 'Decisions without a quorum' },
  quorumOverrideNever: { id: 'forumTypes.changes.quorumOverride.never', defaultMessage: 'Never' },
  quorumOverrideWithReason: {
    id: 'forumTypes.changes.quorumOverride.withReason',
    defaultMessage: 'With a reason, to be ratified later',
  },
  // The minutes policy (Phase 4 E06, design E06 §1.8); its tab comes with E06 T10.
  whatMinutesApproval: { id: 'forumTypes.changes.minutesApproval', defaultMessage: 'How minutes are approved' },
  minutesAtNextMeeting: {
    id: 'forumTypes.changes.minutesApproval.atNextMeeting',
    defaultMessage: 'At the next meeting',
  },
  minutesByCirculation: {
    id: 'forumTypes.changes.minutesApproval.byCirculation',
    defaultMessage: 'By circulation, then noted at the next meeting',
  },
  whatCirculationDays: {
    id: 'forumTypes.changes.circulationDays',
    defaultMessage: 'Working days to comment on circulated minutes',
  },
  whatDraftDays: {
    id: 'forumTypes.changes.draftDays',
    defaultMessage: 'Reminder when the minutes are not circulated (working days after a meeting)',
  },
  whatMemoResumes: { id: 'forumTypes.changes.memoResumes', defaultMessage: 'When a memo moves on after the decision' },
  memoResumesAtEnd: {
    id: 'forumTypes.changes.memoResumes.atEnd',
    defaultMessage: 'When the meeting is marked as held',
  },
  memoResumesOnApproval: {
    id: 'forumTypes.changes.memoResumes.onApproval',
    defaultMessage: 'When the minutes are approved',
  },
  whatResolutionNumbering: { id: 'forumTypes.changes.resolutionNumbering', defaultMessage: 'Resolution numbers' },
  resolutionResetYearly: {
    id: 'forumTypes.changes.resolutionNumbering.yearly',
    defaultMessage: 'starting again every calendar year',
  },
  resolutionResetNever: { id: 'forumTypes.changes.resolutionNumbering.never', defaultMessage: 'never starting again' },
  whatMinutesTemplate: { id: 'forumTypes.changes.minutesTemplate', defaultMessage: 'Minutes template' },
  minutesTemplateBuiltIn: { id: 'forumTypes.changes.minutesTemplate.builtIn', defaultMessage: 'The built-in template' },
  minutesTemplateUploaded: {
    id: 'forumTypes.changes.minutesTemplate.uploaded',
    defaultMessage: 'An uploaded template',
  },
  minutesTemplateNew: { id: 'forumTypes.changes.minutesTemplate.new', defaultMessage: 'A newly uploaded template' },
})

/** Texts with values besides the terms. */
export const ftv = defineMessages<{
  version: { number: number; state: string }
  versionShort: { number: number }
  published: { number: number }
  inReview: { number: number }
  inReviewBy: { number: number; name: string }
  draft: { number: number }
  submittedBy: { name: string; when: string }
  reviewRemark: { remark: string }
  tabChanges: { count: number }
  removeNamed: { name: string }
  changeWhat: { what: string }
  nameWithCode: { name: string; code: string }
  attributeUse: { label: string }
  attributeLabelOf: { key: string }
  attributeKeyShown: { key: string }
  requirement: { n: number }
  requirementAddRole: { n: number }
  requirementRemove: { n: number }
  conditionHowMany: { n: number }
  conditionNumber: { n: number }
  conditionAttribute: { n: number }
  conditionRemove: { n: number }
  fraction: { key: string; numerator: number; denominator: number; base: string }
  seatsBase: { seats: number }
  composition: { count: number; label: string }
  compositionMajority: { label: string }
  exampleInOffice: { basis: number; who: string; needed: number }
  exampleSeats: { basis: number; needed: number }
  rolePresent: { roles: string }
  attributePresent: { label: string }
  quorateDetail: { present: number; needed: number }
  unmetCount: { needed: number; actual: number }
  unmetRole: { roles: string }
  unmetComposition: { needed: number; label: string; actual: number }
  outcomeUse: { meaning: string }
  outcomeLabel: { meaning: string }
  outcomeResolution: { meaning: string }
  reminder: { n: number }
  reminderRemove: { n: number }
  whatAttribute: { label: string }
  whatOutcome: { meaning: string }
  whatOutcomeLabel: { meaning: string }
  whatOutcomeResolution: { meaning: string }
  whatAgendaSection: { title: string }
  whatAgendaItem: { section: string; title: string }
  agendaSectionValue: { kind: string; membersOnly: string; receives: string }
  agendaItemValue: { purpose: string; minutes: string }
  sentence: { amount: string; floor: string; including: string; composition: string }
  amountMajority: { base: string }
  amountCount: { count: number; base: string }
  floor: { minimum: number }
  including: { roles: string }
  invitationNeutral: { title: string }
  resolutionNumbering: { example: string; reset: string }
  resolutionResetFinancialYear: { month: string }
}>({
  resolutionNumbering: { id: 'forumTypes.changes.resolutionNumbering.value', defaultMessage: '{example}, {reset}' },
  resolutionResetFinancialYear: {
    id: 'forumTypes.changes.resolutionNumbering.financialYear',
    defaultMessage: 'starting again every financial year from {month}',
  },
  invitationNeutral: { id: 'forumTypes.changes.invitationNeutral', defaultMessage: 'A neutral title: {title}' },
  sentence: { id: 'forumTypes.quorum.sentence', defaultMessage: '{amount}{floor}{including}{composition}.' },
  amountMajority: { id: 'forumTypes.quorum.amount.majority', defaultMessage: 'More than half of the {base}' },
  amountCount: { id: 'forumTypes.quorum.amount.count', defaultMessage: 'At least {count} of the {base}' },
  floor: { id: 'forumTypes.quorum.floor', defaultMessage: ', and never fewer than {minimum}' },
  including: { id: 'forumTypes.quorum.including', defaultMessage: ', including {roles}' },
  version: { id: 'forumTypes.version', defaultMessage: 'v{number} · {state}' },
  versionShort: { id: 'forumTypes.version.short', defaultMessage: 'v{number}' },
  published: { id: 'forumTypes.published', defaultMessage: 'Published: v{number}' },
  inReview: { id: 'forumTypes.pending.inReview', defaultMessage: 'In review v{number}' },
  inReviewBy: { id: 'forumTypes.pending.inReviewBy', defaultMessage: 'In review v{number} · {name}' },
  draft: { id: 'forumTypes.pending.draft', defaultMessage: 'Draft v{number}' },
  submittedBy: { id: 'forumTypes.submittedBy', defaultMessage: 'Submitted by {name}, {when}' },
  reviewRemark: { id: 'forumTypes.reviewRemark', defaultMessage: 'Reviewer’s remark: {remark}' },
  tabChanges: {
    id: 'forumTypes.tab.changes',
    defaultMessage: '{count, plural, =0 {Changes} other {Changes (#)}}',
  },
  changeWhat: { id: 'forumTypes.changes.what', defaultMessage: '{what}:' },
  nameWithCode: { id: 'forumTypes.nameWithCode', defaultMessage: '{name} ({code})' },
  removeNamed: { id: 'forumTypes.removeNamed', defaultMessage: 'Remove {name}' },
  attributeUse: { id: 'forumTypes.attribute.use', defaultMessage: 'Use {label}' },
  attributeLabelOf: { id: 'forumTypes.attribute.labelOf', defaultMessage: 'Label of the attribute {key}' },
  attributeKeyShown: { id: 'forumTypes.attribute.keyShown', defaultMessage: 'key: {key}' },
  requirement: { id: 'forumTypes.quorum.requirement', defaultMessage: 'Requirement {n}' },
  requirementAddRole: { id: 'forumTypes.quorum.requirement.addRole', defaultMessage: 'Add a role to requirement {n}' },
  requirementRemove: { id: 'forumTypes.quorum.requirement.remove', defaultMessage: 'Remove requirement {n}' },
  conditionHowMany: { id: 'forumTypes.quorum.condition.howMany', defaultMessage: 'Condition {n}: how many' },
  conditionNumber: { id: 'forumTypes.quorum.condition.number', defaultMessage: 'Condition {n}: number' },
  conditionAttribute: { id: 'forumTypes.quorum.condition.attribute', defaultMessage: 'Condition {n}: attribute' },
  conditionRemove: { id: 'forumTypes.quorum.condition.remove', defaultMessage: 'Remove condition {n}' },
  fraction: {
    id: 'forumTypes.quorum.amount.fraction',
    defaultMessage:
      '{key, select, 1_2 {Half} 1_3 {A third} 2_3 {Two-thirds} 1_4 {A quarter} 3_4 {Three-quarters} other {{numerator}/{denominator}}} of the {base}, rounded up',
  },
  seatsBase: { id: 'forumTypes.quorum.seatsBase', defaultMessage: '{seats, plural, one {# seat} other {# seats}}' },
  composition: { id: 'forumTypes.quorum.compositionPart', defaultMessage: ', with at least {count} of them {label}' },
  compositionMajority: {
    id: 'forumTypes.quorum.compositionMajorityPart',
    defaultMessage: ', with a majority of them {label}',
  },
  exampleInOffice: {
    id: 'forumTypes.quorum.example.inOffice',
    defaultMessage: 'With {basis} {who}, {needed} must attend.',
  },
  exampleSeats: { id: 'forumTypes.quorum.example.seats', defaultMessage: 'With {basis} seats, {needed} must attend.' },
  rolePresent: { id: 'forumTypes.tester.rolePresent', defaultMessage: '{roles} present' },
  attributePresent: { id: 'forumTypes.tester.attributePresent', defaultMessage: '{label} among those present' },
  quorateDetail: {
    id: 'forumTypes.tester.quorateDetail',
    defaultMessage: 'Quorate: {present} present; {needed} needed.',
  },
  unmetCount: { id: 'forumTypes.tester.unmet.count', defaultMessage: 'Needs {needed} present, has {actual}.' },
  unmetRole: { id: 'forumTypes.tester.unmet.role', defaultMessage: 'Needs {roles} present.' },
  unmetComposition: {
    id: 'forumTypes.tester.unmet.composition',
    defaultMessage: 'Needs {needed} {label} members, has {actual}.',
  },
  outcomeUse: { id: 'forumTypes.outcomes.use', defaultMessage: 'Use {meaning}' },
  outcomeLabel: { id: 'forumTypes.outcomes.label', defaultMessage: 'Label for {meaning}' },
  outcomeResolution: {
    id: 'forumTypes.outcomes.resolution',
    defaultMessage: 'Number {meaning} decisions as resolutions',
  },
  reminder: { id: 'forumTypes.terms.reminder', defaultMessage: 'Reminder {n}, days before a term ends' },
  reminderRemove: { id: 'forumTypes.terms.reminder.remove', defaultMessage: 'Remove reminder {n}' },
  whatAttribute: { id: 'forumTypes.changes.attribute', defaultMessage: 'Member attribute “{label}”' },
  whatOutcome: { id: 'forumTypes.changes.outcome', defaultMessage: 'Outcome “{meaning}”' },
  whatOutcomeLabel: { id: 'forumTypes.changes.outcomeLabel', defaultMessage: 'Outcome “{meaning}” label' },
  whatOutcomeResolution: {
    id: 'forumTypes.changes.outcomeResolution',
    defaultMessage: 'Outcome “{meaning}” numbered as a resolution',
  },
  whatAgendaSection: { id: 'forumTypes.changes.agendaSection', defaultMessage: 'Agenda section “{title}”' },
  whatAgendaItem: { id: 'forumTypes.changes.agendaItem', defaultMessage: 'Agenda item “{title}” ({section})' },
  agendaSectionValue: {
    id: 'forumTypes.changes.agendaSectionValue',
    defaultMessage:
      '{kind, select, CONSENT {Consent section} other {Section}}{membersOnly, select, true {, members only} other {}}{receives, select, true {, new items land here} other {}}',
  },
  agendaItemValue: {
    id: 'forumTypes.changes.agendaItemValue',
    defaultMessage:
      '{purpose, select, DECISION {For decision} DISCUSSION {For discussion} other {For noting}}{minutes, select, none {} other {, {minutes} min}}',
  },
})

type TermMessage =
  | 'title'
  | 'lead'
  | 'add'
  | 'caption'
  | 'codeTaken'
  | 'back'
  | 'switchedOffHint'
  | 'appliesTo'
  | 'tabs'
  | 'outcomesLead'
  | 'APPROVED'
  | 'APPROVED_WITH_CONDITIONS'
  | 'REJECTED'
  | 'DEFERRED'
  | 'RETURNED'
  | 'REFERRED'
  | 'NOTED'
  | 'WITHDRAWN'
  | 'APPROVED_effect'
  | 'APPROVED_WITH_CONDITIONS_effect'
  | 'REJECTED_effect'
  | 'DEFERRED_effect'
  | 'RETURNED_effect'
  | 'REFERRED_effect'
  | 'NOTED_effect'
  | 'WITHDRAWN_effect'
  | 'invitationName'
  | 'meetingsNeutralLead'

/** Texts that name the committee: they take the organisation's terms (ADR-0032). */
export const ftt = defineMessages<Record<TermMessage, TermValues>>({
  meetingsNeutralLead: {
    id: 'forumTypes.meetings.neutral.lead',
    defaultMessage: 'Use a neutral title for {forums} whose name should not appear in people’s calendars.',
  },
  // The meeting policy's invitation title in the changes view and on the Meetings tab (Phase 4 E05).
  invitationName: {
    id: 'forumTypes.changes.invitationName',
    defaultMessage: "The meeting's title or the {forum}'s name",
  },
  title: { id: 'forumTypes.title', defaultMessage: '{Forum} types' },
  lead: {
    id: 'forumTypes.lead',
    defaultMessage:
      'The policy of each kind of {forum}: who makes a quorum, the decisions it records and how long members serve. Every change is reviewed by a second administrator before it applies.',
  },
  add: { id: 'forumTypes.add', defaultMessage: 'New {forum} type' },
  caption: { id: 'forumTypes.caption', defaultMessage: '{Forum} types of the organisation' },
  codeTaken: { id: 'forumTypes.code.taken', defaultMessage: 'Another {forum} type has this code.' },
  back: { id: 'forumTypes.back', defaultMessage: 'All {forum} types' },
  switchedOffHint: {
    id: 'forumTypes.switchedOff.hint',
    defaultMessage: 'Switched off: new {forums} cannot use this type. Those that do keep working.',
  },
  appliesTo: {
    id: 'forumTypes.appliesTo',
    defaultMessage: 'Changes apply to every {forum} of this type from its next meeting.',
  },
  tabs: { id: 'forumTypes.tabs', defaultMessage: 'Parts of the {forum} type' },
  outcomesLead: {
    id: 'forumTypes.outcomes.lead',
    defaultMessage: 'The decisions this {forum} records. The labels are yours; the meanings are fixed.',
  },
  APPROVED: { id: 'forumTypes.outcome.approved', defaultMessage: 'Approved' },
  APPROVED_WITH_CONDITIONS: {
    id: 'forumTypes.outcome.approvedWithConditions',
    defaultMessage: 'Approved with conditions',
  },
  REJECTED: { id: 'forumTypes.outcome.rejected', defaultMessage: 'Rejected' },
  DEFERRED: { id: 'forumTypes.outcome.deferred', defaultMessage: 'Deferred' },
  RETURNED: { id: 'forumTypes.outcome.returned', defaultMessage: 'Returned for more information' },
  REFERRED: {
    id: 'forumTypes.outcome.referred',
    defaultMessage: '{forumGender, select, other {Referred to another {forum}}}',
  },
  NOTED: { id: 'forumTypes.outcome.noted', defaultMessage: 'Noted' },
  WITHDRAWN: { id: 'forumTypes.outcome.withdrawn', defaultMessage: 'Withdrawn' },
  APPROVED_effect: { id: 'forumTypes.effect.approved', defaultMessage: 'The memo continues on its route.' },
  APPROVED_WITH_CONDITIONS_effect: {
    id: 'forumTypes.effect.approvedWithConditions',
    defaultMessage: 'The memo continues on its route; the conditions are recorded with the decision.',
  },
  REJECTED_effect: { id: 'forumTypes.effect.rejected', defaultMessage: 'The memo is rejected and its route ends.' },
  DEFERRED_effect: { id: 'forumTypes.effect.deferred', defaultMessage: 'The memo keeps waiting for a later meeting.' },
  RETURNED_effect: {
    id: 'forumTypes.effect.returned',
    defaultMessage: 'The memo goes back to the person who raised it.',
  },
  REFERRED_effect: {
    id: 'forumTypes.effect.referred',
    defaultMessage: 'The memo keeps waiting while the other {forum} considers it.',
  },
  NOTED_effect: { id: 'forumTypes.effect.noted', defaultMessage: 'The memo continues on its route.' },
  WITHDRAWN_effect: {
    id: 'forumTypes.effect.withdrawn',
    defaultMessage:
      'The item is taken off the agenda; the memo stays with the {forum} until the secretary returns it or places it again.',
  },
})
