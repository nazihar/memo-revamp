import type { QueryClient } from '@tanstack/react-query'
import { createRootRouteWithContext, Outlet } from '@tanstack/react-router'
import { t } from '@/i18n/messages'

export interface RouterContext {
  queryClient: QueryClient
}

// Placeholder shell; E03 replaces it with the shadcn/ui application shell.
export const Route = createRootRouteWithContext<RouterContext>()({
  component: RootLayout,
})

function RootLayout() {
  return (
    <>
      <header>
        <p>{t('app.name')}</p>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  )
}
