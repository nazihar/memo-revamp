import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { DoaPage } from '@/features/workflows/DoaPage'

const crumbs = defineMessages({
  page: { id: 'nav.doa', defaultMessage: 'Delegation of authority' },
})

/** The menu shows it to holders of `workflow.doa.manage` or `.approve`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/workflow/doa')({
  component: DoaPage,
  staticData: { crumb: crumbs.page },
})
