import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Health } from '@/api/generated/types.gen'

// jsdom has no origin for relative URLs; the generated client uses baseUrl "/" which resolves against this.
export const API_ORIGIN = 'http://localhost:3000'

/** Handlers are typed from the generated API types, so mocks cannot drift from the contract (TESTING_STRATEGY §4). */
export const healthHandler = (body: Health, status = 200) =>
  http.get(`${API_ORIGIN}/api/v1/health`, () => HttpResponse.json<Health>(body, { status }))

export const server = setupServer()
