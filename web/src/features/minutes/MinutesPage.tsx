import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Check } from 'lucide-react'
import { useIntl } from 'react-intl'
import { getMeetingOptions } from '@/api/generated/@tanstack/react-query.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge, type Tone } from '@/components/ui/badge'
import { cn } from '@/lib/utils'
import { ApprovedPanel } from './ApprovedPanel'
import { notForMe, useMinutes, useMinutesItems } from './data'
import { mn, mnv } from './messages'
import { ReaderCard } from './ReaderCard'
import { ConfirmCard, ReviewCard } from './ReviewCard'
import { SecretaryPanel } from './SecretaryPanel'
import { day, meetingWords, type Minutes, type Stage } from './words'

const TONES: Record<Stage, Tone> = {
  DRAFT: 'neutral',
  CHAIR_REVIEW: 'warn',
  CIRCULATED: 'warn',
  AWAITING_CHAIR: 'warn',
  APPROVED: 'ok',
}

/**
 * `/meetings/$meetingId/minutes` (Phase 4 E06 T11, flows AD–AG; the address the minutes' notifications open): the
 * minutes as the viewer reads them, per stage — the steps, then the secretaries' drafting and circulation tools, the
 * chair's review, a reader's "Minutes to read", the chair's approval, and once approved the record and its corrections.
 * Not found to anyone who does not read the minutes now.
 */
export function MinutesPage({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const minutes = useMinutes(meetingId)
  const meeting = useQuery({ ...getMeetingOptions({ path: { meetingId } }), retry: false })
  const items = useMinutesItems(minutes.data, meetingId)
  if (minutes.isPending) return <LoadingState />
  if (minutes.isError) {
    return notForMe(minutes.error) ? (
      <ErrorState title={intl.formatMessage(mn.copyMissing)} />
    ) : (
      <ErrorState onRetry={() => void minutes.refetch()} />
    )
  }
  const page = minutes.data
  const m = meeting.data
  const name = meetingWords(intl, m)
  const timeZone = m?.timeZone
  const date = m ? day(intl, m.startsAt, m.timeZone) : undefined
  const access = page.viewerAccess
  const reading = (access === 'READER' || access === 'LINKED') && page.state !== 'APPROVED'
  return (
    <section aria-labelledby="minutes-title" className="flex max-w-3xl flex-col gap-5">
      <PageHeader
        className="mb-0"
        title={<span id="minutes-title">{intl.formatMessage(mnv.pageTitle, { meeting: name })}</span>}
        status={
          <Badge tone={TONES[page.state]}>
            {page.state === 'APPROVED' && page.approvedAt
              ? intl.formatMessage(mnv.approvedOn, { date: day(intl, page.approvedAt, timeZone) })
              : intl.formatMessage(mn[page.state])}
          </Badge>
        }
        description={
          date ? intl.formatMessage(mnv.lead, { date, mode: intl.formatMessage(mn[page.approval]) }) : undefined
        }
      />
      {m ? (
        <Link
          to="/meetings/$meetingId"
          params={{ meetingId }}
          className="inline-flex min-h-11 w-fit items-center underline-offset-4 hover:underline"
        >
          {intl.formatMessage(mn.backToMeeting)}
        </Link>
      ) : null}
      <Steps page={page} />
      {page.mayConfirm ? (
        <ConfirmCard page={page} meetingName={name} meetingDate={m?.startsAt} timeZone={timeZone} />
      ) : null}
      {access === 'REVIEWER' && page.mayReview ? <ReviewCard page={page} meetingName={name} /> : null}
      {reading && !page.mayConfirm ? (
        <ReaderCard page={page} meetingName={name} items={items} timeZone={timeZone} />
      ) : null}
      {access === 'MANAGER' && page.state !== 'APPROVED' ? (
        <SecretaryPanel page={page} items={items} meetingName={name} timeZone={timeZone} />
      ) : null}
      {page.state === 'APPROVED' ? (
        <ApprovedPanel page={page} items={items} meetingName={name} timeZone={timeZone} />
      ) : null}
    </section>
  )
}

/**
 * The steps (flow AD1): Draft, Chair's review, Circulated for comments, then "Approved at BRC 4/2026" (at the next
 * meeting) or "Comment period ended" (by circulation), and "Approved by the chair"; done ones ticked, the current one
 * marked for screen readers too.
 */
function Steps({ page }: { page: Minutes }) {
  const intl = useIntl()
  const approving = page.approvingMeeting
  const labels = [
    intl.formatMessage(mn.stepDraft),
    intl.formatMessage(mn.stepReview),
    intl.formatMessage(mn.stepCirculated),
    page.approval === 'AT_NEXT_MEETING'
      ? approving?.label
        ? intl.formatMessage(mnv.stepApprovedAt, { meeting: approving.label })
        : intl.formatMessage(mn.stepNextMeeting)
      : intl.formatMessage(mn.stepEnded),
    intl.formatMessage(mn.stepApproved),
  ]
  const at: Record<Stage, number> = { DRAFT: 0, CHAIR_REVIEW: 1, CIRCULATED: 2, AWAITING_CHAIR: 4, APPROVED: 5 }
  const now = at[page.state]
  return (
    <nav aria-label={intl.formatMessage(mn.steps)}>
      <ol className="flex flex-wrap gap-x-4 gap-y-2">
        {labels.map((label, i) => {
          const done = i < now
          const current = i === now
          return (
            <li
              key={label}
              aria-current={current ? 'step' : undefined}
              className={cn(
                'flex min-h-11 items-center gap-2 text-base',
                current ? 'font-semibold' : done ? '' : 'text-muted-foreground',
              )}
            >
              <span
                aria-hidden="true"
                className={cn(
                  'flex size-6 items-center justify-center rounded-full border text-sm',
                  done && 'border-status-ok-fg bg-status-ok-bg text-status-ok-fg',
                  current && 'border-primary bg-primary text-primary-foreground',
                )}
              >
                {done ? <Check className="size-4" /> : i + 1}
              </span>
              {label}
              <span className="sr-only">
                {done ? intl.formatMessage(mn.stepDone) : current ? intl.formatMessage(mn.stepNow) : ''}
              </span>
            </li>
          )
        })}
      </ol>
    </nav>
  )
}
