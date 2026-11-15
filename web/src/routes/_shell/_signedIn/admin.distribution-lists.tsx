import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { DistributionListsPage } from '@/features/memo/DistributionListsPage'

const crumbs = defineMessages({
  page: { id: 'nav.distributionLists', defaultMessage: 'Distribution lists' },
})

/** The menu shows it to holders of `memo.distribution.manage`; the API refuses changes from anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/distribution-lists')({
  component: DistributionListsPage,
  staticData: { crumb: crumbs.page },
})
