import type { QueryClient } from '@tanstack/react-query'
import { createRootRouteWithContext, Outlet, useRouterState } from '@tanstack/react-router'
import { isPlatformPath } from '@/api/http'
import { StepUpProvider } from '@/features/auth/StepUpProvider'
import { TenantGate } from '@/features/admin/TenantGate'

export interface RouterContext {
  queryClient: QueryClient
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
})

/**
 * Frames below the root: the application shell (`_shell`) and the sign-in card (`_auth`) on an organisation's host,
 * behind the tenant gate (suspension, the operator host); the operator console (`/platform`) has its own frame.
 */
function RootLayout() {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  if (isPlatformPath(pathname)) return <Outlet />
  return (
    <StepUpProvider>
      <TenantGate>
        <Outlet />
      </TenantGate>
    </StepUpProvider>
  )
}
