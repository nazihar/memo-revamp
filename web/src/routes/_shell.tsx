import { createFileRoute, Outlet, useRouterState } from '@tanstack/react-router'
import { AppShell } from '@/components/shell/AppShell'
import { BrandingApplier } from '@/features/admin/BrandingApplier'
import { AccountMenu } from '@/features/auth/AccountMenu'
import { useMyAccess } from '@/features/access/access'
import { useSession, viewerOf } from '@/features/auth/session'
import { NotificationBell } from '@/features/notifications/NotificationBell'
import { partForPath } from '@/i18n/catalogue-parts'
import { CatalogueGate } from '@/i18n/CatalogueGate'

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
    >
      <BrandingApplier />
      <CatalogueGate part={partForPath(pathname)}>
        <Outlet />
      </CatalogueGate>
    </AppShell>
  )
}
