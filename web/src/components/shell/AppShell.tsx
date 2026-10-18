import { Link, useMatches } from '@tanstack/react-router'
import { Menu } from 'lucide-react'
import { useState, type ReactNode } from 'react'
import { defineMessages, useIntl, type MessageDescriptor } from 'react-intl'
import { useTenantBranding } from '@/app/branding'
import { groupLabels, navigationGroups } from '@/app/navigation'
import { Button } from '@/components/ui/button'
import { Sheet } from '@/components/ui/dialog'
import { Breadcrumbs, type Crumb } from '@/components/ui/misc'
import { ToastProvider } from '@/components/ui/toast'
import { LocaleMenu } from './LocaleMenu'
import { ThemeMenu } from './ThemeMenu'

const messages = defineMessages({
  appName: { id: 'app.name', defaultMessage: 'Memo & Meeting Management' },
  productName: { id: 'app.product', defaultMessage: 'memo' },
  skipToContent: { id: 'shell.skipToContent', defaultMessage: 'Skip to main content' },
  navigation: { id: 'shell.navigation', defaultMessage: 'Main navigation' },
  openMenu: { id: 'shell.openMenu', defaultMessage: 'Open menu' },
})

declare module '@tanstack/react-router' {
  interface StaticDataRouteOption {
    /** Breadcrumb label of the route (E01 design §2). */
    crumb?: MessageDescriptor
  }
}

function TenantIdentity() {
  const intl = useIntl()
  const branding = useTenantBranding()
  const name = branding.name ?? intl.formatMessage(messages.appName)
  return (
    <div className="flex items-center gap-2.5 px-2 pb-4 pt-1">
      {branding.logoUrl ? (
        <img src={branding.logoUrl} alt="" className="size-9 rounded-md object-contain" />
      ) : (
        <span
          aria-hidden="true"
          className="flex size-9 shrink-0 items-center justify-center rounded-md bg-accent text-sm font-semibold text-accent-foreground"
        >
          {name.slice(0, 1)}
        </span>
      )}
      <div className="flex min-w-0 flex-col">
        <span className="truncate text-sm font-semibold">{name}</span>
        <span className="text-xs text-muted-foreground">{intl.formatMessage(messages.productName)}</span>
      </div>
    </div>
  )
}

function NavigationList({ onNavigate }: { onNavigate?: () => void }) {
  const intl = useIntl()
  return (
    <div className="flex flex-col gap-4">
      {navigationGroups().map(({ group, items }) => (
        <div key={group} className="flex flex-col gap-0.5">
          <h2 className="px-2.5 pb-1 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
            {intl.formatMessage(groupLabels[group])}
          </h2>
          <ul className="flex flex-col gap-0.5">
            {items.map((item) => (
              <li key={item.id}>
                <Link
                  to={item.to}
                  onClick={onNavigate}
                  activeOptions={{ exact: item.to === '/' }}
                  className="flex min-h-10 items-center gap-2.5 rounded-md px-2.5 text-sm hover:bg-accent hover:text-accent-foreground data-[status=active]:bg-accent data-[status=active]:font-semibold data-[status=active]:text-accent-foreground"
                >
                  <item.icon aria-hidden="true" className="size-4 shrink-0" />
                  {intl.formatMessage(item.label)}
                </Link>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

function useCrumbs(): Crumb[] {
  const intl = useIntl()
  return useMatches()
    .flatMap((m) => (m.staticData.crumb ? [{ crumb: m.staticData.crumb, pathname: m.pathname }] : []))
    .map(({ crumb, pathname }, i, all) => ({
      label: intl.formatMessage(crumb),
      href: i < all.length - 1 ? pathname : undefined,
    }))
}

/**
 * Application frame, Direction A (E01 design §2): grouped sidebar on the inline-start side (an off-canvas Sheet below
 * 1024px), top bar with breadcrumbs and global menus, main landmark. Logical properties only, so it mirrors under RTL.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const intl = useIntl()
  const [menuOpen, setMenuOpen] = useState(false)
  const crumbs = useCrumbs()
  const navLabel = intl.formatMessage(messages.navigation)
  return (
    <ToastProvider>
      <div className="flex min-h-dvh bg-background">
        <a
          href="#main"
          className="sr-only focus:not-sr-only focus:absolute focus:start-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-primary focus:px-4 focus:py-3 focus:text-primary-foreground"
        >
          {intl.formatMessage(messages.skipToContent)}
        </a>

        <nav aria-label={navLabel} className="hidden w-58 shrink-0 flex-col border-e bg-card px-3 py-4 lg:flex">
          <TenantIdentity />
          <NavigationList />
        </nav>

        <Sheet open={menuOpen} onOpenChange={setMenuOpen} label={navLabel}>
          <nav aria-label={navLabel} className="flex flex-col px-3 py-4">
            <TenantIdentity />
            <NavigationList
              onNavigate={() => {
                setMenuOpen(false)
              }}
            />
          </nav>
        </Sheet>

        <div className="flex min-w-0 flex-1 flex-col">
          <header className="sticky top-0 z-10 flex min-h-14 flex-wrap items-center gap-2 border-b bg-card px-4 py-1.5 lg:px-6">
            <Button
              variant="ghost"
              size="icon"
              className="lg:hidden"
              aria-label={intl.formatMessage(messages.openMenu)}
              aria-haspopup="dialog"
              onClick={() => {
                setMenuOpen(true)
              }}
            >
              <Menu aria-hidden="true" />
            </Button>
            <Breadcrumbs items={crumbs} className="min-w-0 flex-1" />
            <div className="ms-auto flex items-center gap-1">
              <LocaleMenu />
              <ThemeMenu />
              {/* Slots: notifications (E06) and the account menu (E03). */}
            </div>
          </header>
          <main id="main" tabIndex={-1} className="flex-1 px-4 py-6 focus:outline-none lg:px-6">
            {children}
          </main>
        </div>
      </div>
    </ToastProvider>
  )
}
