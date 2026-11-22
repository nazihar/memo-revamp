import { exportDecisionRegister } from '@/api/generated/sdk.gen'
import type { DecisionExportRequest } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'

/** `POST /api/v1/decision-register/export` (E08 T3, design §1.5 "As built (T3)"): the register as XLSX or PDF. */
export type ExportFormat = DecisionExportRequest['format']

/** The rows each format holds (design §1.5). */
export const MAX_ROWS: Record<ExportFormat, number> = { XLSX: 20_000, PDF: 2_000 }

export type ExportOutcome =
  | { kind: 'file'; blob: Blob }
  | { kind: 'tooMany'; rows?: number; moreThan?: number; maximum?: number }
  | { kind: 'rateLimited'; retryAt?: Date }
  | { kind: 'forbidden' }
  | { kind: 'outOfScope' }
  | { kind: 'failed' }

/** T3's `TOO_MANY_ROWS`: the exact count when the search knew it, else a lower bound, and the format's maximum. */
interface TooManyRows {
  rows?: unknown
  moreThan?: unknown
  maximum?: unknown
}

const count = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)

/** Asks for the file and says what came back: the file, or the refusal in a form the dialog can put in words. */
export async function requestExport(body: DecisionExportRequest, now: Date = new Date()): Promise<ExportOutcome> {
  let result: Awaited<ReturnType<typeof exportDecisionRegister>>
  try {
    result = await exportDecisionRegister({ body, parseAs: 'blob' })
  } catch {
    return { kind: 'failed' }
  }
  const { response } = result
  if (!response) return { kind: 'failed' }
  // Not instanceof Blob: in tests the fetch's Blob is not the page's.
  if (response.ok && result.data) return { kind: 'file', blob: result.data }
  if (response.status === 429) {
    const seconds = Number(response.headers.get('Retry-After'))
    return Number.isFinite(seconds) && seconds > 0
      ? { kind: 'rateLimited', retryAt: new Date(now.getTime() + seconds * 1000) }
      : { kind: 'rateLimited' }
  }
  if (response.status === 403) return { kind: 'forbidden' }
  const problem: unknown = result.error
  if (response.status === 422 && isProblem(problem)) {
    if (problem.errors?.some((e) => e.code === 'TOO_MANY_ROWS')) {
      const extra = problem as TooManyRows
      return {
        kind: 'tooMany',
        rows: count(extra.rows),
        moreThan: count(extra.moreThan),
        maximum: count(extra.maximum),
      }
    }
    if (problem.errors?.some((e) => e.code === 'FORUM_OUT_OF_SCOPE')) return { kind: 'outOfScope' }
  }
  return { kind: 'failed' }
}

/** Saves [blob] as a file in the browser's downloads. */
export function saveFile(blob: Blob, name: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = name
  link.click()
  URL.revokeObjectURL(url)
}
