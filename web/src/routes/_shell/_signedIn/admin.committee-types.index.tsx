import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { ForumTypesPage } from '@/features/forum-types/ForumTypesPage'

const crumbs = defineMessages({
  page: { id: 'nav.forumTypes', defaultMessage: '{Forum} types' },
})

/** The menu shows it to holders of `forum.types.manage` or `forum.types.approve`; the API answers 403 to anyone else. */
export const Route = createFileRoute('/_shell/_signedIn/admin/committee-types/')({
  component: ForumTypesPage,
  staticData: { crumb: crumbs.page },
})
