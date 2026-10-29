import type { ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { useSession } from './session'
import { LocaleMenu } from '@/components/shell/LocaleMenu'
import { ThemeMenu } from '@/components/shell/ThemeMenu'
import { ToastProvider } from '@/components/ui/toast'

const messages = defineMessages({
  product: { id: 'app.product', defaultMessage: 'memo' },
})

/** Unauthenticated frame (E03 design §11): a centred card with the organisation's name, language and theme menus. */
export function AuthLayout({ children }: { children: ReactNode }) {
  const intl = useIntl()
  const session = useSession()
  const organisation = session.data?.organisation
  return (
    <ToastProvider>
      <div className="flex min-h-dvh flex-col bg-background">
        <header className="flex justify-end gap-1 px-4 py-2">
          <LocaleMenu />
          <ThemeMenu />
        </header>
        <main id="main" className="flex flex-1 items-start justify-center px-4 pb-12 pt-6 sm:pt-16">
          <div className="w-full max-w-md">
            <div className="mb-6 flex items-center gap-2.5">
              <span
                aria-hidden="true"
                className="flex size-10 shrink-0 items-center justify-center rounded-md bg-accent text-base font-semibold text-accent-foreground"
              >
                {(organisation ?? 'm').slice(0, 1)}
              </span>
              <div className="flex min-w-0 flex-col">
                <span className="truncate font-semibold">{organisation ?? intl.formatMessage(messages.product)}</span>
                <span className="text-xs text-muted-foreground">{intl.formatMessage(messages.product)}</span>
              </div>
            </div>
            <div className="rounded-lg border bg-card p-6 text-card-foreground shadow-sm">{children}</div>
          </div>
        </main>
      </div>
    </ToastProvider>
  )
}

/** Heading and lead text of an auth card. */
export function AuthHeading({ title, lead }: { title: ReactNode; lead?: ReactNode }) {
  return (
    <div className="mb-5">
      <h1 className="text-xl font-semibold">{title}</h1>
      {lead ? <p className="mt-1 text-sm text-muted-foreground">{lead}</p> : null}
    </div>
  )
}

/** An error the user can act on, announced to screen readers. */
export function FormAlert({ children }: { children: ReactNode }) {
  if (!children) return null
  return (
    <p role="alert" className="mb-4 rounded-md bg-status-danger-bg px-3 py-2 text-sm text-status-danger-fg">
      {children}
    </p>
  )
}
