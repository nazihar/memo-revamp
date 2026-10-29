import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { getMeOptions, getSessionOptions, getSessionQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import type { Session } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import type { Viewer } from '@/app/navigation'

/** Session state as the server reports it (`GET /auth/session`); never 401, anonymous callers get ANONYMOUS. */
export const sessionQuery = () => ({ ...getSessionOptions(), staleTime: 30_000 })

export function useSession() {
  return useQuery(sessionQuery())
}

/** The signed-in user's account (`GET /me`), for the account menu and the step-up prompt. */
export function useMe(enabled = true) {
  return useQuery({ ...getMeOptions(), enabled, staleTime: 60_000 })
}

/** After sign-in, sign-out or a new factor: the next read asks the server again. */
export function useSessionRefresh() {
  const queryClient = useQueryClient()
  return (next?: Session) => refreshSession(queryClient, next)
}

export function refreshSession(queryClient: QueryClient, next?: Session) {
  if (next) queryClient.setQueryData(getSessionQueryKey(), next)
  return queryClient.invalidateQueries({ queryKey: getSessionQueryKey() })
}

/** Where a session in [state] belongs: the sign-in steps until it is AUTHENTICATED. */
export function pathForState(state: Session['state'], next?: string): string | undefined {
  switch (state) {
    case 'AUTHENTICATED':
      return next && next.startsWith('/') && !next.startsWith('//') ? next : '/'
    case 'MFA_ENROLMENT_REQUIRED':
      return '/login/enrol-mfa'
    case 'MFA_REQUIRED':
    case 'ANONYMOUS':
      return undefined
  }
}

/** Seconds from a 429 problem's detail is not available; the hint is generic but honest. */
export const isRateLimited = (error: unknown) => problemType(error) === PROBLEM.rateLimited

/** What the menus may show for [session] (the server enforces access regardless). */
export function viewerOf(session: Session | undefined): Viewer {
  const user = session?.state === 'AUTHENTICATED' ? session.user : undefined
  return { signedIn: !!user, tenantAdmin: user?.tenantAdmin ?? false }
}
