import { defineMessages } from 'react-intl'

/** Texts of the legacy import's screens (Phase 3 E06 T9, ux-review flows L to N); the `imports` part of the catalogue. */
export const im = defineMessages({
  title: { id: 'imports.title', defaultMessage: 'Import legacy memos' },
  lead: {
    id: 'imports.lead',
    defaultMessage:
      'Bring memos from an earlier system: their originals and a spreadsheet of their facts. Nothing is imported until you have checked a dry run.',
  },
  newBatch: { id: 'imports.newBatch', defaultMessage: 'New batch' },
  template: { id: 'imports.template', defaultMessage: 'Download template' },
  caption: { id: 'imports.caption', defaultMessage: 'Import batches, newest first' },
  colName: { id: 'imports.col.name', defaultMessage: 'Name' },
  colSource: { id: 'imports.col.source', defaultMessage: 'Source' },
  colState: { id: 'imports.col.state', defaultMessage: 'State' },
  colUpdated: { id: 'imports.col.updated', defaultMessage: 'Updated' },
  empty: { id: 'imports.empty', defaultMessage: 'No import batches yet.' },
  loadMore: { id: 'imports.loadMore', defaultMessage: 'Load more' },
  // New batch and settings (L2)
  newTitle: { id: 'imports.new.title', defaultMessage: 'New import batch' },
  settingsTitle: { id: 'imports.settings.title', defaultMessage: 'Batch settings' },
  editSettings: { id: 'imports.settings.edit', defaultMessage: 'Settings' },
  name: { id: 'imports.field.name', defaultMessage: 'Name' },
  sourceCode: { id: 'imports.field.sourceCode', defaultMessage: 'Source code' },
  sourceCodeHint: {
    id: 'imports.field.sourceCode.hint',
    defaultMessage: 'Capital letters, digits and "-". The same source code keeps re-runs from duplicating memos.',
  },
  timeZone: { id: 'imports.field.timeZone', defaultMessage: 'Time zone of dates' },
  defaultClassification: {
    id: 'imports.field.defaultClassification',
    defaultMessage: 'Default classification for blank cells',
  },
  noDefault: { id: 'imports.field.noDefault', defaultMessage: 'None: blank is an error' },
  numberPrefix: { id: 'imports.field.numberPrefix', defaultMessage: 'Number prefix' },
  numberPrefixHint: {
    id: 'imports.field.numberPrefix.hint',
    defaultMessage: 'Optional. Added before every legacy number, for example "LEG-", if they clash with ours.',
  },
  optional: { id: 'imports.optional', defaultMessage: '(optional)' },
  create: { id: 'imports.create', defaultMessage: 'Create' },
  save: { id: 'imports.save', defaultMessage: 'Save' },
  cancel: { id: 'imports.cancel', defaultMessage: 'Cancel' },
  required: { id: 'imports.required', defaultMessage: 'Required.' },
  failed: { id: 'imports.failed', defaultMessage: 'That did not work. Try again.' },
  conflict: {
    id: 'imports.conflict',
    defaultMessage: 'Someone else changed this batch. It has been reloaded with their changes.',
  },
  saved: { id: 'imports.saved', defaultMessage: 'Saved.' },
  rerun: { id: 'imports.rerun', defaultMessage: 'Run the dry run again before importing.' },
  notFound: { id: 'imports.notFound', defaultMessage: 'This batch does not exist, or it is not yours to see.' },
  // Batch page and stepper (L3)
  steps: { id: 'imports.steps', defaultMessage: 'Steps of the import' },
  stepMetadata: { id: 'imports.step.metadata', defaultMessage: 'Metadata' },
  stepOriginals: { id: 'imports.step.originals', defaultMessage: 'Originals' },
  stepMappings: { id: 'imports.step.mappings', defaultMessage: 'Mappings' },
  stepDryRun: { id: 'imports.step.dryRun', defaultMessage: 'Dry run' },
  stepImport: { id: 'imports.step.import', defaultMessage: 'Import' },
  stepReport: { id: 'imports.step.report', defaultMessage: 'Report' },
  stale: {
    id: 'imports.stale',
    defaultMessage: 'Something changed since the last dry run. Run the dry run again before importing.',
  },
  readOnly: { id: 'imports.readOnly', defaultMessage: 'This batch can no longer change.' },
  abandon: { id: 'imports.abandon', defaultMessage: 'Abandon batch' },
  abandonTitle: { id: 'imports.abandon.title', defaultMessage: 'Abandon this batch?' },
  abandonText: {
    id: 'imports.abandon.text',
    defaultMessage: 'Nothing has been imported. Staged copies of the files are deleted; the batch stays listed.',
  },
  reason: { id: 'imports.reason', defaultMessage: 'Reason' },
  abandoned: { id: 'imports.abandoned', defaultMessage: 'The batch was abandoned.' },
  // Metadata (L3)
  metadataLead: {
    id: 'imports.metadata.lead',
    defaultMessage: 'Use our template, or your own file with the same columns.',
  },
  sourceKind: { id: 'imports.metadata.kind', defaultMessage: 'Metadata as' },
  kindWorkbook: {
    id: 'imports.metadata.workbook',
    defaultMessage: 'One workbook (.xlsx) with sheets Memos, Files, Approvals',
  },
  kindCsv: { id: 'imports.metadata.csv', defaultMessage: 'CSV files, UTF-8, one per table' },
  tableWorkbook: { id: 'imports.table.workbook', defaultMessage: 'Workbook' },
  tableMemos: { id: 'imports.table.memos', defaultMessage: 'Memos (CSV)' },
  tableFiles: { id: 'imports.table.files', defaultMessage: 'Files (CSV)' },
  tableApprovals: { id: 'imports.table.approvals', defaultMessage: 'Approvals (CSV, optional)' },
  sourceHint: { id: 'imports.metadata.hint', defaultMessage: 'Up to 20 MB.' },
  csvTip: {
    id: 'imports.metadata.tip',
    defaultMessage:
      'Tip: never open a legacy CSV export in Excel before uploading it. Excel removes leading zeros from numbers and changes dates.',
  },
  sourcesNone: { id: 'imports.metadata.none', defaultMessage: 'No metadata uploaded yet.' },
  // Originals (L4)
  originalsLead: {
    id: 'imports.originals.lead',
    defaultMessage:
      'Unpack the archive on your computer, then choose the folder. Each file is sent and checked on its own.',
  },
  chooseFolder: { id: 'imports.originals.choose', defaultMessage: 'Choose folder' },
  phone: { id: 'imports.originals.phone', defaultMessage: 'Use a computer to send a folder.' },
  pause: { id: 'imports.originals.pause', defaultMessage: 'Pause' },
  resume: { id: 'imports.originals.resume', defaultMessage: 'Resume' },
  paused: { id: 'imports.originals.paused', defaultMessage: 'Paused. Nothing is lost: resume when you are ready.' },
  pausing: { id: 'imports.originals.pausing', defaultMessage: 'Pausing after the files being sent…' },
  sendingDone: { id: 'imports.originals.done', defaultMessage: 'Every file of the folder was sent.' },
  view: { id: 'imports.view', defaultMessage: 'View' },
  hide: { id: 'imports.hide', defaultMessage: 'Hide' },
  viewAll: { id: 'imports.viewAll', defaultMessage: 'View all' },
  colPath: { id: 'imports.col.path', defaultMessage: 'Path' },
  colReason: { id: 'imports.col.reason', defaultMessage: 'Reason' },
  refusedCaption: { id: 'imports.originals.refusedCaption', defaultMessage: 'Refused originals' },
  failedSend: {
    id: 'imports.originals.failedSend',
    defaultMessage: 'Some files could not be sent. Choose the same folder again to send them.',
  },
  // Mappings (M1)
  mappingsLead: {
    id: 'imports.mappings.lead',
    defaultMessage:
      'Say what each legacy value means here. Every value must be confirmed; the dry run proposes what it can.',
  },
  mappingsNone: { id: 'imports.mappings.none', defaultMessage: 'Run the dry run once to list the legacy values.' },
  kindSTATUS: { id: 'imports.kind.status', defaultMessage: 'Statuses' },
  kindTYPE: { id: 'imports.kind.type', defaultMessage: 'Types' },
  kindUNIT: { id: 'imports.kind.unit', defaultMessage: 'Units' },
  kindCLASSIFICATION: { id: 'imports.kind.classification', defaultMessage: 'Classification' },
  kindPERSON: { id: 'imports.kind.person', defaultMessage: 'People' },
  confirmAll: { id: 'imports.mappings.confirmAll', defaultMessage: 'Confirm all proposals' },
  colLegacy: { id: 'imports.col.legacy', defaultMessage: 'Legacy value' },
  colMemos: { id: 'imports.col.memos', defaultMessage: 'Memos' },
  colOurs: { id: 'imports.col.ours', defaultMessage: 'Ours' },
  colConfirmed: { id: 'imports.col.confirmed', defaultMessage: 'Confirmed' },
  colOrder: { id: 'imports.col.order', defaultMessage: 'Order' },
  notMapped: { id: 'imports.mappings.notMapped', defaultMessage: 'Choose…' },
  notLinked: { id: 'imports.mappings.notLinked', defaultMessage: 'Not linked: keep the name as text' },
  proposed: { id: 'imports.mappings.proposed', defaultMessage: 'Proposed' },
  orderLead: {
    id: 'imports.mappings.orderLead',
    defaultMessage:
      'Order the legacy markings from least to most strict. A stricter marking can never map to a lower level than a less strict one.',
  },
  peopleLead: {
    id: 'imports.mappings.peopleLead',
    defaultMessage:
      'An email equal to a user’s is proposed; a legacy id is linked through the people picker. People given only by name are kept as text (no identifier) and cannot be linked.',
  },
  linkPerson: { id: 'imports.mappings.linkPerson', defaultMessage: 'Link to' },
  saveMappings: { id: 'imports.mappings.save', defaultMessage: 'Save mappings' },
  statusNOT_IMPORTED: { id: 'imports.status.notImported', defaultMessage: 'Not imported (in flight)' },
  // Dry run (M2)
  runDryRun: { id: 'imports.dryRun.run', defaultMessage: 'Run dry run' },
  dryRunning: { id: 'imports.dryRun.running', defaultMessage: 'The dry run is running. Nothing is imported.' },
  dryRunNever: { id: 'imports.dryRun.never', defaultMessage: 'No dry run yet.' },
  problems: { id: 'imports.problems', defaultMessage: 'Problems' },
  problemsCaption: { id: 'imports.problems.caption', defaultMessage: 'Problems found, by level and code' },
  rows: { id: 'imports.rows', defaultMessage: 'Rows' },
  rowsCaption: { id: 'imports.rows.caption', defaultMessage: 'Memos rows and their results' },
  level: { id: 'imports.filter.level', defaultMessage: 'Level' },
  code: { id: 'imports.filter.code', defaultMessage: 'Code' },
  sheet: { id: 'imports.filter.sheet', defaultMessage: 'Sheet' },
  row: { id: 'imports.filter.row', defaultMessage: 'Row' },
  result: { id: 'imports.filter.result', defaultMessage: 'Result' },
  any: { id: 'imports.filter.any', defaultMessage: 'Any' },
  colSheet: { id: 'imports.col.sheet', defaultMessage: 'Sheet' },
  colRow: { id: 'imports.col.row', defaultMessage: 'Row' },
  colColumn: { id: 'imports.col.column', defaultMessage: 'Column' },
  colLevel: { id: 'imports.col.level', defaultMessage: 'Level' },
  colProblem: { id: 'imports.col.problem', defaultMessage: 'Problem' },
  colLegacyNumber: { id: 'imports.col.legacyNumber', defaultMessage: 'Legacy number' },
  colResult: { id: 'imports.col.result', defaultMessage: 'Result' },
  colSubject: { id: 'imports.col.subject', defaultMessage: 'Subject' },
  colCodes: { id: 'imports.col.codes', defaultMessage: 'Problems' },
  restricted: { id: 'imports.restricted', defaultMessage: 'Restricted' },
  noProblems: { id: 'imports.problems.none', defaultMessage: 'No problem matches these filters.' },
  noRows: { id: 'imports.rows.none', defaultMessage: 'No row matches this filter.' },
  // Import (N1, N2)
  start: { id: 'imports.start', defaultMessage: 'Import…' },
  startConfirm: { id: 'imports.start.confirm', defaultMessage: 'Confirm and verify identity' },
  startFinal: {
    id: 'imports.start.final',
    defaultMessage:
      'Imported memos are final. They get no approvals, tasks, notifications or final record. Their legacy approvals show as history, not verified by this system.',
  },
  startUndo: {
    id: 'imports.start.undo',
    defaultMessage: 'This cannot be undone. Corrections are added later, never by deleting.',
  },
  notStartable: {
    id: 'imports.start.notYet',
    defaultMessage: 'Run a dry run that finds no batch problem and at least one ready memo first.',
  },
  stop: { id: 'imports.stop', defaultMessage: 'Stop' },
  resumeImport: { id: 'imports.resumeImport', defaultMessage: 'Resume' },
  finish: { id: 'imports.finish', defaultMessage: 'Finish without the rest' },
  finishTitle: { id: 'imports.finish.title', defaultMessage: 'Finish without the rest?' },
  finishText: {
    id: 'imports.finish.text',
    defaultMessage:
      'The rows not imported yet are left out ("not started"); they can go into a later batch. The imported memos are checked and the report is made.',
  },
  stopping: { id: 'imports.stopping', defaultMessage: 'Stopping after the current chunk of rows…' },
  stopped: {
    id: 'imports.stopped',
    defaultMessage: 'Stopped. Resume the import (you verify your identity again), or finish without the rest.',
  },
  verifying: {
    id: 'imports.verifying',
    defaultMessage:
      'Every imported memo and original is being checked. The report is made when previews are done; this can take some hours.',
  },
  notImported: { id: 'imports.notImportedYet', defaultMessage: 'Nothing has been imported yet.' },
  // Report (N3)
  reportTitle: { id: 'imports.report.title', defaultMessage: 'Reconciliation report' },
  balanced: { id: 'imports.report.balanced', defaultMessage: 'Balanced' },
  unbalanced: { id: 'imports.report.unbalanced', defaultMessage: 'Unbalanced' },
  complete: { id: 'imports.report.complete', defaultMessage: 'Complete' },
  incomplete: { id: 'imports.report.incomplete', defaultMessage: 'Incomplete' },
  valid: { id: 'imports.report.valid', defaultMessage: 'Valid' },
  invalid: { id: 'imports.report.invalid', defaultMessage: 'Not valid' },
  reportPending: {
    id: 'imports.report.pending',
    defaultMessage: 'The report is made when the previews are done.',
  },
  partSUMMARY: { id: 'imports.part.summary', defaultMessage: 'Summary (PDF)' },
  partDETAIL: { id: 'imports.part.detail', defaultMessage: 'Details (XLSX)' },
  partMANIFEST: { id: 'imports.part.manifest', defaultMessage: 'Manifest (SHA-256)' },
  partSOURCE_WORKBOOK: { id: 'imports.part.sourceWorkbook', defaultMessage: 'Workbook used' },
  partSOURCE_MEMOS: { id: 'imports.part.sourceMemos', defaultMessage: 'Memos CSV used' },
  partSOURCE_FILES: { id: 'imports.part.sourceFiles', defaultMessage: 'Files CSV used' },
  partSOURCE_APPROVALS: { id: 'imports.part.sourceApprovals', defaultMessage: 'Approvals CSV used' },
  closeBatch: { id: 'imports.closeBatch', defaultMessage: 'Close batch' },
  closeTitle: { id: 'imports.close.title', defaultMessage: 'Close this batch?' },
  closeText: {
    id: 'imports.close.text',
    defaultMessage: 'Staged copies of the originals are deleted. The report and imported memos stay.',
  },
  closed: { id: 'imports.closed', defaultMessage: 'The batch was closed.' },
})

/** Texts with values. */
export const imv = defineMessages<{
  header: { name: string; source: string }
  replaceTable: { table: string }
  stateLabel: { state: string }
  stepDone: { step: string }
  source: { table: string; name: string; size: string; date: string }
  folder: { name: string; files: number; size: string }
  skipped: { count: number }
  tooLarge: { count: number }
  invalid: { count: number }
  registering: { done: number; total: number }
  sending: { done: number; total: number; sent: string; size: string }
  timeLeft: { minutes: number; parallel: number }
  alreadyHere: { count: number }
  alreadySkipped: { count: number }
  received: { received: number; expected: number; refused: number; size: string }
  refused: { count: number }
  stopped: { reason: string }
  registerFailed: { reason: string }
  orderUp: { value: string }
  orderDown: { value: string }
  orderBreak: { stricter: string; lessStrict: string }
  target: { value: string }
  confirm: { value: string }
  personEmail: { value: string }
  personId: { value: string }
  linked: { name: string }
  lastRun: { date: string }
  totals: { ready: number; rejected: number; warnings: number; already: number }
  originals: { received: number; unused: number; refused: number }
  levelCount: { level: string; count: number }
  startTitle: { count: number }
  startRows: { ready: number; received: number; rejected: number }
  startWarnings: { warnings: number; notLinked: number }
  progressLine: { done: number; rows: number }
  progressEnd: { done: number; rows: number; minutes: number }
  progressCounts: { imported: number; skipped: number; rejected: number; failed: number; notStarted: number }
  throttled: { time: string }
  failing: { lines: string }
  reportRows: { read: number; imported: number; rejected: number; skipped: number; failed: number; notStarted: number }
  reportOriginals: {
    registered: number
    imported: number
    heldBack: number
    unreferenced: number
    refused: number
    neverUploaded: number
  }
  reportRenditions: {
    kind: string
    done: number
    notPossible: number
    notNeeded: number
    failed: number
    pending: number
  }
  reportFixity: { verified: number; mismatches: number }
  reportFiles: { listed: number; matched: number; notReceived: number; ambiguous: number }
  storedNotBound: { count: number }
  classified: { level: string }
  download: { part: string }
  closedReason: { date: string; reason: string }
}>({
  header: { id: 'imports.batch.header', defaultMessage: '{name} · {source}' },
  replaceTable: { id: 'imports.metadata.replace', defaultMessage: 'Replace: {table}' },
  stateLabel: { id: 'imports.batch.state', defaultMessage: 'State: {state}' },
  stepDone: { id: 'imports.step.doneLabel', defaultMessage: '{step} (done)' },
  source: {
    id: 'imports.metadata.source',
    defaultMessage: '{table}: {name} · {size} · uploaded {date}',
  },
  folder: {
    id: 'imports.originals.folder',
    defaultMessage: 'Folder: {name} · {files, plural, one {# file} other {# files}} · {size}',
  },
  skipped: {
    id: 'imports.originals.skipped',
    defaultMessage: 'Skipped: {count, plural, one {# system file} other {# system files}} (Thumbs.db, .DS_Store)',
  },
  tooLarge: { id: 'imports.originals.tooLarge', defaultMessage: 'Too large (over 100 MB): {count, number}' },
  invalid: { id: 'imports.originals.invalid', defaultMessage: 'Paths that cannot be used: {count, number}' },
  registering: {
    id: 'imports.originals.registering',
    defaultMessage: 'Listing the folder: {done, number} of {total, number} paths',
  },
  sending: {
    id: 'imports.originals.sending',
    defaultMessage: '{done, number} of {total, number} files · {sent} of {size}',
  },
  timeLeft: {
    id: 'imports.originals.timeLeft',
    defaultMessage: 'About {minutes, plural, one {# minute} other {# minutes}} left · {parallel, number} at a time',
  },
  alreadyHere: {
    id: 'imports.originals.alreadyHere',
    defaultMessage:
      '{count, plural, one {# file is} other {# files are}} already here. Choose the same folder to send the rest.',
  },
  alreadySkipped: {
    id: 'imports.originals.alreadySkipped',
    defaultMessage: '{count, plural, one {# file was} other {# files were}} already here and not sent again.',
  },
  received: {
    id: 'imports.originals.received',
    defaultMessage:
      'Received {received, number} · waiting {expected, number} · refused {refused, number} · {size} received',
  },
  refused: { id: 'imports.originals.refused', defaultMessage: 'Refused ({count, number})' },
  stopped: { id: 'imports.originals.stoppedReason', defaultMessage: 'Sending stopped: {reason}' },
  registerFailed: {
    id: 'imports.originals.registerFailed',
    defaultMessage: 'The folder could not be listed: {reason}',
  },
  orderUp: { id: 'imports.mappings.up', defaultMessage: 'Move “{value}” up (less strict)' },
  orderDown: { id: 'imports.mappings.down', defaultMessage: 'Move “{value}” down (stricter)' },
  orderBreak: {
    id: 'imports.mappings.orderBreak',
    defaultMessage: '“{stricter}” is stricter than “{lessStrict}” but maps to a lower level.',
  },
  target: { id: 'imports.mappings.target', defaultMessage: 'Ours for “{value}”' },
  confirm: { id: 'imports.mappings.confirm', defaultMessage: 'Confirm “{value}”' },
  personEmail: { id: 'imports.mappings.email', defaultMessage: 'Email {value}' },
  personId: { id: 'imports.mappings.legacyId', defaultMessage: 'Legacy id {value}' },
  linked: { id: 'imports.mappings.linked', defaultMessage: 'Linked: {name}' },
  lastRun: { id: 'imports.dryRun.last', defaultMessage: 'Last run {date} · nothing was imported' },
  totals: {
    id: 'imports.dryRun.totals',
    defaultMessage:
      '{ready, number} memos ready · {rejected, number} rejected · {warnings, number} warnings · {already, number} already imported',
  },
  originals: {
    id: 'imports.dryRun.originals',
    defaultMessage:
      '{received, number} originals received · {unused, number} not used by any row · {refused, number} refused',
  },
  levelCount: { id: 'imports.filter.levelCount', defaultMessage: '{level} ({count, number})' },
  startTitle: {
    id: 'imports.start.title',
    defaultMessage: 'Import {count, plural, one {# memo} other {# memos}}?',
  },
  startRows: {
    id: 'imports.start.rows',
    defaultMessage:
      '{ready, plural, one {# memo} other {# memos}} will be imported with their originals ({received, number} originals received). {rejected, plural, =0 {No row is rejected.} one {# rejected row will not.} other {# rejected rows will not.}}',
  },
  startWarnings: {
    id: 'imports.start.warnings',
    defaultMessage:
      'The dry run listed {warnings, plural, one {# warning} other {# warnings}} (for example {notLinked, number} people kept as text).',
  },
  progressLine: {
    id: 'imports.progress.line',
    defaultMessage: 'Importing · {done, number} of {rows, number} memos',
  },
  progressEnd: {
    id: 'imports.progress.end',
    defaultMessage:
      'Importing · {done, number} of {rows, number} memos · about {minutes, plural, one {# minute} other {# minutes}} left',
  },
  progressCounts: {
    id: 'imports.progress.counts',
    defaultMessage:
      'Imported {imported, number} · Skipped {skipped, number} · Rejected {rejected, number} · Failed {failed, number} · Not started {notStarted, number}',
  },
  throttled: {
    id: 'imports.progress.throttled',
    defaultMessage: 'Waiting for previews to catch up (since {time}). The import resumes by itself.',
  },
  failing: { id: 'imports.report.failing', defaultMessage: 'Lines that do not balance: {lines}' },
  reportRows: {
    id: 'imports.report.rows',
    defaultMessage:
      'Rows read {read, number} = imported {imported, number} + rejected {rejected, number} + skipped {skipped, number} + failed {failed, number} + not started {notStarted, number}',
  },
  reportOriginals: {
    id: 'imports.report.originals',
    defaultMessage:
      'Originals registered {registered, number} = imported {imported, number} + held back {heldBack, number} + not used {unreferenced, number} + refused {refused, number} + never sent {neverUploaded, number}',
  },
  reportRenditions: {
    id: 'imports.report.renditions',
    defaultMessage:
      'Previews ({kind}): {done, number} done · {notPossible, number} not possible · {notNeeded, number} not needed · {failed, number} failed · {pending, number} pending',
  },
  reportFixity: {
    id: 'imports.report.fixity',
    defaultMessage: 'Fixity: {verified, number} originals re-checked · {mismatches, number} mismatches',
  },
  reportFiles: {
    id: 'imports.report.files',
    defaultMessage:
      'Files rows {listed, number} = matched {matched, number} + not received {notReceived, number} + ambiguous {ambiguous, number}',
  },
  storedNotBound: {
    id: 'imports.report.storedNotBound',
    defaultMessage: 'Stored for rows that then failed: {count, number}',
  },
  classified: {
    id: 'imports.report.classified',
    defaultMessage: 'Classified {level}: only people cleared to it can open these files.',
  },
  download: { id: 'imports.report.download', defaultMessage: 'Download {part}' },
  closedReason: { id: 'imports.closedReason', defaultMessage: 'Closed {date}: {reason}' },
})
