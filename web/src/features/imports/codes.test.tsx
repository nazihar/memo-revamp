import { render } from '@testing-library/react'
import { createIntl, IntlProvider } from 'react-intl'
import { describe, expect, it } from 'vitest'
import ar from '@/i18n/messages/ar.json'
import { ANSWER_CODES, answerText, PROBLEM_CODES, problemText, REFUSAL_CODES, refusalText } from './codes'

const en = createIntl({ locale: 'en', onError: () => undefined })
const arabic = createIntl({ locale: 'ar', messages: ar, onError: () => undefined })

/** Every code design E06 §9 names, with the codes T5 to T8 added. */
const DESIGN_CODES = [
  'SOURCE_MISSING',
  'SOURCE_KIND_MIXED',
  'NOT_UTF8',
  'DELIMITER_UNKNOWN',
  'HEADER_MISSING',
  'COLUMN_UNKNOWN',
  'COLUMN_DUPLICATE',
  'COLUMN_REQUIRED',
  'SHEET_MISSING',
  'SHEET_UNKNOWN',
  'TOO_MANY_ROWS',
  'TOO_MANY_COLUMNS',
  'VALUE_TOO_LONG_FOR_FILE',
  'MALFORMED',
  'PARSE_TIMEOUT',
  'TOO_MANY_PROBLEMS',
  'DRY_RUN_FAILED',
  'VALUE_REQUIRED',
  'VALUE_TOO_LONG',
  'VALUE_INVALID',
  'SUBJECT_TOO_LONG',
  'DATE_INVALID',
  'DATE_ORDER',
  'DATE_FUTURE',
  'MAPPING_UNCONFIRMED',
  'STATUS_UNMAPPED',
  'IN_FLIGHT_NOT_IMPORTED',
  'TYPE_UNMAPPED',
  'UNIT_UNMAPPED',
  'CLASSIFICATION_UNMAPPED',
  'CLASSIFICATION_BLANK',
  'FIELD_INVALID',
  'NUMBER_TOO_LONG',
  'NUMBER_DUPLICATE_IN_BATCH',
  'NUMBER_IN_USE',
  'NUMBER_IN_LIVE_SERIES',
  'ALREADY_IMPORTED_CHANGED',
  'MAIN_FILE_MISSING',
  'MAIN_FILE_MULTIPLE',
  'TOO_MANY_FILES',
  'TOO_MANY_APPROVALS',
  'APPROVAL_ORDER_DUPLICATE',
  'FILE_NOT_RECEIVED',
  'FILE_AMBIGUOUS',
  'FILE_REFUSED',
  'FILE_EMPTY',
  'FILE_HASH_MISMATCH',
  'FILE_CLASSIFICATION_LOWER',
  'FORMULA_NO_VALUE',
  'ORPHAN_ROW',
  'PERSON_UNKNOWN',
  'PERSON_NOT_LINKED',
  'PATH_CASE_MATCH',
  'FORMULA_CACHED',
  'CLASSIFICATION_DEFAULTED',
  'NUMBER_NOT_TEXT',
  'NUMBER_SIMILAR',
  'FILE_SHARED',
  'FILE_UNREFERENCED',
  'PATH_CASE_ONLY',
  'ALREADY_IMPORTED',
  'IMPORT_ERROR',
  'FIXITY_MISMATCH',
  'VERIFY_MEMO_MISSING',
  'VERIFY_FACTS_DIFFER',
  'VERIFY_ORIGINAL_UNBOUND',
]
/** The refusals of the batch's actions (design §10 with T7's `DRY_RUN_STALE` and T8's `REPORT_NOT_READY`). */
const DESIGN_ANSWERS = [
  'BATCH_PROBLEMS',
  'NOTHING_TO_IMPORT',
  'IMPORTER_NOT_CLEARED',
  'DRY_RUN_STALE',
  'ANOTHER_IMPORT_RUNNING',
  'ROWS_CHANGED',
  'WRONG_STATE',
  'TOO_MANY_BATCHES',
  'SOURCE_MISSING',
  'REPORT_NOT_READY',
  'SOURCE_CODE_INVALID',
  'TIME_ZONE_INVALID',
  'NUMBER_PREFIX_INVALID',
  'MAPPING_INVALID',
  'CLASSIFICATION_ORDER',
  'PATH_INVALID',
  'PATH_DUPLICATE',
  'LIMIT_EXCEEDED',
]
const PIPELINE_REASONS = [
  'TOO_LARGE',
  'MALWARE',
  'TYPE_NOT_ALLOWED',
  'TYPE_MISMATCH',
  'MALFORMED',
  'ENCRYPTED',
  'LIMIT_EXCEEDED',
  'CONTENT_REFUSED',
  'HASH_MISMATCH',
  'SCAN_UNAVAILABLE',
]

function text(node: React.ReactNode, locale: 'en' | 'ar' = 'en'): string {
  const { container } = render(
    <IntlProvider locale={locale} messages={locale === 'ar' ? (ar as Record<string, string>) : {}}>
      <p>{node}</p>
    </IntlProvider>,
  )
  return container.textContent
}

describe('messages of the import’s codes (design E06 §9)', () => {
  it('has words for every problem code, refusal and pipeline reason', () => {
    expect(PROBLEM_CODES).toEqual(expect.arrayContaining(DESIGN_CODES))
    expect(ANSWER_CODES).toEqual(expect.arrayContaining(DESIGN_ANSWERS))
    expect(REFUSAL_CODES).toEqual(expect.arrayContaining(PIPELINE_REASONS))
    for (const code of DESIGN_CODES) {
      expect(text(problemText(en, { code, column: 'raised_on', params: {} })), code).not.toMatch(/^Problem /)
    }
    expect(text(problemText(en, { code: 'SOMETHING_NEW', params: {} }))).toBe('Problem SOMETHING_NEW.')
    for (const code of DESIGN_ANSWERS) expect(answerText(en, code), code).not.toBe('That did not work. Try again.')
    for (const reason of PIPELINE_REASONS) expect(refusalText(en, reason), reason).not.toBe('Refused')
  })

  it('builds a message from the code and its params, never a subject', () => {
    expect(
      text(problemText(en, { code: 'FILE_REFUSED', params: { path: '2016/CR-0042/annex.doc', reason: 'MALWARE' } })),
    ).toBe('File 2016/CR-0042/annex.doc was refused: Contains macros or malware')
    expect(
      text(problemText(en, { code: 'MAPPING_UNCONFIRMED', params: { kind: 'STATUS', value: 'Pending with GM' } })),
    ).toBe('The status “Pending with GM” is not confirmed in the mappings.')
    expect(text(problemText(en, { code: 'NUMBER_IN_LIVE_SERIES', params: { format: 'CR/{YYYY}/{SEQ}' } }))).toContain(
      'CR/{YYYY}/{SEQ}',
    )
    expect(text(problemText(en, { code: 'TOO_MANY_ROWS', params: { limit: 50000 } }))).toBe(
      'Too many rows: at most 50,000 in this table.',
    )
    expect(
      text(problemText(en, { code: 'VERIFY_FACTS_DIFFER', params: { fields: ['fingerprint', 'originals'] } })),
    ).toBe('The imported memo differs from its row in: fingerprint, originals.')
    expect(text(problemText(en, { code: 'VERIFY_ORIGINAL_UNBOUND', params: { reason: 'VERSION' } }))).toBe(
      'A stored original is no longer the file’s current version.',
    )
  })

  it('reads in Arabic, with paths and columns isolated left to right', () => {
    const { container } = render(
      <IntlProvider locale="ar" messages={ar as Record<string, string>}>
        <p>{problemText(arabic, { code: 'FILE_NOT_RECEIVED', params: { path: '2017/CR-0108/main.pdf' } })}</p>
        <p>{problemText(arabic, { code: 'DATE_FUTURE', column: 'decided_on', params: {} })}</p>
      </IntlProvider>,
    )
    expect(container.textContent).toContain('لم يُستلم الملف')
    const isolated = [...container.querySelectorAll('bdi[dir="ltr"]')].map((b) => b.textContent)
    expect(isolated).toEqual(['2017/CR-0108/main.pdf', 'decided_on'])
    expect(answerText(arabic, 'IMPORTER_NOT_CLEARED')).toMatch(/مصرّح/)
    expect(
      text(problemText(arabic, { code: 'MAPPING_UNCONFIRMED', params: { kind: 'TYPE', value: 'X' } }), 'ar'),
    ).toContain('النوع')
  })
})
