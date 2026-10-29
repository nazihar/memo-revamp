import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Health, Session } from '@/api/generated/types.gen'

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

export const anonymousSession: Session = { state: 'ANONYMOUS', organisation: 'Bank of Example' }

export const signedInSession = (tenantAdmin = false): Session => ({
  state: 'AUTHENTICATED',
  organisation: 'Bank of Example',
  user: {
    id: '01900000-0000-7000-8000-0000000000aa',
    email: 'nadia@bank.example',
    displayName: 'Nadia Karim',
    tenantAdmin,
  },
})

export const sessionHandler = (body: Session = anonymousSession) =>
  http.get(`${API_ORIGIN}/api/v1/auth/session`, () => HttpResponse.json<Session>(body))

/** The CSRF endpoint sets the cookie the client echoes in X-XSRF-TOKEN (jsdom keeps it in document.cookie). */
export const csrfHandler = () =>
  http.get(`${API_ORIGIN}/api/v1/auth/csrf`, () => {
    document.cookie = 'XSRF-TOKEN=test-csrf-token; path=/'
    return new HttpResponse(null, { status: 204 })
  })

/** Default handlers: every test starts from a healthy backend and an anonymous visitor, and overrides what it needs. */
export const server = setupServer(healthHandler(), sessionHandler(), csrfHandler())
