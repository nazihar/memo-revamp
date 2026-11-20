import { Link } from '@tanstack/react-router'
import { CalendarDays } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { MeetingSummaryResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { useTerms } from '@/features/terms/terms'
import { mm, mmt, mmv } from './messages'
import { upcoming, useMyMeetings } from './mine'
import { CardReply } from './replies/CardReply'
import { fullName, whenLine } from './shared'
import { StatusBadge } from './StatusBadge'
import { notEnded, yourTimes } from './time'

/**
 * One meeting as a card (flow N3): the date and times in the meeting's zone and the viewer's own when it differs, the
 * committee and the meeting's number, how it is held, "Agenda published · 2 changes since you last looked", and
 * "Open meeting". Above the button, the viewer's answer to "Will you attend?" and, while one is wanted, the answers
 * themselves (Phase 4 E05 T7, flow V6).
 */
export function MeetingCard({ meeting: m }: { meeting: MeetingSummaryResponse }) {
  const intl = useIntl()
  const mine = yourTimes(intl, m)
  const name = fullName(intl, m)
  const titleId = `meeting-card-${m.id}`
  const when = whenLine(intl, m)
  return (
    <li>
      <article
        aria-labelledby={titleId}
        className="flex flex-col gap-2 rounded-lg border bg-card p-4 text-card-foreground"
      >
        <p className="flex flex-wrap gap-x-1 font-semibold">
          <span>{when}</span>
          {mine ? <span className="font-normal">{intl.formatMessage(mmv.yourTimes, { times: mine })}</span> : null}
        </p>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <h3 id={titleId} className="text-base font-medium">
            {intl.formatMessage(mmv.dot, { first: name, second: intl.formatMessage(mm[m.mode]) })}
          </h3>
          {m.status !== 'SCHEDULED' ? <StatusBadge meeting={m} officer={m.relation === 'OFFICER'} /> : null}
        </div>
        {m.agendaRevision ? (
          <p className="text-sm">
            {m.changesSinceSeen
              ? intl.formatMessage(mmv.dot, {
                  first: intl.formatMessage(mm.cardAgenda),
                  second: intl.formatMessage(mmv.cardChanges, { count: m.changesSinceSeen }),
                })
              : intl.formatMessage(mm.cardAgenda)}
          </p>
        ) : null}
        {m.relation === 'GUEST' ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.asGuest)}</p>
        ) : null}
        <CardReply meeting={m} titleId={titleId} />
        <div>
          <Button asChild variant="outline" className="min-h-11">
            <Link
              to="/meetings/$meetingId"
              params={{ meetingId: m.id }}
              aria-label={intl.formatMessage(mmv.openNamed, { name, date: when })}
            >
              {intl.formatMessage(mm.openMeeting)}
            </Link>
          </Button>
        </div>
      </article>
    </li>
  )
}

/** The home page's "My meetings" card (flow N3): the next three meetings and a link to all of them; absent without any. */
export function MyMeetingsCard() {
  const intl = useIntl()
  const mine = useMyMeetings()
  const next = upcoming(mine.data ?? []).slice(0, 3)
  if (!next.length) return null
  return (
    <section aria-labelledby="home-meetings" className="mb-6 flex flex-col gap-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="home-meetings" className="flex items-center gap-2 text-lg font-semibold [&_svg]:size-5">
          <CalendarDays aria-hidden="true" />
          {intl.formatMessage(mm.myTitle)}
        </h2>
        <Link
          to="/me/meetings"
          className="inline-flex min-h-11 items-center text-sm underline-offset-4 hover:underline"
        >
          {intl.formatMessage(mm.myAll)}
        </Link>
      </div>
      <ul className="flex flex-col gap-3">
        {next.map((m) => (
          <MeetingCard key={m.id} meeting={m} />
        ))}
      </ul>
    </section>
  )
}

/**
 * `/me/meetings` (flow N3): meetings coming up, the soonest first (a cancelled one says so), then those of the last 30
 * days, the latest first.
 */
export function MyMeetingsPage() {
  const intl = useIntl()
  const terms = useTerms()
  const mine = useMyMeetings()
  const coming = (mine.data ?? []).filter((m) => notEnded(m))
  const recent = (mine.data ?? []).filter((m) => !notEnded(m)).reverse()
  return (
    <section aria-labelledby="my-meetings-title" className="flex max-w-3xl flex-col gap-6">
      <PageHeader
        className="mb-0"
        title={<span id="my-meetings-title">{intl.formatMessage(mm.myTitle)}</span>}
        description={intl.formatMessage(mmt.myLead, terms)}
      />
      {mine.isPending ? (
        <LoadingState rows={3} />
      ) : mine.isError ? (
        <ErrorState onRetry={() => void mine.refetch()} />
      ) : (
        <>
          <section aria-labelledby="my-meetings-upcoming" className="flex flex-col gap-3">
            <h2 id="my-meetings-upcoming" className="text-lg font-semibold">
              {intl.formatMessage(mm.myUpcoming)}
            </h2>
            {coming.length ? (
              <ul className="flex flex-col gap-3">
                {coming.map((m) => (
                  <MeetingCard key={m.id} meeting={m} />
                ))}
              </ul>
            ) : (
              <EmptyState title={intl.formatMessage(mm.myEmpty)} />
            )}
          </section>
          {recent.length ? (
            <section aria-labelledby="my-meetings-recent" className="flex flex-col gap-3">
              <h2 id="my-meetings-recent" className="text-lg font-semibold">
                {intl.formatMessage(mm.myRecent)}
              </h2>
              <ul className="flex flex-col gap-3">
                {recent.map((m) => (
                  <MeetingCard key={m.id} meeting={m} />
                ))}
              </ul>
            </section>
          ) : null}
        </>
      )}
    </section>
  )
}

/** "Next meeting: …" on a "My committees" card, linking to the meeting; nothing when none is coming up. */
export function NextMeeting({ forumId, meetings }: { forumId: string; meetings: MeetingSummaryResponse[] }) {
  const intl = useIntl()
  const next = upcoming(meetings).find((m) => m.forumId === forumId)
  if (!next) return null
  return (
    <p className="text-sm">
      <Link
        to="/meetings/$meetingId"
        params={{ meetingId: next.id }}
        className="inline-flex min-h-11 items-center underline underline-offset-4"
      >
        {intl.formatMessage(mmv.nextMeeting, { when: whenLine(intl, next) })}
      </Link>
    </p>
  )
}
