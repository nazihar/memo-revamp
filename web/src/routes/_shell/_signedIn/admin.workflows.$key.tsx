import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { WorkflowDefinitionPage } from '@/features/workflows/WorkflowDefinitionPage'

const crumbs = defineMessages({
  page: { id: 'nav.workflow', defaultMessage: 'Workflow' },
})

/** One definition and its versions; the version shown is in the address, so a link opens it. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflows/$key')({
  validateSearch: (search: Record<string, unknown>): { version?: number } =>
    typeof search.version === 'number' && Number.isInteger(search.version) ? { version: search.version } : {},
  component: WorkflowRoute,
  staticData: { crumb: crumbs.page },
})

function WorkflowRoute() {
  const { key } = Route.useParams()
  const { version } = Route.useSearch()
  return <WorkflowDefinitionPage definitionKey={key} {...(version ? { version } : {})} />
}
