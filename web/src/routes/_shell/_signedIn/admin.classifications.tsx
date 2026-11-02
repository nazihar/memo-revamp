import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ClassificationsPage } from '@/features/access/ClassificationsPage'

const crumbs = defineMessages({
  page: { id: 'nav.classifications', defaultMessage: 'Classification' },
})

/** The menu shows it only to permission holders; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/classifications')({
  component: ClassificationsPage,
  staticData: { crumb: crumbs.page },
})
