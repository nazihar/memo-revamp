/**
 * RFC 9457 Problem Details as the API returns them, with the field-error extension used by forms:
 * `errors: [{ field, message }]` (E01 design §3; the backend adds it with its first forms in E03).
 */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  correlationId?: string
  errors?: { field: string; message: string }[]
}

export function isProblemDetails(value: unknown): value is ProblemDetails {
  return typeof value === 'object' && value !== null && ('title' in value || 'errors' in value)
}

/**
 * Applies server field errors to a form: [setError] receives each field's message; focus moves to the first field with
 * an error (its control carries the field name as `name`). Returns the errors that matched no field, for a form-level
 * message.
 */
export function applyFieldErrors(
  problem: ProblemDetails,
  fields: readonly string[],
  setError: (field: string, message: string) => void,
  container: HTMLElement | null,
): string[] {
  const unmatched: string[] = []
  let first: string | undefined
  for (const e of problem.errors ?? []) {
    if (fields.includes(e.field)) {
      setError(e.field, e.message)
      first ??= e.field
    } else {
      unmatched.push(e.message)
    }
  }
  if (first && container) {
    const control = container.querySelector<HTMLElement>(`[name="${CSS.escape(first)}"]`)
    control?.focus()
  }
  return unmatched
}
