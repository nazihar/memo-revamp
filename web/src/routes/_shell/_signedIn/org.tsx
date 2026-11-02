import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { OrgPage } from '@/features/org/OrgPage'

const crumbs = defineMessages({
  org: { id: 'nav.org', defaultMessage: 'Organisation' },
})

/** The org directory: readable by every signed-in user; changes appear where the user may make them. */
export const Route = createFileRoute('/_shell/_signedIn/org')({
  validateSearch: (search: Record<string, unknown>): { unit?: string } =>
    typeof search.unit === 'string' ? { unit: search.unit } : {},
  component: OrgRoute,
  staticData: { crumb: crumbs.org },
})

function OrgRoute() {
  const { unit } = Route.useSearch()
  return <OrgPage selected={unit} />
}
