import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { HealthPage } from '@/features/health/HealthPage'

const crumbs = defineMessages({
  status: { id: 'nav.status', defaultMessage: 'Status' },
})

export const Route = createFileRoute('/')({
  component: HealthPage,
  staticData: { crumb: crumbs.status },
})
