import { Link, useLocation, useNavigate } from '@tanstack/react-router'
import { useEffect, useState } from 'react'
import { useIntl } from 'react-intl'
import type { TimingSummary } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { DataTableOf, Figure } from '@/features/dashboard/Figure'
import { useTerms } from '@/features/terms/terms'
import { periodOf, type PeriodSearch } from './analytics-params'
import { BarList } from './bars'
import { am, amt, amv } from './messages'
import { PeriodPicker } from './PeriodPicker'
import { formatDay, formatPercent } from './period'
import { Arrow, Loaded, type Detail } from './shared'
import { LINK, quarterWords, rateWords, useCommitteeDetail } from './words'

/**
 * `/analytics/committees/$forumId` (Phase 4 E08 T7, flow AW): a committee's measures in detail for the period — time
 * from submission to decision by quarter and memo type (calendar days, working days on request), items deferred by
 * quarter, overdue actions by owner with their ageing, and papers on time per held meeting (late counts for officers
 * and oversight only). Each section is a table-first figure with its sentence, its basis and its link.
 */
export function DetailPage({ forumId, search }: { forumId: string; search: PeriodSearch }) {
  const intl = useIntl()
  const terms = useTerms()
  const navigate = useNavigate()
  const detail = useCommitteeDetail(forumId, search)
  const period = periodOf(search)
  return (
    <section aria-labelledby="analytics-detail-title" className="flex flex-col gap-4">
      <PageHeader
        title={
          <span id="analytics-detail-title">{detail.data?.name ?? intl.formatMessage(amt.linkDetail, terms)}</span>
        }
        description={intl.formatMessage(am.overviewLead)}
        actions={
          <Link to="/analytics/committees" search={period} className={LINK}>
            {intl.formatMessage(amt.toOverview, terms)}
            <Arrow />
          </Link>
        }
      />
      <PeriodPicker
        search={search}
        onChange={(next) => {
          void navigate({ to: '/analytics/committees/$forumId', params: { forumId }, search: next, replace: true })
        }}
      />
      <Loaded query={detail}>{(data) => <Sections data={data} period={period} />}</Loaded>
    </section>
  )
}

function Sections({ data, period }: { data: Detail; period: PeriodSearch }) {
  const intl = useIntl()
  const terms = useTerms()
  const a = data.attendance.summary
  // A figure of the overview opens its section here (`#actions`): the figures exist only once the data has come, so the
  // browser could not go there by itself. Focus goes with it, so a screen reader reads on from the section.
  const hash = useLocation({ select: (l) => l.hash })
  useEffect(() => {
    const target = hash ? document.getElementById(hash) : null
    target?.scrollIntoView({ block: 'start' })
    target?.focus({ preventScroll: true })
  }, [hash])
  return (
    <>
      <p className="text-sm font-medium" aria-live="polite">
        {intl.formatMessage(amv.showing, { from: formatDay(intl, data.from), to: formatDay(intl, data.to) })}
      </p>
      <p className="flex flex-wrap items-center gap-x-4 gap-y-2 text-sm">
        <span>
          {a.ratePercent == null
            ? intl.formatMessage(am.noMeetings)
            : intl.formatMessage(amv.attendanceLead, {
                meetings: a.heldMeetings,
                rate: formatPercent(intl, a.ratePercent),
              })}
        </span>
        <Link
          to="/analytics/committees/$forumId/attendance"
          params={{ forumId: data.forumId }}
          search={period}
          className={`${LINK} font-medium`}
        >
          {intl.formatMessage(am.linkAttendance)}
          <Arrow />
        </Link>
      </p>
      <TimingFigure data={data} />
      <DeferralsFigure data={data} />
      <ActionsFigure data={data} />
      <PapersFigure data={data} terms={terms} />
    </>
  )
}

function timingValue(t: TimingSummary, working: boolean, which: 'median' | 'p90') {
  const days = working
    ? which === 'median'
      ? t.medianWorkingDays
      : t.p90WorkingDays
    : which === 'median'
      ? t.medianDays
      : t.p90Days
  return t.enough && days != null ? days : null
}

function TimingFigure({ data }: { data: Detail }) {
  const intl = useIntl()
  const [working, setWorking] = useState(false)
  const t = data.timeToDecision
  const title = intl.formatMessage(am.timingTitle)
  const median = timingValue(t.overall, working, 'median')
  const p90 = timingValue(t.overall, working, 'p90')
  const summary =
    t.overall.decisions === 0
      ? intl.formatMessage(am.timingNone)
      : median != null && p90 != null
        ? intl.formatMessage(amv.timingSummary, { median, p90 })
        : intl.formatMessage(am.timingTooFew)
  const days = (value: number | null) =>
    value == null ? intl.formatMessage(am.tooFew) : intl.formatMessage(amv.days, { days: value })
  const quarters = t.byQuarter.map((q) => ({
    key: q.quarter,
    name: quarterWords(intl, q.quarter),
    decisions: q.timing.decisions,
    median: timingValue(q.timing, working, 'median'),
    p90: timingValue(q.timing, working, 'p90'),
  }))
  const types = t.byMemoType.map((m) => {
    const type = m.memoTypeName ?? intl.formatMessage(am.noMemoType)
    const value = timingValue(m.timing, working, 'median')
    return value == null
      ? intl.formatMessage(amv.memoTypeFew, { type, decisions: m.timing.decisions })
      : intl.formatMessage(amv.memoTypeTiming, { type, days: value, decisions: m.timing.decisions })
  })
  return (
    <Figure
      id="timing"
      tableFirst
      title={title}
      summary={summary}
      basis={intl.formatMessage(amv.basis, { count: t.overall.decisions })}
      link={
        t.overall.decisions > 0 ? (
          <Link to="/decisions" search={{ committee: data.forumId, from: data.from, to: data.to }} className={LINK}>
            {intl.formatMessage(am.linkDecisions)}
            <Arrow />
          </Link>
        ) : undefined
      }
      controls={
        t.overall.decisions > 0 ? (
          <div className="flex flex-wrap items-center gap-3">
            <Button
              variant={working ? 'default' : 'outline'}
              className="min-h-11"
              aria-pressed={working}
              aria-describedby="timing-working-hint"
              onClick={() => {
                setWorking((w) => !w)
              }}
            >
              {intl.formatMessage(am.workingDays)}
            </Button>
            <span id="timing-working-hint" className="text-xs text-muted-foreground">
              {intl.formatMessage(am.workingDaysHint)}
            </span>
          </div>
        ) : null
      }
      table={
        quarters.length > 0 ? (
          <DataTableOf
            caption={title}
            columns={[
              intl.formatMessage(am.colQuarter),
              intl.formatMessage(am.colDecisions),
              intl.formatMessage(am.colMedian),
              intl.formatMessage(am.colP90),
            ]}
            rows={quarters.map((q) => ({ key: q.key, cells: [q.name, q.decisions, days(q.median), days(q.p90)] }))}
          />
        ) : undefined
      }
      notes={
        types.length > 0 ? (
          <p className="text-sm">
            {intl.formatMessage(amv.byMemoType, { list: intl.formatList(types, { type: 'unit', style: 'short' }) })}
          </p>
        ) : undefined
      }
    >
      {quarters.length > 0 ? (
        <BarList rows={quarters.map((q) => ({ key: q.key, name: q.name, value: q.median, label: days(q.median) }))} />
      ) : null}
    </Figure>
  )
}

function DeferralsFigure({ data }: { data: Detail }) {
  const intl = useIntl()
  const d = data.deferrals
  const title = intl.formatMessage(am.deferralsTitle)
  const summary =
    d.overall.decisions === 0
      ? intl.formatMessage(am.timingNone)
      : intl.formatMessage(amv.deferralsSummary, {
          deferred: d.overall.deferred,
          decisions: d.overall.decisions,
          percent: rateWords(intl, d.overall.deferredPercent),
          twice: d.overall.deferredTwice,
        })
  return (
    <Figure
      id="deferrals"
      tableFirst
      title={title}
      summary={summary}
      basis={intl.formatMessage(amv.basis, { count: d.overall.decisions })}
      link={
        d.overall.deferred > 0 ? (
          <Link
            to="/decisions"
            search={{ committee: data.forumId, from: data.from, to: data.to, outcome: 'DEFERRED' }}
            className={LINK}
          >
            {intl.formatMessage(am.linkDeferred)}
            <Arrow />
          </Link>
        ) : undefined
      }
      table={
        d.byQuarter.length > 0 ? (
          <DataTableOf
            caption={title}
            columns={[
              intl.formatMessage(am.colQuarter),
              intl.formatMessage(am.colDecisions),
              intl.formatMessage(am.colDeferred),
              intl.formatMessage(am.colShare),
            ]}
            rows={d.byQuarter.map((q) => ({
              key: q.quarter,
              cells: [
                quarterWords(intl, q.quarter),
                q.deferrals.decisions,
                q.deferrals.deferred,
                rateWords(intl, q.deferrals.deferredPercent),
              ],
            }))}
          />
        ) : undefined
      }
    >
      {d.byQuarter.length > 0 ? (
        <BarList
          max={100}
          rows={d.byQuarter.map((q) => ({
            key: q.quarter,
            name: quarterWords(intl, q.quarter),
            value: q.deferrals.deferredPercent ?? null,
            label: rateWords(intl, q.deferrals.deferredPercent),
          }))}
        />
      ) : null}
    </Figure>
  )
}

function ActionsFigure({ data }: { data: Detail }) {
  const intl = useIntl()
  const a = data.actions.summary
  const owners = data.actions.owners
  const title = intl.formatMessage(am.actionsTitle)
  const ownerName = (o: (typeof owners)[number]) => {
    const owner = o.ownerName ?? intl.formatMessage(am.unknownOwner)
    return o.ownerPositionId && o.holderName
      ? intl.formatMessage(amv.ownerHolder, { owner, holder: o.holderName })
      : owner
  }
  return (
    <Figure
      id="actions"
      tableFirst
      title={title}
      summary={
        a.overdue === 0
          ? intl.formatMessage(am.actionsNone)
          : intl.formatMessage(amv.actionsSummary, { count: a.overdue, over90: a.overdueOver90 })
      }
      basis={intl.formatMessage(amv.closedSummary, {
        closed: a.closed,
        original: a.closedByOriginalDate,
        agreed: a.closedByAgreedDate,
      })}
      table={
        owners.length > 0 ? (
          <DataTableOf
            caption={title}
            columns={[
              intl.formatMessage(am.colOwner),
              intl.formatMessage(am.colOverdue),
              intl.formatMessage(am.col1To30),
              intl.formatMessage(am.col31To90),
              intl.formatMessage(am.colOver90),
              intl.formatMessage(am.colRevised),
            ]}
            rows={owners.map((o, i) => ({
              key: `${o.ownerPositionId ?? o.ownerUserId ?? 'none'}-${String(i)}`,
              cells: [ownerName(o), o.overdue, o.days1To30, o.days31To90, o.over90, o.revised],
            }))}
          />
        ) : undefined
      }
    >
      {owners.length > 0 ? (
        <BarList
          rows={owners.map((o, i) => ({
            key: `${o.ownerPositionId ?? o.ownerUserId ?? 'none'}-${String(i)}`,
            name: ownerName(o),
            value: o.overdue,
            label: intl.formatNumber(o.overdue),
          }))}
        />
      ) : null}
    </Figure>
  )
}

function PapersFigure({ data, terms }: { data: Detail; terms: Record<string, string> }) {
  const intl = useIntl()
  const p = data.papers.summary
  const full = data.access === 'FULL'
  const title = intl.formatMessage(am.papersTitle)
  const meetings = data.papers.meetings
  const status = (onTime: boolean | null | undefined) =>
    onTime == null ? (
      <Badge tone="neutral">{intl.formatMessage(am.noDeadlineWord)}</Badge>
    ) : onTime ? (
      <Badge tone="ok">{intl.formatMessage(am.onTime)}</Badge>
    ) : (
      <Badge tone="danger">{intl.formatMessage(am.late)}</Badge>
    )
  const columns = [
    intl.formatMessage(am.colMeeting),
    intl.formatMessage(am.colDate),
    intl.formatMessage(am.colPapers),
    ...(full ? [intl.formatMessage(am.colLateItems), intl.formatMessage(am.colLatePapers)] : []),
    intl.formatMessage(am.colAttendedOf),
  ]
  return (
    <Figure
      id="papers"
      tableFirst
      title={title}
      summary={
        meetings.length === 0
          ? intl.formatMessage(am.papersNone)
          : p.withDeadline === 0
            ? intl.formatMessage(am.noDeadline)
            : intl.formatMessage(amv.papersSummary, { onTime: p.onTime, meetings: p.withDeadline })
      }
      basis={
        full && p.latePapers != null && p.lateItems != null
          ? intl.formatMessage(amv.papersLate, { papers: p.latePapers, items: p.lateItems })
          : undefined
      }
      table={
        meetings.length > 0 ? (
          <DataTableOf
            caption={title}
            columns={columns}
            rows={meetings.map((m) => {
              const date = formatDay(intl, m.date)
              return {
                key: m.meetingId,
                cells: [
                  <Link key="m" to="/meetings/$meetingId" params={{ meetingId: m.meetingId }} className={LINK}>
                    {m.label ?? intl.formatMessage(amv.meetingOf, { date })}
                  </Link>,
                  date,
                  status(m.onTime),
                  ...(full ? [m.lateItems ?? 0, m.latePapers ?? 0] : []),
                  intl.formatMessage(amv.attendedOf, { attended: m.attended, expected: m.expected }),
                ],
              }
            })}
          />
        ) : undefined
      }
      link={
        <Link
          to="/committees/$forumId"
          params={{ forumId: data.forumId }}
          search={{ tab: 'meetings' }}
          className={LINK}
        >
          {intl.formatMessage(amt.linkMeetings, terms)}
          <Arrow />
        </Link>
      }
    />
  )
}
