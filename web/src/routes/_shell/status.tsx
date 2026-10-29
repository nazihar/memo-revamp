import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { HealthPage } from '@/features/health/HealthPage'

const crumbs = defineMessages({
  status: { id: 'nav.status', defaultMessage: 'Status' },
})

/** Platform status: public, like the health endpoint behind it. */
export const Route = createFileRoute('/_shell/status')({
  component: HealthPage,
  staticData: { crumb: crumbs.status },
})
