import type { IntlShape } from 'react-intl'
import { isProblem, PROBLEM } from '@/api/http'
import type { TermValues } from '@/features/terms/terms'
import { am } from './messages'

/** Refusals of the action endpoints that have their own sentence (design §1.4, §3, as built in T4–T6). */
const REASONS = {
  RECUSED: am.refusedRECUSED,
  WRONG_STATE: am.refusedWRONG_STATE,
  PENDING_REQUEST: am.refusedPENDING_REQUEST,
  TOO_LATE: am.refusedTOO_LATE,
  NOT_HOLDER: am.refusedNOT_HOLDER,
  NOT_VERIFIER: am.refusedNOT_VERIFIER,
  CLAIMED: am.refusedCLAIMED,
  NOT_INVOLVED: am.refusedNOT_INVOLVED,
  ALREADY_LINKED: am.refusedALREADY_LINKED,
  ASK_FOR_IT: am.refusedASK_FOR_IT,
  OWNER_CHANGES: am.refusedOWNER_CHANGES,
} as const

/** Whether the refusal means the action moved on, so the page should reload it. */
export function staleRefusal(problem: unknown): boolean {
  if (!isProblem(problem)) return false
  return problem.status === 412 || problem.reason === 'WRONG_STATE' || problem.type === PROBLEM.conflict
}

/** A field problem in words, or undefined when it has none of its own (the caller then shows the server's sentence). */
export function fieldText(
  intl: IntlShape,
  terms: TermValues,
  field: string,
  code: string | undefined,
): string | undefined {
  const at = field.replace(/\/\d+$/, '')
  const byField: Record<string, string | undefined> = {
    'text:LENGTH': intl.formatMessage(am.fieldTextLength),
    'note:LENGTH': intl.formatMessage(am.fieldNoteLength),
    'remark:LENGTH': intl.formatMessage(am.fieldNoteLength),
    'remark:REQUIRED': intl.formatMessage(am.fieldRemarkRequired),
    'reason:LENGTH': intl.formatMessage(am.fieldReasonLength),
    'confidence:CONFIDENCE': intl.formatMessage(am.fieldConfidence),
    'fileIds:EVIDENCE_REQUIRED': intl.formatMessage(am.fieldEvidenceRequired, terms),
    'fileIds:TOO_MANY': intl.formatMessage(am.fieldFilesTooMany),
    'fileIds:unknown': intl.formatMessage(am.fieldFileUnknown),
    'newDate:DUE_IN_PAST': intl.formatMessage(am.fieldDueInPast),
    'newDate:SAME_DATE': intl.formatMessage(am.fieldSameDate),
    'memos:TOO_MANY': intl.formatMessage(am.fieldMemosTooMany),
    'classificationId:LOWER_THAN_ACTION': intl.formatMessage(am.fieldLowerLevel),
    'classificationId:below-type-default': intl.formatMessage(am.fieldBelowTypeDefault),
    'memoId:NOT_READABLE': intl.formatMessage(am.fieldNotReadable),
    'memoId:DISCARDED': intl.formatMessage(am.fieldDiscarded),
    'typeId:unknown': intl.formatMessage(am.fieldTypeUnknown),
    'typeId:NOT_ALLOWED_TO_RAISE': intl.formatMessage(am.fieldTypeNotAllowed),
  }
  const known = byField[`${at}:${code ?? ''}`]
  if (known) return known
  if (code === 'REQUIRED') return intl.formatMessage(am.fieldRequired)
  return undefined
}

/** Field problems in words, by field (`fileIds/2` counts as `fileIds`); the first problem of a field is the one shown. */
export function fieldProblems(intl: IntlShape, terms: TermValues, problem: unknown): Record<string, string> {
  if (!isProblem(problem) || !problem.errors?.length) return {}
  const found: Record<string, string> = {}
  for (const e of problem.errors) {
    const at = e.field.replace(/\/\d+$/, '')
    found[at] ??= fieldText(intl, terms, e.field, e.code) ?? e.message
  }
  return found
}

/** One sentence for a refused request: a known reason, a lost race (412), no right, too many tries, or a failure. */
export function actionRefusal(intl: IntlShape, terms: TermValues, problem: unknown): string {
  if (!isProblem(problem)) return intl.formatMessage(am.refusedFailed)
  const reason = problem.reason as keyof typeof REASONS | undefined
  if (reason && reason in REASONS) return intl.formatMessage(REASONS[reason], terms)
  if (problem.status === 412 || problem.type === PROBLEM.conflict) return intl.formatMessage(am.refusedConflict)
  if (problem.status === 404) return intl.formatMessage(am.refusedNotFound)
  if (problem.status === 403) return intl.formatMessage(am.refusedForbidden)
  if (problem.status === 429 || problem.type === PROBLEM.rateLimited) return intl.formatMessage(am.refusedRateLimited)
  if (problem.errors?.length) {
    return problem.errors.map((e) => fieldText(intl, terms, e.field, e.code) ?? e.message).join(' ')
  }
  return intl.formatMessage(am.refusedFailed)
}
