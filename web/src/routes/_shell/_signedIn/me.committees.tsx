import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MyForumsPage } from '@/features/forums/MyForumsPage'

const crumbs = defineMessages({
  page: { id: 'nav.myForums', defaultMessage: 'My {forums}' },
})

export const Route = createFileRoute('/_shell/_signedIn/me/committees')({
  component: MyForumsPage,
  staticData: { crumb: crumbs.page },
})
