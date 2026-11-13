import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, X } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import {
  getWorkflowCalendarOptions,
  getWorkflowCalendarQueryKey,
  listWorkflowCalendarDaysOptions,
  listWorkflowCalendarDaysQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  previewWorkflowDue,
  removeWorkflowCalendarDay,
  replaceWorkflowCalendar,
  setWorkflowCalendarDays,
} from '@/api/generated/sdk.gen'
import type { CalendarResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'

const m = defineMessages({
  title: { id: 'workflows.calendar.title', defaultMessage: 'Business calendar' },
  lead: {
    id: 'workflows.calendar.lead',
    defaultMessage:
      'Deadlines in working time count only these hours. Changes apply to tasks that start afterwards; tasks already waiting keep their due times.',
  },
  week: { id: 'workflows.calendar.week', defaultMessage: 'Working hours' },
  defaulted: {
    id: 'workflows.calendar.defaulted',
    defaultMessage: 'Not set yet: Sunday to Thursday, 9:00 to 17:00. Save to keep or change it.',
  },
  notWorked: { id: 'workflows.calendar.notWorked', defaultMessage: 'Not worked' },
  addPeriod: { id: 'workflows.calendar.addPeriod', defaultMessage: 'Add hours' },
  from: { id: 'workflows.calendar.from', defaultMessage: 'From' },
  until: { id: 'workflows.calendar.until', defaultMessage: 'Until' },
  saveWeek: { id: 'workflows.calendar.saveWeek', defaultMessage: 'Save working hours' },
  days: { id: 'workflows.calendar.days', defaultMessage: 'Holidays and special days' },
  noDays: { id: 'workflows.calendar.noDays', defaultMessage: 'No special days this month.' },
  closed: { id: 'workflows.calendar.closed', defaultMessage: 'Closed' },
  previousMonth: { id: 'workflows.calendar.previousMonth', defaultMessage: 'Previous month' },
  nextMonth: { id: 'workflows.calendar.nextMonth', defaultMessage: 'Next month' },
  addDays: { id: 'workflows.calendar.addDays', defaultMessage: 'Add a day or a range' },
  firstDay: { id: 'workflows.calendar.firstDay', defaultMessage: 'First day' },
  lastDay: { id: 'workflows.calendar.lastDay', defaultMessage: 'Last day (for a range)' },
  dayKind: { id: 'workflows.calendar.dayKind', defaultMessage: 'The day is' },
  kindClosed: { id: 'workflows.calendar.kind.closed', defaultMessage: 'Closed (a holiday)' },
  kindHours: { id: 'workflows.calendar.kind.hours', defaultMessage: 'Worked, with these hours' },
  note: { id: 'workflows.calendar.note', defaultMessage: 'Note' },
  add: { id: 'workflows.calendar.add', defaultMessage: 'Add' },
  preview: { id: 'workflows.calendar.preview', defaultMessage: 'When is it due?' },
  previewLead: {
    id: 'workflows.calendar.preview.lead',
    defaultMessage: 'A deadline such as P2D (two working days) or PT4H (four working hours), counted from now.',
  },
  duration: { id: 'workflows.calendar.duration', defaultMessage: 'Deadline' },
  workingTime: { id: 'workflows.calendar.workingTime', defaultMessage: 'Count working time only' },
  calculate: { id: 'workflows.calendar.calculate', defaultMessage: 'Calculate' },
  noWorkingTime: { id: 'workflows.calendar.noWorkingTime', defaultMessage: 'No working time ahead.' },
  saved: { id: 'workflows.calendar.saved', defaultMessage: 'Saved.' },
  failed: { id: 'workflows.calendar.failed', defaultMessage: 'That did not work. Try again.' },
  conflict: {
    id: 'workflows.calendar.conflict',
    defaultMessage: 'Someone changed the hours meanwhile. They have been reloaded.',
  },
})

const mv = defineMessages<{
  timeZone: { zone: string }
  removePeriod: { day: string }
  removeDay: { day: string }
  due: { time: string }
  periodFrom: { day: string }
  periodUntil: { day: string }
  dayLine: { day: string; hours: string }
  dayLineNote: { day: string; hours: string; note: string }
}>({
  periodFrom: { id: 'workflows.calendar.periodFrom', defaultMessage: '{day}, from' },
  periodUntil: { id: 'workflows.calendar.periodUntil', defaultMessage: '{day}, until' },
  dayLine: { id: 'workflows.calendar.dayLine', defaultMessage: '{day} · {hours}' },
  dayLineNote: { id: 'workflows.calendar.dayLineNote', defaultMessage: '{day} · {hours} · {note}' },
  timeZone: { id: 'workflows.calendar.timeZone', defaultMessage: 'Time zone: {zone}' },
  removePeriod: { id: 'workflows.calendar.removePeriod', defaultMessage: 'Remove these hours on {day}' },
  removeDay: { id: 'workflows.calendar.removeDay', defaultMessage: 'Remove {day}' },
  due: { id: 'workflows.calendar.due', defaultMessage: 'Due {time}' },
})

/** The pilot's week starts on Sunday (Friday and Saturday off); ISO weekday names as the API uses them. */
const WEEK = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'] as const
type Week = Record<string, string[][]>

/** 2026-11-15 is a Sunday: a fixed date per weekday, only to name the days in the user's language. */
function weekdayName(intl: ReturnType<typeof useIntl>, index: number): string {
  return intl.formatDate(new Date(Date.UTC(2026, 10, 15 + index)), { weekday: 'long', timeZone: 'UTC' })
}

function monthRange(year: number, month: number) {
  const pad = (n: number) => String(n).padStart(2, '0')
  const last = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  return { from: `${String(year)}-${pad(month + 1)}-01`, to: `${String(year)}-${pad(month + 1)}-${pad(last)}` }
}

/**
 * `/admin/workflow/calendar` (design E05 §5, ux-review flow 1): the weekly working hours, holidays and special days by
 * month, and a preview of when a deadline falls; for holders of `workflow.calendar.manage`.
 */
export function CalendarPage() {
  const intl = useIntl()
  const calendar = useQuery(getWorkflowCalendarOptions())

  return (
    <section aria-labelledby="calendar-title" className="flex flex-col gap-6">
      <PageHeader
        title={<span id="calendar-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
      />
      {calendar.isPending ? (
        <LoadingState />
      ) : calendar.isError ? (
        <ErrorState onRetry={() => void calendar.refetch()} />
      ) : (
        <>
          <WeekEditor key={calendar.data.version} calendar={calendar.data} />
          <SpecialDays timeZone={calendar.data.timeZone} />
          <DuePreview timeZone={calendar.data.timeZone} />
        </>
      )}
    </section>
  )
}

function WeekEditor({ calendar }: { calendar: CalendarResponse }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const [week, setWeek] = useState<Week>(calendar.week)
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  const setPeriods = (day: string, periods: string[][]) => {
    setWeek({ ...week, [day]: periods })
  }

  const save = async () => {
    setBusy(true)
    setError(undefined)
    try {
      const body = Object.fromEntries(Object.entries(week).filter(([, periods]) => periods.length > 0))
      await replaceWorkflowCalendar({
        headers: { 'If-Match': `"${String(calendar.version)}"` },
        body: { week: body },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(m.saved), 'success')
      await queryClient.invalidateQueries({ queryKey: getWorkflowCalendarQueryKey() })
    } catch (problem) {
      if (isProblem(problem) && problem.status === 412) {
        toast.show(intl.formatMessage(m.conflict), 'error')
        await queryClient.invalidateQueries({ queryKey: getWorkflowCalendarQueryKey() })
      } else {
        setError((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(m.failed))
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-labelledby="calendar-week" className="rounded-md border p-4">
      <h2 id="calendar-week" className="text-lg font-semibold">
        {intl.formatMessage(m.week)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">
        {intl.formatMessage(mv.timeZone, { zone: calendar.timeZone })}
      </p>
      {calendar.defaulted ? <p className="mb-3 text-sm">{intl.formatMessage(m.defaulted)}</p> : null}
      {error ? (
        <p role="alert" className="mb-3 text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <ul className="flex flex-col divide-y">
        {WEEK.map((day, index) => {
          const name = weekdayName(intl, index)
          const periods = week[day] ?? []
          return (
            <li key={day} className="flex flex-wrap items-center gap-3 py-2">
              <span className="w-28 text-sm font-medium">{name}</span>
              {periods.length === 0 ? (
                <span className="text-sm text-muted-foreground">{intl.formatMessage(m.notWorked)}</span>
              ) : null}
              {periods.map((period, i) => (
                <span key={i} className="flex items-center gap-1">
                  <Input
                    type="time"
                    aria-label={intl.formatMessage(mv.periodFrom, { day: name })}
                    value={period[0] ?? ''}
                    onChange={(e) => {
                      setPeriods(
                        day,
                        periods.map((p, j) => (j === i ? [e.target.value, p[1] ?? ''] : p)),
                      )
                    }}
                  />
                  <Input
                    type="time"
                    aria-label={intl.formatMessage(mv.periodUntil, { day: name })}
                    value={period[1] ?? ''}
                    onChange={(e) => {
                      setPeriods(
                        day,
                        periods.map((p, j) => (j === i ? [p[0] ?? '', e.target.value] : p)),
                      )
                    }}
                  />
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={intl.formatMessage(mv.removePeriod, { day: name })}
                    onClick={() => {
                      setPeriods(
                        day,
                        periods.filter((_, j) => j !== i),
                      )
                    }}
                  >
                    <X aria-hidden="true" />
                  </Button>
                </span>
              ))}
              <Button
                variant="outline"
                onClick={() => {
                  setPeriods(day, [...periods, ['09:00', '17:00']])
                }}
              >
                {intl.formatMessage(m.addPeriod)}
              </Button>
            </li>
          )
        })}
      </ul>
      <div className="mt-3">
        <Button disabled={busy} onClick={() => void save()}>
          {intl.formatMessage(m.saveWeek)}
        </Button>
      </div>
    </section>
  )
}

function SpecialDays({ timeZone }: { timeZone: string }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const today = new Date()
  const [month, setMonth] = useState({ year: today.getFullYear(), month: today.getMonth() })
  const range = monthRange(month.year, month.month)
  const days = useQuery(listWorkflowCalendarDaysOptions({ query: range }))
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [kind, setKind] = useState<'closed' | 'hours'>('closed')
  const [start, setStart] = useState('09:00')
  const [end, setEnd] = useState('17:00')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string>()

  const refresh = () => queryClient.invalidateQueries({ queryKey: listWorkflowCalendarDaysQueryKey({ query: range }) })
  const shift = (by: number) => {
    const d = new Date(month.year, month.month + by, 1)
    setMonth({ year: d.getFullYear(), month: d.getMonth() })
  }
  const dayLabel = (day: string) => intl.formatDate(`${day}T00:00:00`, { dateStyle: 'full' })
  const line = (d: { day: string; periods: string[][]; note?: string | null }) => {
    const hours = d.periods.length === 0 ? intl.formatMessage(m.closed) : d.periods.map((p) => p.join('–')).join(', ')
    return d.note
      ? intl.formatMessage(mv.dayLineNote, { day: dayLabel(d.day), hours, note: d.note })
      : intl.formatMessage(mv.dayLine, { day: dayLabel(d.day), hours })
  }

  const add = async () => {
    if (!from) return
    setError(undefined)
    try {
      await setWorkflowCalendarDays({
        body: {
          from,
          to: to || undefined,
          periods: kind === 'closed' ? [] : [[start, end]],
          note: note.trim() || undefined,
        },
        throwOnError: true,
      })
      toast.show(intl.formatMessage(m.saved), 'success')
      setFrom('')
      setTo('')
      setNote('')
      await refresh()
    } catch (problem) {
      setError((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(m.failed))
    }
  }

  const remove = async (day: string) => {
    try {
      await removeWorkflowCalendarDay({ path: { day }, throwOnError: true })
      await refresh()
    } catch {
      toast.show(intl.formatMessage(m.failed), 'error')
    }
  }

  const monthName = intl.formatDate(new Date(month.year, month.month, 1), { month: 'long', year: 'numeric' })
  return (
    <section aria-labelledby="calendar-days" className="rounded-md border p-4">
      <h2 id="calendar-days" className="text-lg font-semibold">
        {intl.formatMessage(m.days)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(mv.timeZone, { zone: timeZone })}</p>
      <div className="mb-3 flex items-center gap-2">
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(m.previousMonth)}
          onClick={() => {
            shift(-1)
          }}
        >
          <ChevronLeft aria-hidden="true" className="rtl:rotate-180" />
        </Button>
        <span className="min-w-40 text-center text-sm font-medium" aria-live="polite">
          {monthName}
        </span>
        <Button
          variant="ghost"
          size="icon"
          aria-label={intl.formatMessage(m.nextMonth)}
          onClick={() => {
            shift(1)
          }}
        >
          <ChevronRight aria-hidden="true" className="rtl:rotate-180" />
        </Button>
      </div>
      {days.isPending ? (
        <LoadingState />
      ) : days.isError ? (
        <ErrorState onRetry={() => void days.refetch()} />
      ) : days.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(m.noDays)}</p>
      ) : (
        <ul className="flex flex-col divide-y rounded-md border">
          {days.data.map((d) => (
            <li key={d.day} className="flex flex-wrap items-center gap-3 px-3 py-2 text-sm">
              <span className="flex-1">{line(d)}</span>
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(mv.removeDay, { day: dayLabel(d.day) })}
                onClick={() => void remove(d.day)}
              >
                <X aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <h3 className="mt-4 text-base font-semibold">{intl.formatMessage(m.addDays)}</h3>
      {error ? (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
      <div className="mt-2 grid gap-3 sm:grid-cols-2">
        <Field label={intl.formatMessage(m.firstDay)} required>
          {(p) => (
            <Input
              {...p}
              type="date"
              value={from}
              onChange={(e) => {
                setFrom(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.lastDay)}>
          {(p) => (
            <Input
              {...p}
              type="date"
              min={from}
              value={to}
              onChange={(e) => {
                setTo(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.dayKind)}>
          {(p) => (
            <Select
              {...p}
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as 'closed' | 'hours')
              }}
            >
              <option value="closed">{intl.formatMessage(m.kindClosed)}</option>
              <option value="hours">{intl.formatMessage(m.kindHours)}</option>
            </Select>
          )}
        </Field>
        {kind === 'hours' ? (
          <div className="flex gap-2">
            <Field label={intl.formatMessage(m.from)}>
              {(p) => (
                <Input
                  {...p}
                  type="time"
                  value={start}
                  onChange={(e) => {
                    setStart(e.target.value)
                  }}
                />
              )}
            </Field>
            <Field label={intl.formatMessage(m.until)}>
              {(p) => (
                <Input
                  {...p}
                  type="time"
                  value={end}
                  onChange={(e) => {
                    setEnd(e.target.value)
                  }}
                />
              )}
            </Field>
          </div>
        ) : null}
        <Field label={intl.formatMessage(m.note)}>
          {(p) => (
            <Input
              {...p}
              maxLength={200}
              value={note}
              onChange={(e) => {
                setNote(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
      <div className="mt-3">
        <Button variant="outline" disabled={!from} onClick={() => void add()}>
          {intl.formatMessage(m.add)}
        </Button>
      </div>
    </section>
  )
}

function DuePreview({ timeZone }: { timeZone: string }) {
  const intl = useIntl()
  const [due, setDue] = useState('P2D')
  const [working, setWorking] = useState(true)
  const [result, setResult] = useState<string | null>()
  const [error, setError] = useState<string>()

  const calculate = async () => {
    setError(undefined)
    try {
      const answer = await previewWorkflowDue({ body: { due: due.trim(), workingTime: working }, throwOnError: true })
      setResult(answer.data.due ?? null)
    } catch (problem) {
      setError((isProblem(problem) ? problem.detail : undefined) ?? intl.formatMessage(m.failed))
    }
  }

  return (
    <section aria-labelledby="calendar-preview" className="rounded-md border p-4">
      <h2 id="calendar-preview" className="text-lg font-semibold">
        {intl.formatMessage(m.preview)}
      </h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(m.previewLead)}</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={intl.formatMessage(m.duration)} error={error}>
          {(p) => (
            <Input
              {...p}
              dir="ltr"
              className="w-32"
              value={due}
              onChange={(e) => {
                setDue(e.target.value)
              }}
            />
          )}
        </Field>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <Checkbox
            checked={working}
            onChange={(e) => {
              setWorking(e.target.checked)
            }}
          />
          {intl.formatMessage(m.workingTime)}
        </label>
        <Button variant="outline" onClick={() => void calculate()}>
          {intl.formatMessage(m.calculate)}
        </Button>
      </div>
      {result !== undefined ? (
        <p role="status" className="mt-3 text-sm font-medium">
          {result
            ? intl.formatMessage(mv.due, {
                time: intl.formatDate(result, { dateStyle: 'full', timeStyle: 'short', timeZone }),
              })
            : intl.formatMessage(m.noWorkingTime)}
        </p>
      ) : null}
    </section>
  )
}
