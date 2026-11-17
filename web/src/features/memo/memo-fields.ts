import type { IntlShape } from 'react-intl'
import type { MemoDetailView } from '@/api/generated/types.gen'
import type { MetadataState, MetadataValue } from './MetadataFields'

import { mm } from './memo-messages'
/** What the create page and the edit dialog edit (design E01 §5). */
export interface MemoFieldValues {
  subject: string
  typeId: string
  /** Empty: the type's default. */
  classificationId: string
}

export const SUBJECT_MAX = 300

/** Field errors of a 422 from the memo endpoints, by field name. */
export function fieldErrors(errors: { field: string; message: string }[] | undefined): FormErrors {
  return Object.fromEntries((errors ?? []).map((e) => [e.field, e.message]))
}

const FSI = String.fromCodePoint(0x2068)
const PDI = String.fromCodePoint(0x2069)

/**
 * A memo reference (`M-2026-00042`) set apart from the text around it (Unicode first-strong isolate), so its hyphens and
 * digits keep their order inside Arabic sentences.
 */
export function isolated(reference: string | null | undefined): string {
  return FSI + (reference ?? '') + PDI
}

/**
 * How a memo's number reads in text: the number, isolated, or "Unnumbered" while it has none (memos are numbered at
 * submit or at final approval, as their type says).
 */
export function referenceOf(intl: IntlShape, reference: string | null | undefined): string {
  return reference ? isolated(reference) : intl.formatMessage(mm.unnumbered)
}

/** Errors of a form by field name; a type's fields as `metadata.<key>`. */
export type FormErrors = Record<string, string | undefined>

/** The values a memo request carries: the filled fields only. */
export function metadataBody(state: MetadataState): Record<string, MetadataValue> {
  return Object.fromEntries(
    Object.entries(state.values).filter((entry): entry is [string, MetadataValue] => entry[1] !== undefined),
  )
}

/** A form's starting state from a memo's details (the names of units and people come with them). */
export function metadataStateOf(details: MemoDetailView[]): MetadataState {
  return {
    values: Object.fromEntries(details.map((d) => [d.key, d.value as MetadataValue])),
    labels: Object.fromEntries(details.flatMap((d) => (d.display ? [[d.key, d.display]] : []))),
  }
}

export const EMPTY_METADATA: MetadataState = { values: {}, labels: {} }

/** [state] with [key] set to [value], and the name shown for a picked unit or person when one comes with it. */
export function withValue(
  state: MetadataState,
  key: string,
  value: MetadataValue | undefined,
  label?: string,
): MetadataState {
  return {
    values: { ...state.values, [key]: value },
    labels: label === undefined ? state.labels : { ...state.labels, [key]: label },
  }
}
