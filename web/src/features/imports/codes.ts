import { createElement, type ReactNode } from 'react'
import { defineMessages, type IntlShape, type MessageDescriptor } from 'react-intl'
import type { ImportProblemView } from '@/api/generated/types.gen'

/**
 * Messages of the legacy import's codes (design E06 §9, with the codes added as built in T5 to T8): every problem the
 * dry run, the import and the verification record, built from the code and its params (which never hold a subject),
 * and every refusal the API answers. Paths, legacy numbers, columns and formats read left to right inside Arabic text.
 */
const problems = defineMessages({
  // BATCH
  SOURCE_MISSING: {
    id: 'imports.code.sourceMissing',
    defaultMessage: 'The metadata is missing: upload a workbook, or the Memos and Files CSV files.',
  },
  SOURCE_KIND_MIXED: {
    id: 'imports.code.sourceKindMixed',
    defaultMessage: 'A file is of the wrong kind for its table (a workbook as a CSV file, or the reverse).',
  },
  NOT_UTF8: { id: 'imports.code.notUtf8', defaultMessage: 'The CSV file is not UTF-8. Save it again as “CSV UTF-8”.' },
  DELIMITER_UNKNOWN: {
    id: 'imports.code.delimiterUnknown',
    defaultMessage: 'The CSV file’s columns could not be told apart: separate them with commas or semicolons.',
  },
  HEADER_MISSING: { id: 'imports.code.headerMissing', defaultMessage: 'Column {position} has values but no header.' },
  COLUMN_UNKNOWN: { id: 'imports.code.columnUnknown', defaultMessage: 'The column {column} is not one we know.' },
  COLUMN_DUPLICATE: { id: 'imports.code.columnDuplicate', defaultMessage: 'The column {column} appears twice.' },
  COLUMN_REQUIRED: { id: 'imports.code.columnRequired', defaultMessage: 'The required column {column} is missing.' },
  SHEET_MISSING: {
    id: 'imports.code.sheetMissing',
    defaultMessage: 'The workbook needs the sheets Memos and Files (Approvals is optional).',
  },
  SHEET_UNKNOWN: { id: 'imports.code.sheetUnknown', defaultMessage: 'The sheet “{sheet}” is not one we know.' },
  TOO_MANY_ROWS: { id: 'imports.code.tooManyRows', defaultMessage: 'Too many rows: at most {limit} in this table.' },
  TOO_MANY_COLUMNS: { id: 'imports.code.tooManyColumns', defaultMessage: 'Too many columns: at most {limit}.' },
  VALUE_TOO_LONG_FOR_FILE: {
    id: 'imports.code.valueTooLongForFile',
    defaultMessage: 'A value is longer than {limit} characters.',
  },
  MALFORMED: {
    id: 'imports.code.malformed',
    defaultMessage: 'The file could not be read. Check that it is not damaged.',
  },
  PARSE_TIMEOUT: {
    id: 'imports.code.parseTimeout',
    defaultMessage: 'Reading the file took longer than {seconds} seconds. Split it, or simplify it.',
  },
  TOO_MANY_PROBLEMS: {
    id: 'imports.code.tooManyProblems',
    defaultMessage: 'More than {limit} problems: fix the first ones and run the dry run again.',
  },
  DRY_RUN_FAILED: {
    id: 'imports.code.dryRunFailed',
    defaultMessage: 'The dry run failed. Try again; if it fails again, ask your support.',
  },
  // ERROR
  VALUE_REQUIRED: { id: 'imports.code.valueRequired', defaultMessage: 'A value is required in {column}.' },
  VALUE_TOO_LONG: { id: 'imports.code.valueTooLong', defaultMessage: 'The value in {column} is too long.' },
  VALUE_INVALID: { id: 'imports.code.valueInvalid', defaultMessage: 'The value in {column} is not valid.' },
  SUBJECT_TOO_LONG: { id: 'imports.code.subjectTooLong', defaultMessage: 'The subject is longer than 300 characters.' },
  DATE_INVALID: {
    id: 'imports.code.dateInvalid',
    defaultMessage: 'The date in {column} is not a date (yyyy-mm-dd), or is before 1900.',
  },
  DATE_ORDER: {
    id: 'imports.code.dateOrder',
    defaultMessage: 'The dates are out of order: raised on, then decided on, then closed on.',
  },
  DATE_FUTURE: { id: 'imports.code.dateFuture', defaultMessage: 'The date in {column} is in the future.' },
  MAPPING_UNCONFIRMED: {
    id: 'imports.code.mappingUnconfirmed',
    defaultMessage:
      'The {kind, select, STATUS {status} TYPE {type} UNIT {unit} CLASSIFICATION {classification} PERSON {person} other {value}} “{value}” is not confirmed in the mappings.',
  },
  STATUS_UNMAPPED: { id: 'imports.code.statusUnmapped', defaultMessage: 'Status “{value}” is not mapped.' },
  IN_FLIGHT_NOT_IMPORTED: {
    id: 'imports.code.inFlight',
    defaultMessage: 'This memo’s status is in flight: finish it in the old system.',
  },
  TYPE_UNMAPPED: { id: 'imports.code.typeUnmapped', defaultMessage: 'Type “{value}” is not mapped.' },
  UNIT_UNMAPPED: { id: 'imports.code.unitUnmapped', defaultMessage: 'Unit “{value}” is not mapped.' },
  CLASSIFICATION_UNMAPPED: {
    id: 'imports.code.classificationUnmapped',
    defaultMessage: 'Classification “{value}” is not mapped.',
  },
  CLASSIFICATION_BLANK: {
    id: 'imports.code.classificationBlank',
    defaultMessage: 'The classification is blank and the batch has no default level.',
  },
  FIELD_INVALID: {
    id: 'imports.code.fieldInvalid',
    defaultMessage: 'The value in {column} does not fit the memo type’s field.',
  },
  NUMBER_TOO_LONG: { id: 'imports.code.numberTooLong', defaultMessage: 'The legacy number is too long.' },
  NUMBER_DUPLICATE_IN_BATCH: {
    id: 'imports.code.numberDuplicate',
    defaultMessage: 'Another row of this batch has the same legacy number.',
  },
  NUMBER_IN_USE: { id: 'imports.code.numberInUse', defaultMessage: 'A memo with this number already exists.' },
  NUMBER_IN_LIVE_SERIES: {
    id: 'imports.code.numberInLiveSeries',
    defaultMessage:
      'This number could be made by the series {format}; set its starting number past it, or use a prefix.',
  },
  ALREADY_IMPORTED_CHANGED: {
    id: 'imports.code.alreadyImportedChanged',
    defaultMessage: 'This memo was imported before with other values. Correct the imported memo instead.',
  },
  MAIN_FILE_MISSING: { id: 'imports.code.mainFileMissing', defaultMessage: 'The memo has no MAIN file.' },
  MAIN_FILE_MULTIPLE: { id: 'imports.code.mainFileMultiple', defaultMessage: 'The memo has more than one MAIN file.' },
  TOO_MANY_FILES: { id: 'imports.code.tooManyFiles', defaultMessage: 'The memo has more than 51 files.' },
  TOO_MANY_APPROVALS: { id: 'imports.code.tooManyApprovals', defaultMessage: 'The memo has more than 50 approvals.' },
  APPROVAL_ORDER_DUPLICATE: {
    id: 'imports.code.approvalOrderDuplicate',
    defaultMessage: 'Two approvals of this memo have the same order.',
  },
  FILE_NOT_RECEIVED: { id: 'imports.code.fileNotReceived', defaultMessage: 'File {path} was not received.' },
  FILE_AMBIGUOUS: {
    id: 'imports.code.fileAmbiguous',
    defaultMessage: 'File {path} matches several originals that differ only in case.',
  },
  FILE_REFUSED: { id: 'imports.code.fileRefused', defaultMessage: 'File {path} was refused: {reason}' },
  FILE_EMPTY: { id: 'imports.code.fileEmpty', defaultMessage: 'File {path} is empty.' },
  FILE_HASH_MISMATCH: {
    id: 'imports.code.fileHashMismatch',
    defaultMessage: 'File {path} is not the one the sheet’s SHA-256 describes.',
  },
  FILE_CLASSIFICATION_LOWER: {
    id: 'imports.code.fileClassificationLower',
    defaultMessage: 'A file’s classification must be stricter than its memo’s, or blank.',
  },
  FORMULA_NO_VALUE: {
    id: 'imports.code.formulaNoValue',
    defaultMessage: 'The formula in {column} has no saved value. Open and save the workbook in Excel.',
  },
  ORPHAN_ROW: {
    id: 'imports.code.orphanRow',
    defaultMessage: 'No Memos row has the legacy number {legacyNumber}.',
  },
  PERSON_UNKNOWN: { id: 'imports.code.personUnknown', defaultMessage: 'A linked person is not a user here.' },
  // WARNING
  PERSON_NOT_LINKED: {
    id: 'imports.code.personNotLinked',
    defaultMessage: 'The person in {column} is kept as text, not linked to a user.',
  },
  PATH_CASE_MATCH: {
    id: 'imports.code.pathCaseMatch',
    defaultMessage: 'File {path} was matched to {matched}, which differs only in case.',
  },
  FORMULA_CACHED: {
    id: 'imports.code.formulaCached',
    defaultMessage: 'The value in {column} comes from a formula; its saved value is used.',
  },
  CLASSIFICATION_DEFAULTED: {
    id: 'imports.code.classificationDefaulted',
    defaultMessage: 'The classification was blank; the batch’s default level is used.',
  },
  NUMBER_NOT_TEXT: {
    id: 'imports.code.numberNotText',
    defaultMessage: 'The value in {column} is held as a number: leading zeros may have been lost.',
  },
  NUMBER_SIMILAR: {
    id: 'imports.code.numberSimilar',
    defaultMessage: 'Another legacy number differs from this one only in case or separators.',
  },
  FILE_SHARED: { id: 'imports.code.fileShared', defaultMessage: 'Another memo has a file with the same content.' },
  // FILE
  FILE_UNREFERENCED: { id: 'imports.code.fileUnreferenced', defaultMessage: 'File {path} is not used by any row.' },
  PATH_CASE_ONLY: {
    id: 'imports.code.pathCaseOnly',
    defaultMessage: 'File {path} differs from another only in case.',
  },
  // INFO
  ALREADY_IMPORTED: {
    id: 'imports.code.alreadyImported',
    defaultMessage: 'This memo was already imported unchanged; it is skipped.',
  },
  // IMPORT and VERIFY
  IMPORT_ERROR: {
    id: 'imports.code.importError',
    defaultMessage: 'The memo could not be imported after three attempts.',
  },
  FIXITY_MISMATCH: {
    id: 'imports.code.fixityMismatch',
    defaultMessage: 'A stored original no longer matches the SHA-256 taken when it was received.',
  },
  VERIFY_MEMO_MISSING: { id: 'imports.code.verifyMemoMissing', defaultMessage: 'The imported memo was not found.' },
  VERIFY_FACTS_DIFFER: {
    id: 'imports.code.verifyFactsDiffer',
    defaultMessage: 'The imported memo differs from its row in: {fields}.',
  },
  VERIFY_ORIGINAL_UNBOUND: {
    id: 'imports.code.verifyOriginalUnbound',
    defaultMessage:
      '{reason, select, VERSION {A stored original is no longer the file’s current version.} other {A stored original is not bound to its memo.}}',
  },
  unknown: { id: 'imports.code.unknown', defaultMessage: 'Problem {code}.' },
})

/** Why an upload was refused (the files pipeline's reason codes). */
const refusals = defineMessages({
  TOO_LARGE: { id: 'imports.refusal.tooLarge', defaultMessage: 'Too large' },
  MALWARE: { id: 'imports.refusal.malware', defaultMessage: 'Contains macros or malware' },
  TYPE_NOT_ALLOWED: { id: 'imports.refusal.typeNotAllowed', defaultMessage: 'This type is not allowed' },
  TYPE_MISMATCH: { id: 'imports.refusal.typeMismatch', defaultMessage: 'The content does not match its extension' },
  MALFORMED: { id: 'imports.refusal.malformed', defaultMessage: 'Damaged or unreadable' },
  ENCRYPTED: { id: 'imports.refusal.encrypted', defaultMessage: 'Protected by a password' },
  LIMIT_EXCEEDED: { id: 'imports.refusal.limitExceeded', defaultMessage: 'Over a limit (pages, size or depth)' },
  CONTENT_REFUSED: { id: 'imports.refusal.contentRefused', defaultMessage: 'Its content is not allowed' },
  HASH_MISMATCH: { id: 'imports.refusal.hashMismatch', defaultMessage: 'Its content changed' },
  SCAN_UNAVAILABLE: { id: 'imports.refusal.scanUnavailable', defaultMessage: 'The checks are unavailable; try later' },
  other: { id: 'imports.refusal.other', defaultMessage: 'Refused' },
})

/** Refusals of the batch's actions and fields (409 `reason`, 422 field codes). */
const answers = defineMessages({
  BATCH_PROBLEMS: {
    id: 'imports.answer.batchProblems',
    defaultMessage: 'The batch has problems that stop the import. Fix them and run the dry run again.',
  },
  NOTHING_TO_IMPORT: { id: 'imports.answer.nothingToImport', defaultMessage: 'No memo is ready to import.' },
  IMPORTER_NOT_CLEARED: {
    id: 'imports.answer.importerNotCleared',
    defaultMessage:
      'You are not cleared to the highest classification this batch writes. Someone cleared to it must start the import.',
  },
  DRY_RUN_STALE: {
    id: 'imports.answer.dryRunStale',
    defaultMessage: 'Something changed since the dry run. Run the dry run again.',
  },
  ANOTHER_IMPORT_RUNNING: {
    id: 'imports.answer.anotherRunning',
    defaultMessage: 'Another batch is importing. Only one import runs at a time; try again when it has finished.',
  },
  ROWS_CHANGED: {
    id: 'imports.answer.rowsChanged',
    defaultMessage: 'The number of memos ready changed. Review the batch again.',
  },
  WRONG_STATE: {
    id: 'imports.answer.wrongState',
    defaultMessage: 'The batch has moved on in the meantime. It has been reloaded.',
  },
  TOO_MANY_BATCHES: {
    id: 'imports.answer.tooManyBatches',
    defaultMessage: 'Five batches are open already. Close or abandon one first.',
  },
  SOURCE_MISSING: {
    id: 'imports.answer.sourceMissing',
    defaultMessage: 'Upload the metadata first: a workbook, or the Memos and Files CSV files.',
  },
  REPORT_NOT_READY: { id: 'imports.answer.reportNotReady', defaultMessage: 'The report is not ready yet.' },
  SOURCE_CODE_INVALID: {
    id: 'imports.answer.sourceCodeInvalid',
    defaultMessage: 'Use 1 to 20 capital letters, digits or "-", starting with a letter or digit.',
  },
  TIME_ZONE_INVALID: { id: 'imports.answer.timeZoneInvalid', defaultMessage: 'Choose a time zone from the list.' },
  NUMBER_PREFIX_INVALID: {
    id: 'imports.answer.numberPrefixInvalid',
    defaultMessage: 'Use up to 20 letters, digits or / . _ -, starting with a letter or digit.',
  },
  MAPPING_INVALID: {
    id: 'imports.answer.mappingInvalid',
    defaultMessage: 'A mapping is not valid. Reload and try again.',
  },
  CLASSIFICATION_ORDER: {
    id: 'imports.answer.classificationOrder',
    defaultMessage: 'A stricter marking maps to a lower level than a less strict one.',
  },
  REQUIRED: { id: 'imports.answer.required', defaultMessage: 'Required.' },
  TOO_LONG: { id: 'imports.answer.tooLong', defaultMessage: 'Too long.' },
  PATH_INVALID: { id: 'imports.answer.pathInvalid', defaultMessage: 'A path in the folder cannot be used.' },
  PATH_DUPLICATE: { id: 'imports.answer.pathDuplicate', defaultMessage: 'Two files of the folder have the same path.' },
  SIZE_INVALID: { id: 'imports.answer.sizeInvalid', defaultMessage: 'A file’s size could not be read.' },
  LIMIT_EXCEEDED: {
    id: 'imports.answer.limitExceeded',
    defaultMessage: 'The batch would hold more than 200,000 originals or 200 GB.',
  },
  CLEARED: { id: 'imports.answer.cleared', defaultMessage: 'Cancelled.' },
  FORBIDDEN: { id: 'imports.answer.forbidden', defaultMessage: 'You may no longer send files to this batch.' },
  other: { id: 'imports.answer.other', defaultMessage: 'That did not work. Try again.' },
})

export const PROBLEM_CODES = Object.keys(problems).filter((code) => code !== 'unknown')
export const ANSWER_CODES = Object.keys(answers).filter((code) => code !== 'other')
export const REFUSAL_CODES = Object.keys(refusals).filter((code) => code !== 'other')

/** A param as text: params hold strings, numbers and lists of strings (design E06 §9). */
function text(value: unknown): string {
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return Array.isArray(value) ? value.map(text).join(', ') : ''
}
const ltr = (value: unknown): ReactNode => createElement('bdi', { dir: 'ltr' }, text(value))
const isolated = (value: unknown): ReactNode => createElement('bdi', null, text(value))

function descriptor(table: Record<string, MessageDescriptor>, code: string, fallback: MessageDescriptor) {
  return table[code] ?? fallback
}

/** Why the pipeline refused a file, in words. */
export function refusalText(intl: IntlShape, reason: string | null | undefined): string {
  return intl.formatMessage(descriptor(refusals, reason ?? '', refusals.other))
}

/** A refusal of an action or a field, in words. */
export function answerText(intl: IntlShape, code: string | null | undefined): string {
  return intl.formatMessage(descriptor(answers, code ?? '', answers.other))
}

/** A problem's message from its code, column and params. */
export function problemText(
  intl: IntlShape,
  problem: Pick<ImportProblemView, 'code' | 'column' | 'params'>,
): ReactNode {
  const p = problem.params
  const values: Record<string, ReactNode> = {
    code: ltr(problem.code),
    column: ltr(problem.column ?? ''),
    path: ltr(p.path),
    matched: ltr(p.matched),
    value: isolated(p.value),
    kind: text(p.kind),
    sheet: isolated(p.sheet),
    format: ltr(p.format),
    legacyNumber: ltr(p.legacyNumber),
    limit: intl.formatNumber(Number(p.limit ?? 0)),
    position: intl.formatNumber(Number(p.position ?? 0)),
    seconds: intl.formatNumber(Number(p.seconds ?? 0)),
    reason: problem.code === 'VERIFY_ORIGINAL_UNBOUND' ? text(p.reason) : refusalText(intl, text(p.reason)),
    fields: ltr(p.fields),
  }
  return intl.formatMessage(descriptor(problems, problem.code, problems.unknown), values)
}
