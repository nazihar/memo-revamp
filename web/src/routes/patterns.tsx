import { createFileRoute, notFound } from '@tanstack/react-router'
import { lazy } from 'react'
import { defineMessages } from 'react-intl'
import { patternsEnabled } from '@/app/features'

const PatternsPage = lazy(async () => ({ default: (await import('@/features/patterns/PatternsPage')).PatternsPage }))

const crumbs = defineMessages({
  patterns: { id: 'nav.patterns', defaultMessage: 'Patterns' },
})

/** Patterns gallery: development and e2e builds only (E01 design §5, threat model I2). */
export const Route = createFileRoute('/patterns')({
  beforeLoad: () => {
    // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's notFound() is designed to be thrown
    if (!patternsEnabled) throw notFound()
  },
  validateSearch: (search: Record<string, unknown>): { tab?: string } =>
    typeof search.tab === 'string' ? { tab: search.tab } : {},
  component: PatternsPage,
  staticData: { crumb: crumbs.patterns },
})
