import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { SubstitutionsPage } from '@/features/workflows/SubstitutionsPage'

const crumbs = defineMessages({
  page: { id: 'nav.substitutions', defaultMessage: 'Substitutions' },
})

/** The menu shows it to holders of `workflow.tasks.reassign`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflow/substitutions')({
  component: SubstitutionsPage,
  staticData: { crumb: crumbs.page },
})
