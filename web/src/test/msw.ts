import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'
import type { Health, MyAccess, Session, Branding, PlatformSession, Term, Terms } from '@/api/generated/types.gen'

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

/** `GET /me/access`: what the signed-in user may do; by default only the implicit user role (directory.read). */
export const accessHandler = (permissions: string[] = []) =>
  http.get(`${API_ORIGIN}/api/v1/me/access`, () =>
    HttpResponse.json<MyAccess>({
      permissions: ['directory.read', ...permissions].map((key) => ({ key, tenantWide: true, scopeUnitIds: [] })),
      clearance: { rank: 0, name: 'Public' },
    }),
  )

/** Default handlers: every test starts from a healthy backend and an anonymous visitor, and overrides what it needs. */
/** The bell polls the unread count on every signed-in page (E06). */
export const unreadHandler = (count = 0) =>
  http.get(`${API_ORIGIN}/api/v1/me/notifications/unread-count`, () => HttpResponse.json({ count }))

/** Branding (E07): the default look unless a test sets colours or a logo. */
export const brandingHandler = (body: Partial<Branding> = {}) =>
  http.get(`${API_ORIGIN}/api/v1/branding`, () =>
    HttpResponse.json<Branding>({ organisation: 'Bank of Example', version: 0, ...body }),
  )

/** The organisation's terms (ADR-0032): none by default, so the standard words. */
export const termsHandler = (terms: Term[] = []) =>
  http.get(`${API_ORIGIN}/api/v1/terms`, ({ request }) =>
    HttpResponse.json<Terms>({ locale: new URL(request.url).searchParams.get('locale') ?? 'en', terms, version: 0 }),
  )

/** The operator console's session (ADR-0031); anonymous unless a test signs in. */
export const platformSessionHandler = (body: PlatformSession = { state: 'ANONYMOUS' }) =>
  http.get(`${API_ORIGIN}/api/v1/platform/auth/session`, () => HttpResponse.json<PlatformSession>(body))

export const server = setupServer(
  healthHandler(),
  sessionHandler(),
  csrfHandler(),
  accessHandler(),
  unreadHandler(),
  brandingHandler(),
  termsHandler(),
  platformSessionHandler(),
)
