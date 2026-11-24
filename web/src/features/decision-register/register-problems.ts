import type { MessageDescriptor } from 'react-intl'
import { isProblem, PROBLEM } from '@/api/http'
import { drm } from './messages'

/** Where a refusal is said: at the words, at the dates, or above the filters (design §3 "As built (T2)"). */
export interface SearchRefusal {
  kind: 'noScope' | 'rateLimited' | 'timeout' | 'invalid' | 'cursor' | 'other'
  text?: MessageDescriptor
  dates?: MessageDescriptor
  filters?: MessageDescriptor
}

function fieldMessage(field: string, code: string | undefined): MessageDescriptor {
  switch (`${field}:${code ?? ''}`) {
    case 'text:TEXT_TOO_LONG':
      return drm.textTooLong
    case 'text:TOO_MANY_TERMS':
      return drm.tooManyTerms
    case 'forumIds:FORUM_OUT_OF_SCOPE':
      return drm.outOfScope
    case 'to:RANGE':
    case 'from:RANGE':
      return drm.range
    case 'sort:SORT_NEEDS_ONE_FORUM':
      return drm.sortNeedsOne
    default:
      return code === 'TOO_MANY' ? drm.tooManyValues : drm.invalid
  }
}

/** What a failed register search means for the reader, in words, at the place it concerns. */
export function searchRefusal(error: unknown): SearchRefusal {
  if (!isProblem(error)) return { kind: 'other' }
  if (error.status === 403) return { kind: 'noScope' }
  if (error.status === 429 || error.type === PROBLEM.rateLimited) return { kind: 'rateLimited' }
  if (error.status === 503) return { kind: 'timeout' }
  if (error.status === 422 || error.status === 400) {
    const errors = error.errors ?? []
    if (errors.some((e) => e.field === 'cursor')) return { kind: 'cursor' }
    const refusal: SearchRefusal = { kind: 'invalid' }
    for (const e of errors) {
      const message = fieldMessage(e.field, e.code)
      if (e.field === 'text') refusal.text ??= message
      else if (e.field === 'from' || e.field === 'to') refusal.dates ??= message
      else refusal.filters ??= message
    }
    if (!refusal.text && !refusal.dates && !refusal.filters) refusal.filters = drm.invalid
    return refusal
  }
  return { kind: 'other' }
}
