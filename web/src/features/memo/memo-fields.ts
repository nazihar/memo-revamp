/** What the create page and the edit dialog edit (design E01 §5). */
export interface MemoFieldValues {
  subject: string
  typeId: string
  /** Empty: the type's default. */
  classificationId: string
}

export const SUBJECT_MAX = 300

/** Field errors of a 422 from the memo endpoints, by field name. */
export function fieldErrors(
  errors: { field: string; message: string }[] | undefined,
): Partial<Record<keyof MemoFieldValues, string>> {
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
