import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { QueuesPage } from '@/features/workflows/QueuesPage'

const crumbs = defineMessages({
  page: { id: 'nav.queues', defaultMessage: 'Queues' },
})

/** The menu shows it to holders of `workflow.queues.manage`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflow/queues')({
  component: QueuesPage,
  staticData: { crumb: crumbs.page },
})
