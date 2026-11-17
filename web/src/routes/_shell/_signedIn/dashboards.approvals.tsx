import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ApprovalsDashboardPage } from '@/features/dashboard/ApprovalsDashboardPage'
import { parseDashboardSearch } from '@/features/dashboard/dashboard-params'

const crumbs = defineMessages({
  page: { id: 'nav.dashboard', defaultMessage: 'Dashboard' },
})

/**
 * The Approvals dashboard (flow K). The scope and the period are in the address. The route's code, Recharts included,
 * loads only when the page opens (the router's automatic code splitting), so the shell's bundle does not grow.
 */
export const Route = createFileRoute('/_shell/_signedIn/dashboards/approvals')({
  validateSearch: parseDashboardSearch,
  component: DashboardRoute,
  staticData: { crumb: crumbs.page },
})

function DashboardRoute() {
  return <ApprovalsDashboardPage search={Route.useSearch()} />
}
