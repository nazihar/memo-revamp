import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useIntl } from 'react-intl'
import { listMemoForumSubmissionsOptions, listMemoNotesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { NoteEntryView, SubmissionResponse } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { pdmv } from '@/features/forums/papers-decision-messages'
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
  // The note sheet's committee decisions (E06 T10, flow AH): the meeting and the resolution number of a decided paper.
  const notes = useQuery(listMemoNotesOptions({ path: { id: memoId } }))
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
            <DecidedAt submission={s} notes={notes.data?.entries ?? []} />
            {s.waits ? null : <p className="text-xs text-muted-foreground">{intl.formatMessage(pm.forInformation)}</p>}
            <PaperTimeline submission={s} />
          </li>
        ))}
      </ul>
    </section>
  )
}

/**
 * "Decided at MCC 4/2026 — Approved with conditions — MCC/2026/001" (flow AH) for a decided paper, from the note sheet's
 * FORUM_DECIDED entry of the same committee (the latest one); nothing when the decision came without a meeting.
 */
function DecidedAt({ submission: s, notes }: { submission: SubmissionResponse; notes: NoteEntryView[] }) {
  const intl = useIntl()
  if (s.status !== 'DECIDED') return null
  const note = notes.findLast((n) => n.actionKind === 'FORUM_DECIDED' && n.committee === s.forumName && n.meetingLabel)
  if (!note?.meetingLabel) return null
  const outcome = s.outcome ?? note.committeeOutcome ?? 'other'
  return (
    <p className="text-sm font-medium">
      {note.resolution
        ? intl.formatMessage(pdmv.decidedAt, { meeting: note.meetingLabel, outcome, number: note.resolution })
        : intl.formatMessage(pdmv.decidedAtNoNumber, { meeting: note.meetingLabel, outcome })}
    </p>
  )
}
