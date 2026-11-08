import { useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { platformLogout } from '@/api/generated/sdk.gen'
import { isProblem } from '@/api/http'
import { LocaleMenu } from '@/components/shell/LocaleMenu'
import { ThemeMenu } from '@/components/shell/ThemeMenu'
import { Button } from '@/components/ui/button'
import { ToastProvider } from '@/components/ui/toast'
import { StepUpProvider } from '@/features/auth/StepUpProvider'
import { pm } from './platform-messages'
import { refreshPlatformSession, usePlatformSession } from './platform-session'

/**
 * The operator console's own frame (design E07 §7): product and console name, its two sections, sign-out. No tenant
 * navigation, no tenant session: everything here talks to the platform API on the console's host.
 */
export function PlatformFrame({ children }: { children: ReactNode }) {
  const intl = useIntl()
  const session = usePlatformSession()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const signedIn = session.data?.state === 'AUTHENTICATED'
  const notServed = isProblem(session.error) && session.error.status === 404
  const signOut = async () => {
    await platformLogout({ throwOnError: true })
    await refreshPlatformSession(queryClient)
    await navigate({ to: '/platform/login' })
  }
  return (
    <ToastProvider>
      <StepUpProvider platform>
        <div className="flex min-h-dvh flex-col bg-background">
          <header className="flex flex-wrap items-center gap-3 border-b bg-card px-4 py-2">
            <span className="font-semibold">{intl.formatMessage(pm.product)}</span>
            <span className="text-sm text-muted-foreground">{intl.formatMessage(pm.console)}</span>
            {signedIn ? (
              <nav aria-label={intl.formatMessage(pm.console)} className="ms-4 flex gap-3 text-sm">
                <Link
                  to="/platform"
                  activeOptions={{ exact: true }}
                  className="hover:underline [&.active]:font-semibold"
                >
                  {intl.formatMessage(pm.navTenants)}
                </Link>
                <Link to="/platform/audit" className="hover:underline [&.active]:font-semibold">
                  {intl.formatMessage(pm.navAudit)}
                </Link>
              </nav>
            ) : null}
            <div className="ms-auto flex items-center gap-1">
              <LocaleMenu />
              <ThemeMenu />
              {signedIn ? (
                <Button variant="ghost" onClick={() => void signOut()}>
                  {intl.formatMessage(pm.signOut)}
                </Button>
              ) : null}
            </div>
          </header>
          <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
            {notServed ? <p>{intl.formatMessage(pm.notServed)}</p> : children}
          </main>
        </div>
      </StepUpProvider>
    </ToastProvider>
  )
}

/** The sign-in card of the console (sign-in, enrolment link). */
export function PlatformCard({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto mt-4 w-full max-w-md rounded-lg border bg-card p-6 text-card-foreground shadow-sm sm:mt-12">
      {children}
    </div>
  )
}
