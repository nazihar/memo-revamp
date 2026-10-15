import { Link } from '@tanstack/react-router'
import { Activity } from 'lucide-react'
import type { ReactNode } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { LocaleMenu } from './LocaleMenu'
import { ThemeMenu } from './ThemeMenu'

const messages = defineMessages({
  appName: { id: 'app.name', defaultMessage: 'Memo & Meeting Management' },
  skipToContent: { id: 'shell.skipToContent', defaultMessage: 'Skip to main content' },
  navigation: { id: 'shell.navigation', defaultMessage: 'Main navigation' },
  navStatus: { id: 'nav.status', defaultMessage: 'Status' },
})

/**
 * Application shell (E03 design §1): skip link, banner, navigation on the inline-start side, main landmark.
 * Logical properties only (ps-/pe-/ms-/me-/start-/end-) so the layout mirrors under RTL.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const intl = useIntl()
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:start-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-3 focus:text-primary-foreground"
      >
        {intl.formatMessage(messages.skipToContent)}
      </a>
      <header className="flex items-center justify-between gap-4 border-b bg-card px-4 py-2">
        <p className="text-base font-semibold">{intl.formatMessage(messages.appName)}</p>
        <div className="flex items-center gap-2">
          <LocaleMenu />
          <ThemeMenu />
        </div>
      </header>
      <div className="flex flex-1 flex-col md:flex-row">
        <nav
          aria-label={intl.formatMessage(messages.navigation)}
          className="border-b bg-card p-2 md:w-56 md:border-e md:border-b-0"
        >
          <ul className="flex gap-1 md:flex-col">
            <li>
              <Link
                to="/"
                className="flex min-h-11 items-center gap-2 rounded-md px-3 text-sm hover:bg-accent hover:text-accent-foreground data-[status=active]:bg-accent data-[status=active]:font-semibold"
              >
                <Activity aria-hidden="true" className="size-4" />
                {intl.formatMessage(messages.navStatus)}
              </Link>
            </li>
          </ul>
        </nav>
        <main id="main" tabIndex={-1} className="flex-1 p-4 md:p-8 focus:outline-none">
          {children}
        </main>
      </div>
    </div>
  )
}
