import { useInfiniteQuery } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { CalendarPlus } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import { listForumMeetingsInfiniteOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ForumPolicyResponse, ForumResponse, MeetingSummaryResponse } from '@/api/generated/types.gen'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { MeetingMenu } from './MeetingDialogs'
import { mm, mmv } from './messages'
import { ScheduleSheet } from './ScheduleSheet'
import { DONE, meetingName, OPEN, type MeetingStatus } from './shared'
import { StatusBadge } from './StatusBadge'
import { cityOf, whenIn, yourTimes } from './time'

type List = 'upcoming' | 'past' | 'cancelled'

/** A year before [day] (`YYYY-MM-DD`), [years] times. */
function yearsBefore(years: number): string {
  const d = new Date()
  d.setFullYear(d.getFullYear() - years)
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/**
 * The committee page's Meetings tab (flow N1): upcoming, past and cancelled meetings with their date and times in the
 * meeting's zone (and the viewer's when it differs), how they are held and their state. Drafts reach only the officers
 * (the server leaves them out for everyone else). "Schedule a meeting" is for the secretaries and administrators.
 */
export function MeetingsTab({ forum, policy }: { forum: ForumResponse; policy?: ForumPolicyResponse }) {
  const intl = useIntl()
  const [list, setList] = useState<List>('upcoming')
  const [scheduling, setScheduling] = useState(false)
  const officer = forum.canManageMembers
  return (
    <div className="flex flex-col gap-4">
      <Tabs
        value={list}
        onValueChange={(v) => {
          setList(v as List)
        }}
      >
        <div className="flex flex-wrap items-center justify-between gap-3">
          <TabsList aria-label={intl.formatMessage(mm.lists)}>
            <TabsTrigger value="upcoming">{intl.formatMessage(mm.upcoming)}</TabsTrigger>
            <TabsTrigger value="past">{intl.formatMessage(mm.past)}</TabsTrigger>
            <TabsTrigger value="cancelled">{intl.formatMessage(mm.cancelledList)}</TabsTrigger>
          </TabsList>
          {officer && forum.status === 'ACTIVE' ? (
            <Button
              onClick={() => {
                setScheduling(true)
              }}
            >
              <CalendarPlus aria-hidden="true" />
              {intl.formatMessage(mm.schedule)}
            </Button>
          ) : null}
        </div>
        <TabsContent value="upcoming" className="pt-4">
          <MeetingList forum={forum} statuses={OPEN} empty={intl.formatMessage(mm.emptyUpcoming)} />
        </TabsContent>
        <TabsContent value="past" className="pt-4">
          <PastList forum={forum} />
        </TabsContent>
        <TabsContent value="cancelled" className="pt-4">
          <MeetingList forum={forum} statuses={['CANCELLED']} empty={intl.formatMessage(mm.emptyCancelled)} />
        </TabsContent>
      </Tabs>
      {scheduling ? (
        <ScheduleSheet
          forum={forum}
          policy={policy}
          onClose={() => {
            setScheduling(false)
          }}
        />
      ) : null}
    </div>
  )
}

/** Held meetings, the latest first, a year at a time ("Show earlier meetings" reaches a year further back). */
function PastList({ forum }: { forum: ForumResponse }) {
  const intl = useIntl()
  const [years, setYears] = useState(1)
  return (
    <MeetingList
      forum={forum}
      statuses={DONE}
      from={yearsBefore(years)}
      latestFirst
      empty={intl.formatMessage(mm.emptyPast)}
      earlier={() => {
        setYears(years + 1)
      }}
    />
  )
}

function MeetingList({
  forum,
  statuses,
  from,
  latestFirst = false,
  empty,
  earlier,
}: {
  forum: ForumResponse
  statuses: MeetingStatus[]
  from?: string
  latestFirst?: boolean
  empty: string
  earlier?: () => void
}) {
  const intl = useIntl()
  const page = { path: { forumId: forum.id }, query: { status: statuses, ...(from ? { from } : {}) } }
  const meetings = useInfiniteQuery({
    ...listForumMeetingsInfiniteOptions(page),
    initialPageParam: page,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
  })
  // The latest first needs the whole window: a year of one committee's meetings is a page or two.
  const { hasNextPage, isFetchingNextPage, fetchNextPage } = meetings
  useEffect(() => {
    if (latestFirst && hasNextPage && !isFetchingNextPage) void fetchNextPage()
  }, [latestFirst, hasNextPage, isFetchingNextPage, fetchNextPage])

  if (meetings.isPending) return <LoadingState rows={3} />
  if (meetings.isError) return <ErrorState onRetry={() => void meetings.refetch()} />
  const rows = meetings.data.pages.flatMap((p) => p.items)
  if (latestFirst) rows.reverse()
  const more = !latestFirst && meetings.hasNextPage
  return (
    <div className="flex flex-col gap-3">
      {rows.length === 0 ? (
        <EmptyState title={empty} />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[40rem] border-collapse text-sm">
            <caption className="sr-only">{intl.formatMessage(mmv.caption, { name: forum.name })}</caption>
            <thead>
              <tr className="border-b text-start text-muted-foreground">
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(mm.colDate)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(mm.colMeeting)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(mm.colMode)}
                </th>
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(mm.colStatus)}
                </th>
                {forum.canManageMembers ? (
                  <th scope="col" className="py-2 text-end font-medium">
                    <span className="sr-only">{intl.formatMessage(mm.colActions)}</span>
                  </th>
                ) : null}
              </tr>
            </thead>
            <tbody>
              {rows.map((m) => (
                <MeetingRow key={m.id} meeting={m} officer={forum.canManageMembers} />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {more || (earlier && !meetings.hasNextPage) ? (
        <div>
          <Button
            variant="outline"
            disabled={meetings.isFetchingNextPage}
            onClick={() => {
              if (more) void meetings.fetchNextPage()
              else earlier?.()
            }}
          >
            {intl.formatMessage(more ? mm.showMore : mm.showEarlier)}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function MeetingRow({ meeting: m, officer }: { meeting: MeetingSummaryResponse; officer: boolean }) {
  const intl = useIntl()
  const { date, times } = whenIn(intl, m, m.timeZone)
  const mine = yourTimes(intl, m)
  const name = meetingName(m)
  return (
    <tr className="border-b align-top">
      <td className="py-3 pe-3">
        <span className="block font-medium">{date}</span>
        <span className="block">{intl.formatMessage(mmv.zoneTimes, { times, city: cityOf(m.timeZone) })}</span>
        {mine ? (
          <span className="block text-muted-foreground">{intl.formatMessage(mmv.yourTimes, { times: mine })}</span>
        ) : null}
      </td>
      <td className="py-3 pe-3">
        <Link
          to="/meetings/$meetingId"
          params={{ meetingId: m.id }}
          className="inline-flex min-h-11 items-center font-medium underline-offset-4 hover:underline"
        >
          {name}
        </Link>
        {m.label && m.title ? <span className="block">{m.title}</span> : null}
        {m.kind === 'SPECIAL' ? (
          <span className="block text-muted-foreground">{intl.formatMessage(mm.special)}</span>
        ) : null}
      </td>
      <td className="py-3 pe-3">{intl.formatMessage(mm[m.mode])}</td>
      <td className="py-3 pe-3">
        <StatusBadge meeting={m} officer={officer} />
      </td>
      {officer ? (
        <td className="py-3 text-end">
          <MeetingMenu meetingId={m.id} name={name} status={m.status} />
        </td>
      ) : null}
    </tr>
  )
}
