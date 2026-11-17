import { defineMessages } from 'react-intl'

/** Texts of an imported memo's page (Phase 3 E06 T9, ux-review O1); the `legacy` part of the catalogue (`/memos`). */
export const lm = defineMessages({
  imported: { id: 'legacy.imported', defaultMessage: 'Imported' },
  notVerified: {
    id: 'legacy.notVerified',
    defaultMessage: 'Created and decided in an earlier system; not verified by this system.',
  },
  details: { id: 'legacy.details', defaultMessage: 'Legacy details' },
  type: { id: 'legacy.type', defaultMessage: 'Type' },
  classification: { id: 'legacy.classification', defaultMessage: 'Classification' },
  unit: { id: 'legacy.unit', defaultMessage: 'Unit' },
  raisedOn: { id: 'legacy.raisedOn', defaultMessage: 'Raised' },
  decidedOn: { id: 'legacy.decidedOn', defaultMessage: 'Decided' },
  closedOn: { id: 'legacy.closedOn', defaultMessage: 'Closed' },
  status: { id: 'legacy.status', defaultMessage: 'Status' },
  legacyNumber: { id: 'legacy.legacyNumber', defaultMessage: 'Legacy number' },
  initiator: { id: 'legacy.initiator', defaultMessage: 'Initiator' },
  approvals: {
    id: 'legacy.approvals',
    defaultMessage: 'Legacy approval history (imported, not verified by this system)',
  },
  approvalsNone: { id: 'legacy.approvals.none', defaultMessage: 'No approvals were given in the earlier system.' },
  colOrder: { id: 'legacy.col.order', defaultMessage: '#' },
  colName: { id: 'legacy.col.name', defaultMessage: 'Name' },
  colPosition: { id: 'legacy.col.position', defaultMessage: 'Position' },
  colDecision: { id: 'legacy.col.decision', defaultMessage: 'Decision' },
  colDate: { id: 'legacy.col.date', defaultMessage: 'Date' },
  colRemark: { id: 'legacy.col.remark', defaultMessage: 'Remark' },
  originals: { id: 'legacy.originals', defaultMessage: 'Original documents' },
  main: { id: 'legacy.main', defaultMessage: 'Main' },
  annex: { id: 'legacy.annex', defaultMessage: 'Annex' },
  restricted: { id: 'legacy.restricted', defaultMessage: 'Restricted original' },
  noPreview: { id: 'legacy.noPreview', defaultMessage: 'Download only' },
  finalRecord: { id: 'legacy.finalRecord', defaultMessage: 'Final record' },
  noFinalRecord: {
    id: 'legacy.noFinalRecord',
    defaultMessage: 'No final record: for an imported memo the original documents are the record.',
  },
  correct: { id: 'legacy.correct', defaultMessage: 'Correct a legacy detail' },
  reclassify: { id: 'legacy.reclassify', defaultMessage: 'Raise classification' },
  field: { id: 'legacy.field', defaultMessage: 'Detail' },
  value: { id: 'legacy.value', defaultMessage: 'Corrected value' },
  valueHint: {
    id: 'legacy.value.hint',
    defaultMessage: 'Leave empty to clear a decision date the status does not need.',
  },
  reason: { id: 'legacy.reason', defaultMessage: 'Reason' },
  reasonHint: { id: 'legacy.reason.hint', defaultMessage: 'Shown with the correction to everyone who reads the memo.' },
  level: { id: 'legacy.level', defaultMessage: 'New classification' },
  levelHint: {
    id: 'legacy.level.hint',
    defaultMessage: 'Only a stricter level. Readers not cleared to it lose access to the memo.',
  },
  noHigher: { id: 'legacy.noHigher', defaultMessage: 'There is no stricter level you are cleared to.' },
  correctLead: {
    id: 'legacy.correct.lead',
    defaultMessage: 'The legacy record stays as given; the correction is added with your reason and name.',
  },
  confirm: { id: 'legacy.confirm', defaultMessage: 'Confirm and verify identity' },
  cancel: { id: 'legacy.cancel', defaultMessage: 'Cancel' },
  corrected: { id: 'legacy.corrected', defaultMessage: 'The correction was added.' },
  reclassified: { id: 'legacy.reclassified', defaultMessage: 'The classification was raised.' },
  fieldSUBJECT: { id: 'legacy.field.subject', defaultMessage: 'Subject' },
  fieldRAISED_ON: { id: 'legacy.field.raisedOn', defaultMessage: 'Raised on' },
  fieldDECIDED_ON: { id: 'legacy.field.decidedOn', defaultMessage: 'Decided on' },
  fieldCLOSED_ON: { id: 'legacy.field.closedOn', defaultMessage: 'Closed on' },
  fieldINITIATOR_NAME: { id: 'legacy.field.initiatorName', defaultMessage: 'Initiator' },
  fieldLEGACY_UNIT: { id: 'legacy.field.legacyUnit', defaultMessage: 'Legacy unit' },
  fieldLEGACY_TYPE: { id: 'legacy.field.legacyType', defaultMessage: 'Legacy type' },
  fieldCLASSIFICATION: { id: 'legacy.field.classification', defaultMessage: 'Classification' },
  errDATE_INVALID: { id: 'legacy.error.dateInvalid', defaultMessage: 'Enter a date (yyyy-mm-dd) after 1900.' },
  errDATE_FUTURE: { id: 'legacy.error.dateFuture', defaultMessage: 'The date cannot be in the future.' },
  errDATE_ORDER: {
    id: 'legacy.error.dateOrder',
    defaultMessage: 'The dates must stay in order: raised on, then decided on, then closed on.',
  },
  errVALUE_REQUIRED: { id: 'legacy.error.valueRequired', defaultMessage: 'This detail cannot be empty.' },
  errUNCHANGED: { id: 'legacy.error.unchanged', defaultMessage: 'That is already the value.' },
  errREQUIRED: { id: 'legacy.error.required', defaultMessage: 'Required.' },
  errTOO_LONG: { id: 'legacy.error.tooLong', defaultMessage: 'Too long.' },
  errUSE_RECLASSIFY: { id: 'legacy.error.useReclassify', defaultMessage: 'Use Raise classification.' },
  errCLASSIFICATION_NOT_HIGHER: {
    id: 'legacy.error.notHigher',
    defaultMessage: 'Choose a stricter level than the memo’s.',
  },
  errAboveClearance: { id: 'legacy.error.aboveClearance', defaultMessage: 'You are not cleared to that level.' },
  errNOT_IMPORTED: { id: 'legacy.error.notImported', defaultMessage: 'This memo was not imported.' },
  errOther: { id: 'legacy.error.other', defaultMessage: 'That did not work. Try again.' },
})

/** Texts with values. */
export const lmv = defineMessages<{
  importedFrom: { source: string; date: string }
  importedFromBatch: { source: string; date: string; batch: string }
  asGiven: { value: string; given: string }
  linkedPerson: { name: string; user: string }
  unlinkedPerson: { name: string }
  corrections: { count: number }
  correction: { date: string; field: string; before: string; after: string; reason: string; by: string }
  preview: { title: string }
  download: { title: string }
  originalLine: { title: string; size: string }
}>({
  importedFrom: { id: 'legacy.importedFrom', defaultMessage: 'Imported from {source} on {date}.' },
  importedFromBatch: {
    id: 'legacy.importedFromBatch',
    defaultMessage: 'Imported from {source} on {date} (“{batch}”).',
  },
  asGiven: { id: 'legacy.asGiven', defaultMessage: '{value} (legacy: “{given}”)' },
  linkedPerson: { id: 'legacy.linkedPerson', defaultMessage: '{name} (linked: {user})' },
  unlinkedPerson: { id: 'legacy.unlinkedPerson', defaultMessage: '{name} (as given; not linked to a user)' },
  corrections: { id: 'legacy.corrections', defaultMessage: 'Corrections ({count, number})' },
  correction: {
    id: 'legacy.correction',
    defaultMessage: '{date} · {field}: {before} → {after} · “{reason}” · {by}',
  },
  preview: { id: 'legacy.preview', defaultMessage: 'Preview {title}' },
  download: { id: 'legacy.download', defaultMessage: 'Download {title}' },
  originalLine: { id: 'legacy.originalLine', defaultMessage: '{title} · {size}' },
})
