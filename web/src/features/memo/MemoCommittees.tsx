import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import { listMemoForumSubmissionsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Badge } from '@/components/ui/badge'
import { isOpen, paperTone } from '@/features/forums/papers'
import { PaperTimeline } from '@/features/forums/PaperTimeline'
import { pm, pmv } from '@/features/forums/papers-messages'
import { useTerms } from '@/features/terms/terms'

/**
 * The memo's committee panel (flow M2, design E02 §10): every paper of the memo, newest first, with its status and
 * timeline ("Sent to the Management Credit Committee", "Accepted for the meeting of 12 Dec", "Returned: reason"). Shown
 * only when the API returns papers; committees above the reader's clearance are left out by the server.
 */
export function MemoCommittees({ memoId }: { memoId: string }) {
  const intl = useIntl()
  const terms = useTerms()
  const papers = useQuery(listMemoForumSubmissionsOptions({ path: { memoId } }))
  const rows = papers.data ?? []
  if (rows.length === 0) return null
  return (
    <section aria-labelledby="memo-committees" className="rounded-md border p-4">
      <h2 id="memo-committees" className="text-base font-semibold">
        {intl.formatMessage(pmv.panelTitle, terms)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(pmv.panelLead, terms)}</p>
      <ul className="flex flex-col gap-4">
        {rows.map((s) => (
          <li key={s.id} className="flex flex-col gap-2 border-t pt-3 first:border-t-0 first:pt-0">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <Link
                to="/committees/$forumId/submissions/$submissionId"
                params={{ forumId: s.forumId, submissionId: s.id }}
                className="font-medium underline-offset-4 hover:underline"
              >
                {isOpen(s) ? intl.formatMessage(pmv.withForum, { committee: s.forumName }) : s.forumName}
              </Link>
              <Badge tone={paperTone[s.status]}>{intl.formatMessage(pm[s.status])}</Badge>
            </div>
            {s.waits ? null : <p className="text-xs text-muted-foreground">{intl.formatMessage(pm.forInformation)}</p>}
            <PaperTimeline submission={s} />
          </li>
        ))}
      </ul>
    </section>
  )
}
