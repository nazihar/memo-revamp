import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { AgendaBuilderPage } from '@/features/meetings/agenda/AgendaBuilderPage'

const crumbs = defineMessages({
  page: { id: 'nav.agenda', defaultMessage: 'Agenda' },
})

/** The agenda builder of one meeting (Phase 4 E03 T7, flows O and Q), for the committee's secretaries and chair. */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId_/agenda')({
  component: AgendaRoute,
  staticData: { crumb: crumbs.page },
})

function AgendaRoute() {
  const { meetingId } = Route.useParams()
  return <AgendaBuilderPage key={meetingId} meetingId={meetingId} />
}
