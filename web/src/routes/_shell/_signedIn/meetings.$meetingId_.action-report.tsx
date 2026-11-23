import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { ActionReportPage } from '@/features/actions/ActionReportPage'
import { am } from '@/features/actions/messages'

interface ReportSearch {
  revision?: number
}

/** The agenda revision whose report is shown (`?revision=2`); the latest when absent. */
function parseReportSearch(search: Record<string, unknown>): ReportSearch {
  const n = Number(search.revision)
  return Number.isInteger(n) && n > 0 && n < 10_000 ? { revision: n } : {}
}

/** The action taken report of a meeting's agenda (Phase 4 E07 T11, flow AO), for the readers of "Matters arising". */
export const Route = createFileRoute('/_shell/_signedIn/meetings/$meetingId_/action-report')({
  validateSearch: parseReportSearch,
  component: ReportRoute,
  staticData: { crumb: am.crumbReport },
})

function ReportRoute() {
  const { meetingId } = Route.useParams()
  const { revision } = Route.useSearch()
  const navigate = useNavigate()
  return (
    <ActionReportPage
      key={meetingId}
      meetingId={meetingId}
      revision={revision}
      onRevision={(next) =>
        void navigate({
          to: '/meetings/$meetingId/action-report',
          params: { meetingId },
          search: next ? { revision: next } : {},
        })
      }
    />
  )
}
