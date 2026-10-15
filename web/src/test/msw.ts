import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Health } from '@/api/generated/types.gen'

// jsdom has no origin for relative URLs; the generated client uses baseUrl "/" which resolves against this.
export const API_ORIGIN = 'http://localhost:3000'

export const healthyResponse: Health = {
  status: 'UP',
  checkedAt: '2026-10-14T10:00:00Z',
  components: [
    { name: 'database', status: 'UP' },
    { name: 'objectStorage', status: 'UP' },
    { name: 'documentServer', status: 'DISABLED' },
    { name: 'malwareScanner', status: 'DOWN' },
    { name: 'mail', status: 'UP' },
  ],
}

/** Handlers are typed from the generated API types, so mocks cannot drift from the contract (TESTING_STRATEGY §4). */
export const healthHandler = (body: Health = healthyResponse, status = 200) =>
  http.get(`${API_ORIGIN}/api/v1/health`, () => HttpResponse.json<Health>(body, { status }))

/** Default handlers: every test starts from a healthy backend and overrides what it needs. */
export const server = setupServer(healthHandler())
