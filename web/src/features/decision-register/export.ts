import { client } from '@/api/generated/client.gen'
import type { DecisionSearchRequest } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'

/**
 * `POST /api/v1/decision-register/export` (E08 T3, design §1.5 "As built (T3)"). T3's endpoint is not in this branch's
 * contract yet, so the call goes through the generated client (its CSRF-aware fetch and its error handling) with the
 * body typed as T3 defines `DecisionExportRequest`. Once T3 is merged, `exportDecisionRegister` from the generated SDK
 * replaces [requestExport] and these types (the client-drift check keeps the contract the backend's).
 */
export type ExportFormat = 'XLSX' | 'PDF'

export type DecisionExportRequest = Omit<DecisionSearchRequest, 'cursor' | 'limit'> & { format: ExportFormat }

/** The rows each format holds (design §1.5). */
export const MAX_ROWS: Record<ExportFormat, number> = { XLSX: 20_000, PDF: 2_000 }

export type ExportOutcome =
  | { kind: 'file'; blob: Blob }
  | { kind: 'tooMany'; rows?: number; moreThan?: number }
  | { kind: 'rateLimited'; retryAt?: Date }
  | { kind: 'forbidden' }
  | { kind: 'outOfScope' }
  | { kind: 'failed' }

interface TooManyRows {
  rows?: unknown
  moreThan?: unknown
}

const count = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : undefined)

/** Asks for the file and says what came back: the file, or the refusal in a form the dialog can put in words. */
export async function requestExport(body: DecisionExportRequest, now: Date = new Date()): Promise<ExportOutcome> {
  let result: { data?: unknown; error?: unknown; response?: Response }
  try {
    result = await client.post({
      url: '/api/v1/decision-register/export',
      body,
      headers: { 'Content-Type': 'application/json' },
      parseAs: 'blob',
    })
  } catch {
    return { kind: 'failed' }
  }
  const status = result.response?.status
  // Not instanceof Blob: in tests the fetch's Blob is not the page's.
  if (result.response?.ok && result.data) return { kind: 'file', blob: result.data as Blob }
  if (status === 429) {
    const seconds = Number(result.response?.headers.get('Retry-After'))
    return Number.isFinite(seconds) && seconds > 0
      ? { kind: 'rateLimited', retryAt: new Date(now.getTime() + seconds * 1000) }
      : { kind: 'rateLimited' }
  }
  if (status === 403) return { kind: 'forbidden' }
  const problem = result.error
  if (status === 422 && isProblem(problem) && problem.errors?.some((e) => e.code === 'TOO_MANY_ROWS')) {
    const extra = problem as TooManyRows
    return { kind: 'tooMany', rows: count(extra.rows), moreThan: count(extra.moreThan) }
  }
  if (status === 422 && isProblem(problem) && problem.errors?.some((e) => e.code === 'FORUM_OUT_OF_SCOPE')) {
    return { kind: 'outOfScope' }
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
