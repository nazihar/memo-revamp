import { useQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { ExternalLink, UserPlus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingOptions, listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingGuestResponse, MeetingResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { AddGuestDialog, MeetingMenu, RemoveGuestDialog } from './MeetingDialogs'
import { mm, mmv } from './messages'
import { fullName } from './shared'
import { StatusBadge } from './StatusBadge'
import { cityOf, whenIn, yourTimes } from './time'

/**
 * `/meetings/$meetingId`: the meeting in brief for everyone who may see it (the link notifications open), with its
 * secretaries' ⋯ menu and guests. The full meeting page with the agenda (flow P) replaces the agenda line in T8.
 */
export function MeetingPage({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const meeting = useQuery(getMeetingOptions({ path: { meetingId } }))
  if (meeting.isPending) return <LoadingState />
  if (meeting.isError) return <ErrorState onRetry={() => void meeting.refetch()} />
  const m = meeting.data
  const { date, times } = whenIn(intl, m, m.timeZone)
  const mine = yourTimes(intl, m)
  const officer = m.relation === 'OFFICER'
  return (
    <section aria-labelledby="meeting-title" className="flex max-w-3xl flex-col gap-5">
      <PageHeader
        className="mb-0"
        title={<span id="meeting-title">{fullName(intl, m)}</span>}
        status={<StatusBadge meeting={m} officer={officer} />}
        actions={
          m.canManage ? <MeetingMenu meetingId={m.id} name={fullName(intl, m)} status={m.status} meeting={m} /> : null
        }
      />
      <Link
        to="/committees/$forumId"
        params={{ forumId: m.forumId }}
        search={{ tab: 'meetings' }}
        className="w-fit text-sm underline-offset-4 hover:underline"
      >
        {intl.formatMessage(mmv.backTo, { name: m.forumName })}
      </Link>
      <div className="flex flex-col gap-1 text-lg">
        <p className="font-semibold">{date}</p>
        <p>{intl.formatMessage(mmv.zoneTimes, { times, city: cityOf(m.timeZone) })}</p>
        {mine ? <p className="text-muted-foreground">{intl.formatMessage(mmv.yourTimes, { times: mine })}</p> : null}
      </div>
      {m.status === 'CANCELLED' && m.statusReason ? (
        <p role="status" className="rounded-md border border-destructive/40 bg-destructive/5 p-3">
          {intl.formatMessage(mmv.cancelledBecause, { reason: m.statusReason })}
        </p>
      ) : null}
      {m.status === 'DRAFT' ? <p className="text-muted-foreground">{intl.formatMessage(mm.draftHint)}</p> : null}
      <Place meeting={m} />
      <Facts meeting={m} />
      <Guests meeting={m} />
      {m.status !== 'CANCELLED' ? <p className="text-muted-foreground">{intl.formatMessage(mm.agendaLater)}</p> : null}
    </section>
  )
}

/** How and where, and the join link as a large button; hidden from `forum.read` holders outside the audience. */
function Place({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  if (m.relation === 'READER') {
    return (
      <p className="text-muted-foreground">
        {intl.formatMessage(mmv.dot, {
          first: intl.formatMessage(mm[m.mode]),
          second: intl.formatMessage(mm.placeHidden),
        })}
      </p>
    )
  }
  return (
    <div className="flex flex-col gap-3">
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
        <dt className="font-medium">{intl.formatMessage(mm.how)}</dt>
        <dd>{intl.formatMessage(mm[m.mode])}</dd>
        {m.location ? (
          <>
            <dt className="font-medium">{intl.formatMessage(mm.where)}</dt>
            <dd>{m.location}</dd>
          </>
        ) : null}
        {m.joinDetails ? (
          <>
            <dt className="font-medium">{intl.formatMessage(mm.joinDetails)}</dt>
            <dd className="whitespace-pre-line">{m.joinDetails}</dd>
          </>
        ) : null}
      </dl>
      {m.joinUrl && (m.status === 'SCHEDULED' || m.status === 'IN_PROGRESS' || m.status === 'DRAFT') ? (
        <div>
          <Button asChild size="default" className="min-h-11">
            <a href={m.joinUrl} target="_blank" rel="noopener noreferrer">
              <ExternalLink aria-hidden="true" />
              {intl.formatMessage(mm.join)}
              <span className="sr-only"> {intl.formatMessage(mm.joinNewTab)}</span>
            </a>
          </Button>
        </div>
      ) : null}
    </div>
  )
}

/** Kind and classification. */
function Facts({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const levels = useQuery(listClassificationLevelsOptions())
  const level = levels.data?.find((l) => l.id === m.classificationId)?.name
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
      <dt className="font-medium">{intl.formatMessage(mm.kind)}</dt>
      <dd>{intl.formatMessage(mm[m.kind])}</dd>
      {m.title && m.label ? (
        <>
          <dt className="font-medium">{intl.formatMessage(mm.title)}</dt>
          <dd>{m.title}</dd>
        </>
      ) : null}
      {level ? (
        <>
          <dt className="font-medium">{intl.formatMessage(mm.classification)}</dt>
          <dd>{level}</dd>
        </>
      ) : null}
    </dl>
  )
}

/** The guests for the whole meeting; its secretaries add and remove them until it is held. */
function Guests({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const [adding, setAdding] = useState(false)
  const [removing, setRemoving] = useState<MeetingGuestResponse>()
  const changeable = m.canManage && ['DRAFT', 'SCHEDULED', 'IN_PROGRESS'].includes(m.status)
  if (!m.guests.length && !changeable) return null
  return (
    <section aria-labelledby="meeting-guests" className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="meeting-guests" className="text-lg font-semibold">
          {intl.formatMessage(mm.guests)}
        </h2>
        {changeable ? (
          <Button
            variant="outline"
            onClick={() => {
              setAdding(true)
            }}
          >
            <UserPlus aria-hidden="true" />
            {intl.formatMessage(mm.addGuest)}
          </Button>
        ) : null}
      </div>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(mm.guestsHint)}</p>
      {m.guests.length ? (
        <ul className="flex flex-col divide-y rounded-md border">
          {m.guests.map((g) => (
            <li key={g.userId} className="flex min-h-11 flex-wrap items-center justify-between gap-2 px-3 py-2">
              <span className="flex flex-wrap gap-x-2">
                <span className="font-medium">{g.name}</span>
                {g.note ? <span className="text-muted-foreground">{g.note}</span> : null}
              </span>
              {changeable ? (
                <Button
                  variant="ghost"
                  aria-label={intl.formatMessage(mmv.removeGuest, { name: g.name })}
                  onClick={() => {
                    setRemoving(g)
                  }}
                >
                  {intl.formatMessage(mm.remove)}
                </Button>
              ) : null}
            </li>
          ))}
        </ul>
      ) : (
        <p>{intl.formatMessage(mm.noGuests)}</p>
      )}
      {adding ? (
        <AddGuestDialog
          meeting={m}
          onClose={() => {
            setAdding(false)
          }}
        />
      ) : null}
      {removing ? (
        <RemoveGuestDialog
          meeting={m}
          guest={removing}
          onClose={() => {
            setRemoving(undefined)
          }}
        />
      ) : null}
    </section>
  )
}
