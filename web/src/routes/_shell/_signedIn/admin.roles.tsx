import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { RolesPage } from '@/features/access/RolesPage'

const crumbs = defineMessages({
  page: { id: 'nav.roles', defaultMessage: 'Roles' },
})

/** The menu shows it only to permission holders; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/roles')({
  component: RolesPage,
  staticData: { crumb: crumbs.page },
})
