import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { WorkflowDefinitionsPage } from '@/features/workflows/WorkflowDefinitionsPage'

const crumbs = defineMessages({
  page: { id: 'nav.workflows', defaultMessage: 'Workflows' },
})

/** The menu shows it to holders of `workflow.definitions.manage` or `.approve`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflows/')({
  component: WorkflowDefinitionsPage,
  staticData: { crumb: crumbs.page },
})
