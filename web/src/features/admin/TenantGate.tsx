import { Navigate } from '@tanstack/react-router'
import type { ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { isProblem, problemType, PROBLEM } from '@/api/http'
import { useSession } from '@/features/auth/session'
import { ToastProvider } from '@/components/ui/toast'

const messages = defineMessages({
  suspendedTitle: { id: 'tenant.suspended.title', defaultMessage: 'This organisation is suspended' },
  suspendedLead: {
    id: 'tenant.suspended.lead',
    defaultMessage: 'Nobody can sign in at the moment. Contact your administrator or the platform team.',
  },
})

/**
 * Before any organisation page (ADR-0031): a suspended organisation gets a full-page notice instead of the app, and the
 * operator console's host, which serves no organisation, goes to the console.
 */
export function TenantGate({ children }: { children: ReactNode }) {
  const intl = useIntl()
  const session = useSession()
  if (problemType(session.error) === PROBLEM.tenantSuspended) {
    return (
      <ToastProvider>
        <main id="main" className="flex min-h-dvh items-center justify-center bg-background px-4">
          <div className="max-w-md rounded-lg border bg-card p-6 text-card-foreground shadow-sm">
            <h1 className="text-xl font-semibold">{intl.formatMessage(messages.suspendedTitle)}</h1>
            <p className="mt-2 text-sm text-muted-foreground">{intl.formatMessage(messages.suspendedLead)}</p>
          </div>
        </main>
      </ToastProvider>
    )
  }
  if (isProblem(session.error) && session.error.status === 404) return <Navigate to="/platform" />
  return children
}
