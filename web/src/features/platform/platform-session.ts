import { useQuery, type QueryClient } from '@tanstack/react-query'
import { getPlatformSessionOptions, getPlatformSessionQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import type { PlatformSession } from '@/api/generated/types.gen'

/** The operator's session on the console's host (ADR-0031); never 401, anonymous callers get ANONYMOUS. */
export const platformSessionQuery = () => ({ ...getPlatformSessionOptions(), staleTime: 30_000, retry: false })

export function usePlatformSession() {
  return useQuery(platformSessionQuery())
}

export function refreshPlatformSession(queryClient: QueryClient, next?: PlatformSession) {
  if (next) queryClient.setQueryData(getPlatformSessionQueryKey(), next)
  return queryClient.invalidateQueries({ queryKey: getPlatformSessionQueryKey() })
}
