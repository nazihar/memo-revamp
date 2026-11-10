import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { WorkflowTasksPage } from '@/features/memo/WorkflowTasksPage'

const crumbs = defineMessages({
  page: { id: 'nav.workflowTasks', defaultMessage: 'Open tasks' },
})

/** The menu shows it only to holders of `workflow.tasks.reassign`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflow/tasks')({
  component: WorkflowTasksPage,
  staticData: { crumb: crumbs.page },
})
