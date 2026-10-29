import type { QueryClient } from '@tanstack/react-query'
import { createRootRouteWithContext, Outlet } from '@tanstack/react-router'
import { StepUpProvider } from '@/features/auth/StepUpProvider'

export interface RouterContext {
  queryClient: QueryClient
}

export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
})

/** Two frames below the root: the application shell (`_shell`) and the sign-in card (`_auth`). */
function RootLayout() {
  return (
    <StepUpProvider>
      <Outlet />
    </StepUpProvider>
  )
}
