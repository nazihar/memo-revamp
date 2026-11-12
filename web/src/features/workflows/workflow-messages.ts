import { defineMessages } from 'react-intl'

/** Texts of the workflow definition screens (design E03 §12); the `workflows.` ids load with the admin catalogue part. */
export const wm = defineMessages({
  title: { id: 'workflows.title', defaultMessage: 'Workflows' },
  lead: {
    id: 'workflows.lead',
    defaultMessage:
      'How memos move from step to step. A version is written, reviewed by someone else, then published; memos keep the version they started on.',
  },
  caption: { id: 'workflows.caption', defaultMessage: 'Workflow definitions of the organisation' },
  add: { id: 'workflows.add', defaultMessage: 'New workflow' },
  colName: { id: 'workflows.col.name', defaultMessage: 'Name' },
  colKey: { id: 'workflows.col.key', defaultMessage: 'Key' },
  colPublished: { id: 'workflows.col.published', defaultMessage: 'Published' },
  colOpen: { id: 'workflows.col.open', defaultMessage: 'In progress' },
  none: { id: 'workflows.none', defaultMessage: 'None' },
  builtIn: { id: 'workflows.builtIn', defaultMessage: 'Built in' },
  fragment: { id: 'workflows.fragment', defaultMessage: 'Sub-workflow' },
  notFound: { id: 'workflows.notFound', defaultMessage: 'This workflow does not exist.' },

  createTitle: { id: 'workflows.create.title', defaultMessage: 'New workflow' },
  key: { id: 'workflows.field.key', defaultMessage: 'Key' },
  keyHint: {
    id: 'workflows.field.key.hint',
    defaultMessage: 'Lower case letters, digits and dashes, starting with a letter. It never changes.',
  },
  name: { id: 'workflows.field.name', defaultMessage: 'Name' },
  description: { id: 'workflows.field.description', defaultMessage: 'Description (optional)' },
  fragmentField: { id: 'workflows.field.fragment', defaultMessage: 'Meant to be used inside other workflows' },
  create: { id: 'workflows.create.submit', defaultMessage: 'Create draft' },
  cancel: { id: 'workflows.cancel', defaultMessage: 'Cancel' },
  required: { id: 'workflows.required', defaultMessage: 'Required.' },
  keyTaken: { id: 'workflows.keyTaken', defaultMessage: 'A workflow with this key exists.' },

  versions: { id: 'workflows.versions', defaultMessage: 'Versions' },
  versionsCaption: { id: 'workflows.versions.caption', defaultMessage: 'Versions, newest first' },
  colVersion: { id: 'workflows.col.version', defaultMessage: 'Version' },
  colState: { id: 'workflows.col.state', defaultMessage: 'State' },
  colSubmitted: { id: 'workflows.col.submitted', defaultMessage: 'Submitted by' },
  colReviewed: { id: 'workflows.col.reviewed', defaultMessage: 'Reviewed by' },
  show: { id: 'workflows.show', defaultMessage: 'Show' },
  body: { id: 'workflows.body', defaultMessage: 'Definition' },
  bodyHint: {
    id: 'workflows.body.hint',
    defaultMessage: 'The definition as JSON (schema version 2). Save keeps a draft even while it has problems.',
  },
  bodyInvalidJson: { id: 'workflows.body.invalidJson', defaultMessage: 'This is not valid JSON.' },
  problems: { id: 'workflows.problems', defaultMessage: 'Problems' },
  noProblems: { id: 'workflows.problems.none', defaultMessage: 'No problems found.' },
  reviewRemark: { id: 'workflows.reviewRemark', defaultMessage: 'Reviewer’s remark' },
  newDraft: { id: 'workflows.action.newDraft', defaultMessage: 'New draft' },
  save: { id: 'workflows.action.save', defaultMessage: 'Save draft' },
  validate: { id: 'workflows.action.validate', defaultMessage: 'Check' },
  submit: { id: 'workflows.action.submit', defaultMessage: 'Submit for review' },
  approve: { id: 'workflows.action.approve', defaultMessage: 'Approve and publish' },
  sendBack: { id: 'workflows.action.sendBack', defaultMessage: 'Send back' },
  retire: { id: 'workflows.action.retire', defaultMessage: 'Retire' },
  export: { id: 'workflows.action.export', defaultMessage: 'Export' },
  import: { id: 'workflows.action.import', defaultMessage: 'Import a file' },
  remark: { id: 'workflows.remark', defaultMessage: 'Remark' },
  remarkOptional: { id: 'workflows.remark.optional', defaultMessage: 'Remark (optional)' },
  remarkRequired: { id: 'workflows.remark.required', defaultMessage: 'Say what needs to change.' },
  sendBackTitle: { id: 'workflows.sendBack.title', defaultMessage: 'Send this version back?' },
  approveTitle: { id: 'workflows.approve.title', defaultMessage: 'Publish this version?' },
  approveLead: {
    id: 'workflows.approve.lead',
    defaultMessage:
      'New memos use it from now on; the version published before is retired. You may be asked to confirm it is you.',
  },
  retireTitle: { id: 'workflows.retire.title', defaultMessage: 'Retire this version?' },
  retireLead: {
    id: 'workflows.retire.lead',
    defaultMessage: 'New memos cannot use this workflow any more. Memos already on it keep running.',
  },
  saved: { id: 'workflows.saved', defaultMessage: 'Saved.' },
  done: { id: 'workflows.done', defaultMessage: 'Done.' },
  failed: { id: 'workflows.failed', defaultMessage: 'That did not work. Try again.' },
  conflict: {
    id: 'workflows.conflict',
    defaultMessage: 'Someone changed this version meanwhile. It was reloaded.',
  },
  samePerson: {
    id: 'workflows.refused.samePerson',
    defaultMessage: 'Someone other than the person who submitted it has to review it.',
  },
  inUse: { id: 'workflows.refused.inUse', defaultMessage: 'A memo type still uses this workflow.' },
  draftExists: {
    id: 'workflows.refused.draftExists',
    defaultMessage: 'There is already a draft or a version in review.',
  },
  invalid: { id: 'workflows.refused.invalid', defaultMessage: 'Fix the problems first.' },

  migration: { id: 'workflows.migration', defaultMessage: 'Memos on older versions' },
  migrationLead: {
    id: 'workflows.migration.lead',
    defaultMessage:
      'Memos keep the version they started on. You can move those whose current step still exists to the published version.',
  },
  migrationCaption: { id: 'workflows.migration.caption', defaultMessage: 'Running memos on other versions' },
  preview: { id: 'workflows.migration.preview', defaultMessage: 'Find memos' },
  migrate: { id: 'workflows.migration.migrate', defaultMessage: 'Move selected' },
  colMemo: { id: 'workflows.col.memo', defaultMessage: 'Memo' },
  colFrom: { id: 'workflows.col.from', defaultMessage: 'On version' },
  colAt: { id: 'workflows.col.at', defaultMessage: 'At' },
  colCan: { id: 'workflows.col.can', defaultMessage: 'Can move' },
  restricted: { id: 'workflows.restricted', defaultMessage: 'Restricted memo' },
  moved: { id: 'workflows.migration.moved', defaultMessage: 'Moved' },
  yes: { id: 'workflows.yes', defaultMessage: 'Yes' },
  nothingToMove: { id: 'workflows.migration.none', defaultMessage: 'No memos run on other versions.' },
})

export const stateLabels = defineMessages({
  DRAFT: { id: 'workflows.state.draft', defaultMessage: 'Draft' },
  IN_REVIEW: { id: 'workflows.state.inReview', defaultMessage: 'In review' },
  PUBLISHED: { id: 'workflows.state.published', defaultMessage: 'Published' },
  RETIRED: { id: 'workflows.state.retired', defaultMessage: 'Retired' },
})

/** Why a memo cannot move to the published version (design E03 §4.7). */
export const migrationReasons = defineMessages({
  WRONG_STATE: { id: 'workflows.reason.wrongState', defaultMessage: 'It is not running any more' },
  STEP_MISSING: { id: 'workflows.reason.stepMissing', defaultMessage: 'Its current step is not in the new version' },
  STEP_CHANGED: { id: 'workflows.reason.stepChanged', defaultMessage: 'Its current step works differently there' },
  NOT_OLDER: { id: 'workflows.reason.notOlder', defaultMessage: 'It is on a newer version' },
  OTHER_DEFINITION: { id: 'workflows.reason.otherDefinition', defaultMessage: 'It runs another workflow' },
})

export const wmv = defineMessages<{
  version: { number: number }
  versionState: { number: number; state: string }
  selectMemo: { reference: string }
  migrated: { count: number }
}>({
  version: { id: 'workflows.version', defaultMessage: 'Version {number}' },
  versionState: { id: 'workflows.versionState', defaultMessage: 'Version {number} · {state}' },
  selectMemo: { id: 'workflows.migration.select', defaultMessage: 'Select {reference}' },
  migrated: {
    id: 'workflows.migration.migrated',
    defaultMessage: '{count, plural, one {# memo moved.} other {# memos moved.}}',
  },
})
