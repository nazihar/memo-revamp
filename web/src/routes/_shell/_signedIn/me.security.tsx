import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MySecurityPage } from '@/features/me/MySecurityPage'

const crumbs = defineMessages({
  security: { id: 'account.security', defaultMessage: 'My security' },
})

export const Route = createFileRoute('/_shell/_signedIn/me/security')({
  component: MySecurityPage,
  staticData: { crumb: crumbs.security },
})
