import { createFileRoute, Outlet, useRouterState } from '@tanstack/react-router'
import { lazy, Suspense } from 'react'
import { AppShell } from '@/components/shell/AppShell'
import { BrandingApplier } from '@/features/admin/BrandingApplier'
import { AccountMenu } from '@/features/auth/AccountMenu'
import { useMyAccess } from '@/features/access/access'
import { useSession, viewerOf } from '@/features/auth/session'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { partsForPath } from '@/i18n/catalogue-parts'
import { CatalogueGate } from '@/i18n/CatalogueGate'

/**
 * The search box shows to signed-in users only, so its code (with its messages and recent searches) loads when it first
 * shows, not with every first page (the first-route JS budget, NFR P5). Until then an empty field of the same size holds
 * its place, so the top bar does not move.
 */
const SearchBox = lazy(async () => ({ default: (await import('@/features/search/SearchBox')).SearchBox }))

/** The application frame; signed-in pages sit below `_signedIn`, the status page and the gallery do not. */
export const Route = createFileRoute('/_shell')({
  component: ShellLayout,
})

function ShellLayout() {
  const session = useSession()
  const access = useMyAccess(session.data?.state === 'AUTHENTICATED')
  const pathname = useRouterState({ select: (state) => state.location.pathname })
  return (
    <AppShell
      viewer={viewerOf(session.data, access.data)}
      account={<AccountMenu />}
      notifications={<NotificationBell />}
      search={
        session.data?.state === 'AUTHENTICATED' ? (
          <Suspense
            fallback={<div aria-hidden="true" className="min-h-10 w-full rounded-md border border-input bg-card" />}
          >
            <SearchBox />
          </Suspense>
        ) : undefined
      }
    >
      <BrandingApplier />
      <CatalogueGate parts={partsForPath(pathname)}>
        <Outlet />
      </CatalogueGate>
    </AppShell>
  )
}
