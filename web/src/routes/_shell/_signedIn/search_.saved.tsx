import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { SavedSearchesPage } from '@/features/search/SavedSearchesPage'

const crumbs = defineMessages({
  page: { id: 'nav.savedSearches', defaultMessage: 'Saved searches' },
})

/** The person's saved searches (flow H3). */
export const Route = createFileRoute('/_shell/_signedIn/search_/saved')({
  component: SavedSearchesPage,
  staticData: { crumb: crumbs.page },
})
