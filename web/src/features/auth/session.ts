import { useQuery, useQueryClient, type QueryClient, type QueryFunction } from '@tanstack/react-query'
import {
  getMeOptions,
  getMyAccessQueryKey,
  getSessionOptions,
  getSessionQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import type { MyAccess, MyForumResponse, Session } from '@/api/generated/types.gen'
import { problemType, PROBLEM } from '@/api/http'
import type { Viewer } from '@/app/navigation'
import { rememberOrgToday } from '@/lib/org-today'

/**
 * Session state as the server reports it (`GET /auth/session`); never 401, anonymous callers get ANONYMOUS. Each answer
 * also gives the web's date defaults the organisation's date today (`orgToday`).
 */
export const sessionQuery = () => {
  const options = getSessionOptions()
  const read = options.queryFn as QueryFunction<Session, ReturnType<typeof getSessionQueryKey>>
  const queryFn: typeof read = async (context) => {
    const session = await read(context)
    rememberOrgToday(session.today)
    return session
  }
  return { ...options, queryFn, staleTime: 30_000 }
}

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
  if (next) {
    queryClient.setQueryData(getSessionQueryKey(), next)
    rememberOrgToday(next.today)
  }
  // Permissions belong to the session: another sign-in must not see the previous user's.
  queryClient.removeQueries({ queryKey: getMyAccessQueryKey() })
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

/** The seats that give committee analytics (design E08 §2.3): officers and members in office, not invitees or observers. */
const ANALYTICS_SEATS = new Set<MyForumResponse['role']>(['SECRETARY', 'CHAIR', 'VICE_CHAIR', 'MEMBER'])

/** Whether [forums] (`GET /me/forums`) hold a seat in office that gives committee analytics. */
export function holdsCommitteeSeat(forums: readonly MyForumResponse[] | undefined): boolean {
  return (forums ?? []).some((f) => f.state === 'IN_OFFICE' && ANALYTICS_SEATS.has(f.role))
}

/** What the menus may show for [session], [access] and the user's committees (the server enforces access regardless). */
export function viewerOf(session: Session | undefined, access?: MyAccess, forums?: readonly MyForumResponse[]): Viewer {
  const signedIn = session?.state === 'AUTHENTICATED'
  return {
    signedIn,
    permissions: signedIn ? (access?.permissions.map((p) => p.key) ?? []) : [],
    committeeSeat: signedIn && holdsCommitteeSeat(forums),
  }
}
