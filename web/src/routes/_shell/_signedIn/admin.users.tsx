import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { UsersPage } from '@/features/admin/UsersPage'

const crumbs = defineMessages({
  users: { id: 'nav.users', defaultMessage: 'Users' },
})

/** Tenant admins only: the menu hides it for others and the API answers 403. */
export const Route = createFileRoute('/_shell/_signedIn/admin/users')({
  component: UsersPage,
  staticData: { crumb: crumbs.users },
})
