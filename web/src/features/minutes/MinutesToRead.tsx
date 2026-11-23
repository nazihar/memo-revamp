import { Link } from '@tanstack/react-router'
import { FileText } from 'lucide-react'
import { defineMessages, useIntl } from 'react-intl'
import type { MeetingSummaryResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { fullName, whenLine } from '@/features/meetings/shared'

/** Texts of "Minutes to read" in "My meetings" (flow AF1): their own small catalogue part, `minutesToRead`. */
const tm = defineMessages({
  title: { id: 'minutesToRead.title', defaultMessage: 'Minutes to read' },
  REVIEW: { id: 'minutesToRead.review', defaultMessage: 'Draft minutes ready for your review' },
  CONFIRM: { id: 'minutesToRead.confirm', defaultMessage: 'Minutes ready for your approval' },
  COMMENT: { id: 'minutesToRead.comment', defaultMessage: 'Draft minutes: your comments are asked' },
  READ: { id: 'minutesToRead.read', defaultMessage: 'Draft minutes to read' },
  open: { id: 'minutesToRead.open', defaultMessage: 'Open the minutes' },
})

const tmv = defineMessages<{ openNamed: { name: string } }>({
  openNamed: { id: 'minutesToRead.openNamed', defaultMessage: 'Open the minutes of {name}' },
})

type Action = keyof Pick<typeof tm, 'REVIEW' | 'CONFIRM' | 'COMMENT' | 'READ'>
const ACTIONS: Action[] = ['REVIEW', 'CONFIRM', 'COMMENT', 'READ']

/** What the viewer has to do with a meeting's minutes, when it is something before their approval. */
function actionOf(m: MeetingSummaryResponse): Action | undefined {
  const action = ACTIONS.find((a) => a === m.minutes?.myAction)
  if (!action) return undefined
  // Approved minutes stay readable from the meeting page; they are not "to read" any more.
  return action === 'READ' && m.minutes?.state === 'APPROVED' ? undefined : action
}

/**
 * "Minutes to read" in "My meetings" (flow AF1, design E06 §3's `minutes.myAction`): the draft minutes the viewer is
 * asked to review, comment on or approve, first those waiting for them; each opens the minutes page.
 */
export function MinutesToRead({ meetings }: { meetings: MeetingSummaryResponse[] }) {
  const intl = useIntl()
  const due = meetings
    .map((m) => ({ m, action: actionOf(m) }))
    .filter((x): x is { m: MeetingSummaryResponse; action: Action } => Boolean(x.action))
    .sort((a, b) => ACTIONS.indexOf(a.action) - ACTIONS.indexOf(b.action))
  if (!due.length) return null
  return (
    <section aria-labelledby="my-minutes" className="flex flex-col gap-3">
      <h2 id="my-minutes" className="flex items-center gap-2 text-lg font-semibold [&_svg]:size-5">
        <FileText aria-hidden="true" />
        {intl.formatMessage(tm.title)}
      </h2>
      <ul className="flex flex-col gap-3">
        {due.map(({ m, action }) => {
          const name = fullName(intl, m)
          return (
            <li key={m.id} className="flex flex-col gap-2 rounded-lg border bg-card p-4 text-card-foreground">
              <p className="font-semibold">{name}</p>
              <p className="text-sm">{whenLine(intl, m)}</p>
              <p className="text-lg">{intl.formatMessage(tm[action])}</p>
              <div>
                <Button asChild variant={action === 'READ' ? 'outline' : 'default'} className="min-h-11 text-base">
                  <Link
                    to="/meetings/$meetingId/minutes"
                    params={{ meetingId: m.id }}
                    aria-label={intl.formatMessage(tmv.openNamed, { name })}
                  >
                    {intl.formatMessage(tm.open)}
                  </Link>
                </Button>
              </div>
            </li>
          )
        })}
      </ul>
    </section>
  )
}
