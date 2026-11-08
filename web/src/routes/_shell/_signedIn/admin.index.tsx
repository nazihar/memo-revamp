import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { AdminHomePage } from '@/features/admin/AdminHomePage'

const crumbs = defineMessages({
  page: { id: 'nav.admin', defaultMessage: 'Overview' },
})

/** The menu shows it only to those who may use it; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/')({
  component: AdminHomePage,
  staticData: { crumb: crumbs.page },
})
