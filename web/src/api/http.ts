import { client } from './generated/client.gen'

/**
 * HTTP setup of the generated client (E03 design §11):
 * - CSRF: every mutating request carries `X-XSRF-TOKEN` with the `XSRF-TOKEN` cookie value; when the cookie is missing
 *   (first visit, or after sign-in or sign-out, which rotate it) it is fetched from `/api/v1/auth/csrf` first.
 * - Errors are RFC 9457 problems; SDK calls pass `throwOnError: true` and the problem body is the thrown value.
 */
export const CSRF_COOKIE = 'XSRF-TOKEN'
export const CSRF_HEADER = 'X-XSRF-TOKEN'

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

export const PROBLEM = {
  invalidCredentials: 'urn:memo:problem:invalid-credentials',
  mfaRequired: 'urn:memo:problem:mfa-required',
  stepUpRequired: 'urn:memo:problem:step-up-required',
  rateLimited: 'urn:memo:problem:rate-limited',
  validation: 'urn:memo:problem:validation',
  conflict: 'urn:memo:problem:conflict',
} as const

export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
  errors?: { field: string; code?: string; message: string }[]
  stepUpWindowSeconds?: number
  reason?: string
}

export function isProblem(value: unknown): value is Problem {
  return typeof value === 'object' && value !== null && ('type' in value || 'status' in value || 'title' in value)
}

export function problemType(value: unknown): string | undefined {
  return isProblem(value) ? value.type : undefined
}

export function readCookie(name: string): string | undefined {
  return document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1)
}

let pending: Promise<void> | undefined

/** The CSRF token, fetching the cookie once when it is missing (concurrent callers share one request). */
export async function csrfToken(): Promise<string> {
  const existing = readCookie(CSRF_COOKIE)
  if (existing) return decodeURIComponent(existing)
  pending ??= fetch('/api/v1/auth/csrf', { credentials: 'same-origin' })
    .then(() => undefined)
    .finally(() => {
      pending = undefined
    })
  await pending
  return decodeURIComponent(readCookie(CSRF_COOKIE) ?? '')
}

let configured = false

/** Installs the interceptors once (main.tsx and tests). */
export function configureHttp(): void {
  if (configured) return
  configured = true
  client.setConfig({ credentials: 'same-origin' })
  client.interceptors.request.use(async (request) => {
    if (MUTATING.has(request.method.toUpperCase())) request.headers.set(CSRF_HEADER, await csrfToken())
    return request
  })
}
