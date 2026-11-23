import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MinutesPage } from '@/features/minutes/MinutesPage'

const crumbs = defineMessages({
  page: { id: 'nav.minutes', defaultMessage: 'Minutes' },
})

/** A meeting's minutes (Phase 4 E06 T11, flows AD–AG), the address the minutes' notifications open. */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId_/minutes')({
  component: MinutesRoute,
  staticData: { crumb: crumbs.page },
})

function MinutesRoute() {
  const { meetingId } = Route.useParams()
  return <MinutesPage key={meetingId} meetingId={meetingId} />
}
