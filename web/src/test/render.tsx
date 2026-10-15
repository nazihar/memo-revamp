import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryHistory, createRouter, RouterProvider } from '@tanstack/react-router'
import { render, type RenderResult } from '@testing-library/react'
import axe from 'axe-core'
import type { ReactElement, ReactNode } from 'react'
import { expect } from 'vitest'
import { LocaleProvider } from '@/i18n/LocaleProvider'
import type { AppLocale } from '@/i18n/locales'
import { routeTree } from '@/routeTree.gen'
import { ThemeProvider } from '@/theme/ThemeProvider'

function Providers({
  children,
  locale,
  queryClient,
}: {
  children: ReactNode
  locale?: AppLocale
  queryClient: QueryClient
}) {
  return (
    <ThemeProvider>
      <LocaleProvider {...(locale ? { locale } : {})}>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </LocaleProvider>
    </ThemeProvider>
  )
}

const testQueryClient = () => new QueryClient({ defaultOptions: { queries: { retry: false } } })

/** Renders a component with the same providers as the app (theme, intl, query). */
export function renderWithProviders(ui: ReactElement, options: { locale?: AppLocale } = {}): RenderResult {
  const queryClient = testQueryClient()
  return render(
    <Providers queryClient={queryClient} {...(options.locale ? { locale: options.locale } : {})}>
      {ui}
    </Providers>,
  )
}

/** Renders the real route tree (shell included) at [path]. */
export function renderRoute(path = '/', options: { locale?: AppLocale } = {}): RenderResult {
  const queryClient = testQueryClient()
  const router = createRouter({
    routeTree,
    context: { queryClient },
    history: createMemoryHistory({ initialEntries: [path] }),
  })
  return render(
    <Providers queryClient={queryClient} {...(options.locale ? { locale: options.locale } : {})}>
      <RouterProvider router={router} />
    </Providers>,
  )
}

/** Zero serious/critical axe violations (TESTING_STRATEGY §4). Color contrast needs real layout: checked in e2e. */
export async function expectNoA11yViolations(container: Element): Promise<void> {
  const results = await axe.run(container, {
    runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] },
    rules: { 'color-contrast': { enabled: false } },
  })
  const serious = results.violations.filter((v) => v.impact === 'serious' || v.impact === 'critical')
  expect(serious.map((v) => `${v.id}: ${v.help} (${String(v.nodes.length)} nodes)`)).toEqual([])
}
