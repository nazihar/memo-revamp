import { createFileRoute, Outlet } from '@tanstack/react-router'
import { AppShell } from '@/components/shell/AppShell'
import { AccountMenu } from '@/features/auth/AccountMenu'
import { useSession, viewerOf } from '@/features/auth/session'

/** The application frame; signed-in pages sit below `_signedIn`, the status page and the gallery do not. */
export const Route = createFileRoute('/_shell')({
  component: ShellLayout,
})

function ShellLayout() {
  const session = useSession()
  return (
    <AppShell viewer={viewerOf(session.data)} account={<AccountMenu />}>
      <Outlet />
    </AppShell>
  )
}
