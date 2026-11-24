import { createFileRoute } from '@tanstack/react-router'
import { defineMessages } from 'react-intl'
import { MinutesEditorPage } from '@/features/minutes/MinutesEditorPage'

const crumbs = defineMessages({
  page: { id: 'nav.minutesEditor', defaultMessage: 'Editor' },
})

/** The draft minutes in the browser editor (flow AD2); the API decides whether this user edits or suggests changes. */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId_/minutes_/edit')({
  component: MinutesEditorRoute,
  staticData: { crumb: crumbs.page },
})

function MinutesEditorRoute() {
  const { meetingId } = Route.useParams()
  return <MinutesEditorPage key={meetingId} meetingId={meetingId} />
}
