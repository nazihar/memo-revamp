import { useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { AlertTriangle, ArrowRight } from 'lucide-react'
import { useIntl, type IntlShape, type MessageDescriptor } from 'react-intl'
import { approvalsDashboardOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { ApprovalsDashboard } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Select } from '@/components/ui/form-controls'
import { AgeChart, ChartLegend, CycleChart, PersonChart, ThroughputChart } from './charts'
import { dm, dmv } from './dashboard-messages'
import { DataTableOf, Figure } from './Figure'
import { PERIODS, type DashboardSearch, type Weeks } from './dashboard-params'
import { formatDay, periodStart, unitOf, useScopeOptions } from './scope'

const BUCKETS: Partial<Record<string, { label: MessageDescriptor; older: boolean }>> = {
  '0-2': { label: dm.bucket0, older: false },
  '3-5': { label: dm.bucket3, older: false },
  '6-10': { label: dm.bucket6, older: true },
  '10+': { label: dm.bucket10, older: true },
}

/**
 * `/dashboards/approvals` (flow K, design E05 §6): pending tasks by age, overdue, pending by person (unit scope), cycle
 * time per memo type and throughput per week, for Me or a unit the viewer may see, over the last 4, 12 or 26 weeks.
 */
export function ApprovalsDashboardPage({ search }: { search: DashboardSearch }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const scope = search.scope ?? 'me'
  const weeks: Weeks = search.weeks ?? 12
  const query = { query: { scope, from: periodStart(weeks) } }
  const dashboard = useQuery({ ...approvalsDashboardOptions(query), retry: false })
  const { options, teamUnits } = useScopeOptions(intl, dashboard.data?.unit)
  const choices = options.some((o) => o.value === scope)
    ? options
    : [...options, { value: scope, label: dashboard.data?.unit?.name ?? intl.formatMessage(dmv.unit, { number: 1 }) }]
  const go = (next: DashboardSearch) =>
    void navigate({
      to: '/dashboards/approvals',
      search: {
        ...(next.scope && next.scope !== 'me' ? { scope: next.scope } : {}),
        ...(next.weeks && next.weeks !== 12 ? { weeks: next.weeks } : {}),
      },
    })
  const forbidden = dashboard.isError && isProblem(dashboard.error) && dashboard.error.status === 403

  return (
    <section aria-labelledby="dashboard-title">
      <PageHeader
        title={<span id="dashboard-title">{intl.formatMessage(dm.title)}</span>}
        description={intl.formatMessage(dm.lead)}
      />
      <div className="mb-4 flex flex-wrap items-end gap-4">
        <div className="flex min-w-56 flex-col gap-1.5">
          <label htmlFor="dashboard-scope" className="text-sm font-medium">
            {intl.formatMessage(dm.scope)}
          </label>
          <Select
            id="dashboard-scope"
            value={scope}
            onChange={(e) => {
              go({ scope: e.target.value, weeks })
            }}
          >
            {choices.map((o) => (
              <option key={o.value} value={o.value}>
                {o.label}
              </option>
            ))}
          </Select>
        </div>
        <div className="flex min-w-44 flex-col gap-1.5">
          <label htmlFor="dashboard-period" className="text-sm font-medium">
            {intl.formatMessage(dm.period)}
          </label>
          <Select
            id="dashboard-period"
            value={String(weeks)}
            onChange={(e) => {
              go({ scope, weeks: PERIODS.find((w) => w === Number(e.target.value)) ?? 12 })
            }}
          >
            {PERIODS.map((w) => (
              <option key={w} value={w}>
                {intl.formatMessage(dmv.weeks, { count: w })}
              </option>
            ))}
          </Select>
        </div>
      </div>
      {dashboard.isPending ? (
        <LoadingState />
      ) : forbidden ? (
        <EmptyState
          title={intl.formatMessage(dm.forbidden)}
          action={
            <Button
              variant="outline"
              onClick={() => {
                go({ weeks })
              }}
            >
              {intl.formatMessage(dm.showMine)}
            </Button>
          }
        />
      ) : dashboard.isError ? (
        <ErrorState onRetry={() => void dashboard.refetch()} />
      ) : (
        <Figures
          data={dashboard.data}
          scopeLabel={choices.find((o) => o.value === scope)?.label ?? ''}
          weeks={weeks}
          teamUnitIds={new Set(teamUnits.map((u) => u.id))}
        />
      )}
    </section>
  )
}

function Figures({
  data,
  scopeLabel,
  weeks,
  teamUnitIds,
}: {
  data: ApprovalsDashboard
  scopeLabel: string
  weeks: Weeks
  teamUnitIds: ReadonlySet<string>
}) {
  const intl = useIntl()
  const unit = unitOf(data.scope) ?? data.unit?.id
  return (
    <>
      <p className="mb-4 text-sm font-medium" aria-live="polite">
        {intl.formatMessage(dmv.showing, {
          scope: data.unit?.name ?? scopeLabel,
          from: formatDay(intl, data.from),
          to: formatDay(intl, data.to),
        })}
      </p>
      <div className="grid gap-4 lg:grid-cols-3">
        <AgeFigure data={data} className="lg:col-span-2" />
        <OverdueFigure count={data.overdue} unit={unit} teamUnitIds={teamUnitIds} />
        {unit ? <PersonFigure data={data} className="lg:col-span-3" /> : null}
        <CycleFigure data={data} className="lg:col-span-3" />
        <ThroughputFigure data={data} weeks={weeks} className="lg:col-span-3" />
      </div>
    </>
  )
}

function AgeFigure({ data, className }: { data: ApprovalsDashboard; className?: string }) {
  const intl = useIntl()
  const rows = data.pendingByAge.map((b) => {
    const known = BUCKETS[b.bucket]
    return {
      key: b.bucket,
      label: known ? intl.formatMessage(known.label) : intl.formatMessage(dmv.bucket, { bucket: b.bucket }),
      count: b.count,
    }
  })
  const total = rows.reduce((sum, r) => sum + r.count, 0)
  const older = data.pendingByAge.filter((b) => BUCKETS[b.bucket]?.older).reduce((sum, b) => sum + b.count, 0)
  const title = intl.formatMessage(dm.ageTitle)
  const summary = total === 0 ? intl.formatMessage(dm.ageNone) : intl.formatMessage(dmv.ageSummary, { total, older })
  return (
    <Figure
      className={className}
      title={title}
      summary={summary}
      table={
        <DataTableOf
          caption={title}
          columns={[intl.formatMessage(dm.colAge), intl.formatMessage(dm.colTasks)]}
          rows={rows.map((r) => ({ key: r.key, cells: [r.label, r.count] }))}
        />
      }
    >
      <AgeChart data={rows} title={summary} seriesName={intl.formatMessage(dm.colTasks)} />
    </Figure>
  )
}

function OverdueFigure({
  count,
  unit,
  teamUnitIds,
}: {
  count: number
  unit?: string
  teamUnitIds: ReadonlySet<string>
}) {
  const intl = useIntl()
  // The list behind the number: the team view for a unit the viewer oversees, else their own tasks by due time.
  const link =
    unit && teamUnitIds.has(unit) ? (
      <Link to="/inbox" search={{ tab: 'team', unit, status: 'overdue' }} className="inline-flex items-center gap-1.5">
        {intl.formatMessage(dm.viewList)}
        <ArrowRight aria-hidden="true" className="size-4 rtl:rotate-180" />
      </Link>
    ) : !unit ? (
      <Link to="/inbox" search={{ tab: 'tasks', sort: 'due' }} className="inline-flex items-center gap-1.5">
        {intl.formatMessage(dm.viewList)}
        <ArrowRight aria-hidden="true" className="size-4 rtl:rotate-180" />
      </Link>
    ) : null
  return (
    <Figure
      className="lg:self-start"
      title={intl.formatMessage(dm.overdueTitle)}
      summary={intl.formatMessage(dmv.overdueSummary, { count })}
    >
      <p className="flex items-center gap-2 text-3xl font-semibold tabular-nums">
        {count > 0 ? <AlertTriangle aria-hidden="true" className="size-6 text-destructive" /> : null}
        {intl.formatMessage(dmv.overdueCount, { count })}
      </p>
      {link ? <p className="text-sm font-medium text-primary underline-offset-4 hover:underline">{link}</p> : null}
    </Figure>
  )
}

function PersonFigure({ data, className }: { data: ApprovalsDashboard; className?: string }) {
  const intl = useIntl()
  const people = [...data.pendingByPerson].sort((a, b) => b.pending - a.pending)
  const title = intl.formatMessage(dm.personTitle)
  const top = people[0]
  const summary = top
    ? intl.formatMessage(dmv.personSummary, { people: people.length, top: top.person.name, count: top.pending })
    : intl.formatMessage(dm.personNone)
  return (
    <Figure
      className={className}
      title={title}
      summary={summary}
      table={
        <DataTableOf
          caption={title}
          columns={[
            intl.formatMessage(dm.colPerson),
            intl.formatMessage(dm.colPending),
            intl.formatMessage(dm.colOverdue),
          ]}
          rows={people.map((p) => ({ key: p.person.id, cells: [p.person.name, p.pending, p.overdue] }))}
        />
      }
    >
      {people.length > 0 ? (
        <PersonChart
          title={summary}
          seriesName={intl.formatMessage(dm.colPending)}
          data={people.map((p) => ({
            name: p.person.name,
            pending: p.pending,
            label: intl.formatMessage(dmv.personLabel, { pending: p.pending, overdue: p.overdue }),
          }))}
        />
      ) : null}
    </Figure>
  )
}

const days = (intl: IntlShape, value: number) => intl.formatNumber(value, { maximumFractionDigits: 1 })

function CycleFigure({ data, className }: { data: ApprovalsDashboard; className?: string }) {
  const intl = useIntl()
  const title = intl.formatMessage(dm.cycleTitle)
  const median = intl.formatMessage(dm.seriesMedian)
  const p90 = intl.formatMessage(dm.seriesP90)
  const slowest = [...data.cycleTimes].sort((a, b) => b.medianDays - a.medianDays)[0]
  const summary = slowest
    ? intl.formatMessage(dmv.cycleSummary, {
        types: data.cycleTimes.length,
        type: slowest.type.name,
        days: days(intl, slowest.medianDays),
      })
    : intl.formatMessage(dm.cycleNone)
  return (
    <Figure
      className={className}
      title={title}
      summary={summary}
      table={
        <DataTableOf
          caption={title}
          columns={[
            intl.formatMessage(dm.colType),
            intl.formatMessage(dm.colDecided),
            intl.formatMessage(dm.colMedian),
            intl.formatMessage(dm.colP90),
          ]}
          rows={data.cycleTimes.map((c) => ({
            key: c.type.id,
            cells: [c.type.name, c.decided, days(intl, c.medianDays), days(intl, c.p90Days)],
          }))}
        />
      }
    >
      {data.cycleTimes.length > 0 ? (
        <>
          <ChartLegend
            items={[
              { label: median, series: 1, marker: 'square', line: false },
              { label: p90, series: 2, marker: 'square', line: false },
            ]}
          />
          <CycleChart
            title={summary}
            medianName={median}
            p90Name={p90}
            data={data.cycleTimes.map((c) => ({
              name: c.type.name,
              median: c.medianDays,
              p90: c.p90Days,
              medianLabel: intl.formatMessage(dmv.seriesValue, { series: median, value: days(intl, c.medianDays) }),
              p90Label: intl.formatMessage(dmv.seriesValue, { series: p90, value: days(intl, c.p90Days) }),
            }))}
          />
        </>
      ) : null}
    </Figure>
  )
}

function ThroughputFigure({ data, weeks, className }: { data: ApprovalsDashboard; weeks: Weeks; className?: string }) {
  const intl = useIntl()
  const title = intl.formatMessage(dm.throughputTitle)
  const createdName = intl.formatMessage(dm.seriesCreated)
  const completedName = intl.formatMessage(dm.seriesCompleted)
  const rows = data.throughput.map((w) => ({
    key: w.week,
    label: formatDay(intl, w.week, { day: 'numeric', month: 'short' }),
    created: w.created,
    completed: w.completed,
  }))
  const summary = intl.formatMessage(dmv.throughputSummary, {
    created: rows.reduce((sum, r) => sum + r.created, 0),
    completed: rows.reduce((sum, r) => sum + r.completed, 0),
    weeks: data.throughput.length || weeks,
  })
  return (
    <Figure
      className={className}
      title={title}
      summary={summary}
      table={
        <DataTableOf
          caption={title}
          columns={[intl.formatMessage(dm.colWeek), createdName, completedName]}
          rows={rows.map((r) => ({
            key: r.key,
            cells: [formatDay(intl, r.key), r.created, r.completed],
          }))}
        />
      }
    >
      <ChartLegend
        items={[
          { label: createdName, series: 1, marker: 'circle', line: true },
          { label: completedName, series: 2, marker: 'square', line: true },
        ]}
      />
      <ThroughputChart data={rows} title={summary} createdName={createdName} completedName={completedName} />
    </Figure>
  )
}
