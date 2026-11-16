import { createFileRoute, redirect, useLocation } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { SearchPage } from '@/features/search/SearchPage'
import { hashOf, parseSearchParams, qOf } from '@/features/search/search-params'

const crumbs = defineMessages({
  page: { id: 'nav.search', defaultMessage: 'Search' },
})

/**
 * The Search page (flow H2). The filters are in the query string and the words in the fragment (`#q=…`), which is never
 * sent to a server; back and forward, reload and links work with both.
 */
export const Route = createFileRoute('/_shell/_signedIn/search')({
  validateSearch: parseSearchParams,
  beforeLoad: ({ location }) => {
    // Words in the query string are ignored; replacing the entry also keeps them out of the browser's history.
    if ('q' in location.search) {
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
      throw redirect({
        to: '/search',
        search: parseSearchParams(location.search),
        hash: hashOf(qOf(location.hash)),
        replace: true,
      })
    }
  },
  component: SearchRoute,
  staticData: { crumb: crumbs.page },
})

function SearchRoute() {
  const params = Route.useSearch()
  const q = useLocation({ select: (location) => qOf(location.hash) })
  return <SearchPage params={params} q={q} />
}
