import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { ExternalLink, UserPlus } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingOptions, listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingGuestResponse, MeetingResponse } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { AgendaLink } from './agenda/AgendaLink'
import { AtTheMeeting } from './attendance/AtTheMeeting'
import { AttendancePanel } from './attendance/AttendancePanel'
import { am } from './attendance/messages'
import { QuorumBanner } from './attendance/QuorumBanner'
import { om } from './declarations/officer-messages'
import { useChairSeat } from './declarations/chair-seat'
import { LateRequests } from './declarations/LateRequests'
import { OfficerDeclarations } from './declarations/OfficerDeclarations'
import { AddGuestDialog, MeetingMenu, RemoveGuestDialog } from './MeetingDialogs'
import { mm, mmv } from './messages'
import { MeetingAgenda } from './page/MeetingAgenda'
import { pmv } from './page/messages'
import { MEETING_TABS, withTab, type MeetingTab } from './page/search'
import { InvitationsPanel } from './replies/InvitationsPanel'
import { rm } from './replies/messages'
import { CalendarLink, WillYouAttend } from './replies/WillYouAttend'
import { fullName } from './shared'
import { StatusBadge } from './StatusBadge'
import { cityOf, viewerZone, whenIn, yourTimes } from './time'

/**
 * `/meetings/$meetingId` (flow P, Phase 4 E03 T8): the meeting page for everyone who may see it (the link notifications
 * open). One column in large type: the date and times in the meeting's zone and the reader's own when it differs, how
 * and where with a large "Join the meeting", then the agenda as the reader may see it; its secretaries' ⋯ menu and
 * the guests for officers. Printing gives a plain agenda without the application's frame. Under the place, "Will you
 * attend?" with "Add to my calendar" for whoever answers the meeting (E05 T7, flow V), and for the officers of a meeting
 * that is scheduled or later, the agenda and "Invitations and replies" (flow W) as two tabs; and for the officers and the
 * committee's chair or vice-chair, "Declarations" (E04 T7, flow T). From the start of the meeting (E05 T8): for the
 * officers the live quorum above the tabs and the "Attendance" register (flows X and Y), the tab they land on while it
 * runs; for the audience, "At the meeting" with how they are recorded and, for members, whether it is quorate.
 */
export function MeetingPage({ meetingId }: { meetingId: string }) {
  const intl = useIntl()
  const meeting = useQuery(getMeetingOptions({ path: { meetingId } }))
  const chairSeat = useChairSeat(meeting.data)
  if (meeting.isPending) return <LoadingState />
  if (meeting.isError) return <ErrorState onRetry={() => void meeting.refetch()} />
  const m = meeting.data
  const { times } = whenIn(intl, m, m.timeZone)
  const date = intl.formatDate(m.startsAt, {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: m.timeZone,
  })
  const zone = viewerZone()
  const mine = yourTimes(intl, m, zone)
  const officer = m.relation === 'OFFICER'
  // The tabs (E05 T7, E04 T7): Invitations and replies for the officers once the meeting is scheduled; Declarations
  // for the officers and the committee's chair or vice-chair, who rules on declarations even as a member.
  const replies = officer && m.status !== 'DRAFT'
  const declarations = (officer || chairSeat) && m.status !== 'CANCELLED'
  // Late papers the viewer decides (E04 §1.7 as built): the chair, or the vice-chair on the items the chair has declared
  // an interest in. Read only by the officers and the committee's chair or vice-chair; the server lists what they decide.
  const decides = (officer || chairSeat) && ['DRAFT', 'SCHEDULED', 'IN_PROGRESS'].includes(m.status)
  // The register (E05 T8, flow X) from the start of the meeting, for the officers; the tab they land on while it runs.
  const attendance = officer && ATTENDANCE_STATUSES.includes(m.status)
  const live = m.status === 'IN_PROGRESS'
  // The audience during the meeting sees how they are recorded and, as members, whether it is quorate (flow Y1).
  const atTheMeeting = live && !officer && m.relation !== 'READER'
  return (
    <section aria-labelledby="meeting-title" className="meeting-print flex max-w-3xl flex-col gap-5">
      <PageHeader
        className="mb-0"
        title={<span id="meeting-title">{fullName(intl, m)}</span>}
        status={<StatusBadge meeting={m} officer={officer} />}
        actions={
          m.canManage ? (
            <span className="print:hidden">
              <MeetingMenu meetingId={m.id} name={fullName(intl, m)} status={m.status} meeting={m} />
            </span>
          ) : null
        }
      />
      <Link
        to="/committees/$forumId"
        params={{ forumId: m.forumId }}
        search={{ tab: 'meetings' }}
        className="inline-flex min-h-11 w-fit items-center underline-offset-4 hover:underline print:hidden"
      >
        {intl.formatMessage(mmv.backTo, { name: m.forumName })}
      </Link>
      <div className="flex flex-col gap-1 text-lg">
        <p className="text-xl font-semibold">{date}</p>
        <p className="text-xl">{intl.formatMessage(mmv.zoneTimes, { times, city: cityOf(m.timeZone) })}</p>
        {mine ? (
          <p className="text-muted-foreground">
            {intl.formatMessage(pmv.yourTimes, { times: mine, city: cityOf(zone) })}
          </p>
        ) : null}
      </div>
      {m.status === 'CANCELLED' && m.statusReason ? (
        <p role="status" className="rounded-md border border-destructive/40 bg-destructive/5 p-3">
          {intl.formatMessage(mmv.cancelledBecause, { reason: m.statusReason })}
        </p>
      ) : null}
      {m.status === 'DRAFT' ? <p className="text-muted-foreground">{intl.formatMessage(mm.draftHint)}</p> : null}
      <Place meeting={m} />
      {atTheMeeting ? (
        <AtTheMeeting meeting={m} />
      ) : m.rsvp ? (
        <WillYouAttend meeting={m} />
      ) : (
        <CalendarLink meeting={m} />
      )}
      {officer && live ? <QuorumBanner meeting={m} /> : null}
      {decides ? <LateRequests meeting={m} /> : null}
      {replies || declarations ? (
        <MeetingTabs
          live={live && attendance}
          tabs={[
            'agenda',
            ...(attendance ? (['attendance'] as const) : []),
            ...(replies ? (['replies'] as const) : []),
            ...(declarations ? (['declarations'] as const) : []),
          ]}
        >
          <TabsList aria-label={intl.formatMessage(rm.tabs)} className="flex-wrap print:hidden">
            <TabsTrigger value="agenda" className="min-h-11">
              {intl.formatMessage(rm.tabAgenda)}
            </TabsTrigger>
            {attendance ? (
              <TabsTrigger value="attendance" className="min-h-11">
                {intl.formatMessage(am.tab)}
              </TabsTrigger>
            ) : null}
            {replies ? (
              <TabsTrigger value="replies" className="min-h-11">
                {intl.formatMessage(rm.tabReplies)}
              </TabsTrigger>
            ) : null}
            {declarations ? (
              <TabsTrigger value="declarations" className="min-h-11">
                {intl.formatMessage(om.tab)}
              </TabsTrigger>
            ) : null}
          </TabsList>
          <TabsContent value="agenda" className="pt-4">
            <MeetingAgenda meetingId={m.id} relation={m.relation} />
          </TabsContent>
          {attendance ? (
            <TabsContent value="attendance" className="pt-4 print:hidden">
              <AttendancePanel meeting={m} />
            </TabsContent>
          ) : null}
          {replies ? (
            <TabsContent value="replies" className="pt-4 print:hidden">
              <InvitationsPanel meeting={m} />
            </TabsContent>
          ) : null}
          {declarations ? (
            <TabsContent value="declarations" className="pt-4 print:hidden">
              <OfficerDeclarations meeting={m} />
            </TabsContent>
          ) : null}
        </MeetingTabs>
      ) : (
        <MeetingAgenda meetingId={m.id} relation={m.relation} />
      )}
      <Facts meeting={m} />
      {officer || m.canManage ? <Guests meeting={m} /> : null}
      {officer ? <AgendaLink meetingId={m.id} /> : null}
    </section>
  )
}

/** The statuses with a register to show: from the start until the meeting is closed (read-only once frozen). */
const ATTENDANCE_STATUSES: MeetingResponse['status'][] = ['IN_PROGRESS', 'HELD', 'MINUTES_APPROVED', 'CLOSED']

/**
 * The meeting page's tabs ([tabs], those the viewer has): the agenda first, or the register while the meeting runs
 * ([live]). Another tab is in the address (`?tab=`), so a reload, Back and a change of language keep it; a tab the
 * viewer does not have opens the one the page opens on. When the secretary starts the meeting from this page, the
 * register opens.
 */
function MeetingTabs({ live, tabs, children }: { live: boolean; tabs: MeetingTab[]; children: ReactNode }) {
  const search = useSearch({ from: '/_shell/_signedIn/meetings/$meetingId' })
  const navigate = useNavigate({ from: '/meetings/$meetingId' })
  const opening: MeetingTab = live ? 'attendance' : 'agenda'
  const tab = search.tab && tabs.includes(search.tab) ? search.tab : opening
  const asked = search.tab
  const wasLive = useRef(live)
  useEffect(() => {
    if (wasLive.current === live) return
    wasLive.current = live
    if (live || asked === 'attendance') void navigate({ search: (prev) => withTab(prev, undefined), replace: true })
  }, [live, asked, navigate])
  return (
    <Tabs
      value={tab}
      onValueChange={(value) => {
        const next = MEETING_TABS.find((t) => t === value)
        if (next) void navigate({ search: (prev) => withTab(prev, next === opening ? undefined : next), replace: true })
      }}
      className="print:contents"
    >
      {children}
    </Tabs>
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
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-lg">
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
        <div className="print:hidden">
          <Button asChild size="default" className="min-h-12 text-lg">
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
    <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 print:hidden">
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
    <section aria-labelledby="meeting-guests" className="flex flex-col gap-2 print:hidden">
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
