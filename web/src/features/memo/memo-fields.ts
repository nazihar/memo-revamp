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
