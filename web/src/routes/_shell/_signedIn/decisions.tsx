import { createFileRoute, redirect, useLocation } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { RegisterPage } from '@/features/decision-register/RegisterPage'
import { hashOf, parseRegisterSearch, qOf } from '@/features/decision-register/register-params'

const crumbs = defineMessages({
  page: { id: 'nav.decisions', defaultMessage: 'Decisions' },
})

/**
 * The decision register (Phase 4 E08 T6, flows AS and AT). The filters are in the query string and the words in the
 * fragment (`#q=…`), which is never sent to a server; back and forward, reload and links keep both. The page's code
 * loads only when it opens, and its messages with it (the `decisionRegister` catalogue part).
 */
export const Route = createFileRoute('/_shell/_signedIn/decisions')({
  validateSearch: parseRegisterSearch,
  beforeLoad: ({ location }) => {
    // Words in the query string are ignored; replacing the entry also keeps them out of the browser's history.
    if ('q' in location.search) {
      // eslint-disable-next-line @typescript-eslint/only-throw-error -- TanStack Router's redirect() is designed to be thrown
      throw redirect({
        to: '/decisions',
        search: parseRegisterSearch(location.search),
        hash: hashOf(qOf(location.hash)),
        replace: true,
      })
    }
  },
  component: DecisionsRoute,
  staticData: { crumb: crumbs.page },
})

function DecisionsRoute() {
  const search = Route.useSearch()
  const q = useLocation({ select: (location) => qOf(location.hash) })
  return <RegisterPage search={search} q={q} />
}
