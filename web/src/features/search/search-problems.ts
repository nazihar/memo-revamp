import type { MessageDescriptor } from 'react-intl'
import { isProblem, PROBLEM } from '@/api/http'
import { sm } from './search-messages'

/** Fields of a search the API may name in a 422 (design E05 §3). */
export type SearchField =
  'q' | 'status' | 'type' | 'unit' | 'from' | 'to' | 'amountMin' | 'amountMax' | 'in' | 'filters'

export type FieldErrors = Partial<Record<SearchField, MessageDescriptor>>

export type SearchFailure =
  { kind: 'rateLimited' } | { kind: 'timeout' } | { kind: 'invalid'; fields: FieldErrors } | { kind: 'other' }

function fieldMessage(field: string, code: string | undefined): MessageDescriptor {
  switch (`${field}:${code ?? ''}`) {
    case 'q:REQUIRED':
      return sm.qRequired
    case 'q:TOO_LONG':
      return sm.qTooLong
    case 'q:TOO_MANY_TERMS':
      return sm.qTooManyTerms
    case 'to:BEFORE_FROM':
      return sm.toBeforeFrom
    case 'amountMax:BELOW_MIN':
      return sm.maxBelowMin
    case 'in:REQUIRED':
      return sm.inRequired
    case 'filters:TOO_MANY':
      return sm.tooManyValues
    default:
      return field === 'q' ? sm.invalid : sm.badFilter
  }
}

const FIELDS: readonly string[] = [
  'q',
  'status',
  'type',
  'unit',
  'from',
  'to',
  'amountMin',
  'amountMax',
  'in',
  'filters',
]

/** What went wrong with a search, for the page to say it in place (flow H2's limit states, errors at the field). */
export function searchFailure(error: unknown): SearchFailure {
  if (!isProblem(error)) return { kind: 'other' }
  if (error.status === 429 || error.type === PROBLEM.rateLimited) return { kind: 'rateLimited' }
  if (error.status === 503 && error.reason === 'SEARCH_TIMEOUT') return { kind: 'timeout' }
  if (error.status === 422 || (error.type === PROBLEM.validation && error.errors)) {
    const fields: FieldErrors = {}
    for (const e of error.errors ?? []) {
      const field = (FIELDS.includes(e.field) ? e.field : 'filters') as SearchField
      fields[field] ??= fieldMessage(e.field, e.code)
    }
    return { kind: 'invalid', fields }
  }
  return { kind: 'other' }
}

/** Why saving or renaming a saved search failed, shown at the name field (flow H3). */
export function saveProblem(error: unknown): MessageDescriptor {
  if (!isProblem(error)) return sm.failed
  if (error.status === 409 && error.reason === 'SAVED_SEARCH_LIMIT') return sm.limit
  if (error.status === 412) return sm.changed
  if (error.status === 429 || error.type === PROBLEM.rateLimited) return sm.rateLimited
  const first = error.errors?.[0]
  if (first?.field === 'name') return first.code === 'TOO_LONG' ? sm.nameTooLong : sm.nameRequired
  if (first?.field === 'criteria') return sm.tooLarge
  if (first) return sm.invalid
  return sm.failed
}
