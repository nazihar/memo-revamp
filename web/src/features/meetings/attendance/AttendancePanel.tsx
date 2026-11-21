import { useQuery } from '@tanstack/react-query'
import { ChevronDown, Ellipsis, UserCheck } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import {
  getMeetingAgendaOptions,
  getMeetingAttendanceOptions,
  getMeetingQuorumOptions,
  getMeetingRsvpsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import type { AttendanceResponse, MeetingResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { FormDialog } from '@/features/forums/FormDialog'
import { meetingName } from '@/features/meetings/shared'
import { CorrectDialog, EntryDialog } from './EntryDialogs'
import { am, amv } from './messages'
import {
  agendaItems,
  attendanceRefusal,
  clock,
  eventWords,
  itemTitle,
  itemWords,
  lateWords,
  LIVE_REFRESH_MS,
  modesFor,
  roleWords,
  stateWords,
  type AgendaItem,
  type AttendanceEvent,
  type Context,
  type Kind,
  type Mode,
  type Person,
} from './words'
import { useAttendanceWrites, type Entry } from './writes'

type Opened =
  | { kind: 'entry'; person: Person; kinds: Kind[] }
  | { kind: 'correct'; person: Person }
  | { kind: 'bulk'; groups: { mode: Mode; userIds: string[] }[] }

/**
 * The attendance register (flow X1, Phase 4 E05 T8) on the meeting page's "Attendance" tab for its officers, from the
 * start of the meeting: "Now: item n" (the item in progress, which labels every arrival and departure and drives the
 * per-item quorum), "Mark everyone who said yes as present", then the members (each alternate standing in under the
 * member they replace) and the people in attendance, each with where they are in words and one-press arrivals and
 * departures recording "now"; "⋯" offers another time, a change to online or in person, stepping out for the item in
 * progress, and "Correct an entry…" (X2). Once the meeting is held every entry is a correction with a reason, until the
 * minutes are approved; then the register only reads. The chair reads it without recording.
 */
export function AttendancePanel({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const titleId = useId()
  const path = { path: { meetingId: m.id } }
  const live = m.status === 'IN_PROGRESS'
  const register = useQuery({ ...getMeetingAttendanceOptions(path), refetchInterval: live ? LIVE_REFRESH_MS : false })
  const agenda = useQuery(getMeetingAgendaOptions(path))
  const canRecord = register.data?.canRecord ?? false
  const replies = useQuery({ ...getMeetingRsvpsOptions(path), enabled: live && canRecord })
  const itemId = register.data?.itemInProgress?.itemId
  const itemQuorum = useQuery({
    ...getMeetingQuorumOptions({ ...path, query: { itemId: itemId ?? '' } }),
    enabled: live && itemId != null,
    refetchInterval: LIVE_REFRESH_MS,
  })
  const writes = useAttendanceWrites(m.id)
  const [opened, setOpened] = useState<Opened>()
  const [refusal, setRefusal] = useState<string>()
  const [done, setDone] = useState<string>()
  const [busy, setBusy] = useState(false)
  // After a change, focus goes to the person's state line (their buttons change under the pointer).
  const focusUser = useRef<string | undefined>(undefined)
  const panelRef = useRef<HTMLElement>(null)
  useEffect(() => {
    const user = focusUser.current
    if (!user) return
    const target = panelRef.current?.querySelector<HTMLElement>(`[data-state-of="${CSS.escape(user)}"]`)
    if (target) {
      target.focus()
      focusUser.current = undefined
    }
  })

  if (register.isPending) return <LoadingState rows={5} />
  if (register.isError) return <ErrorState onRetry={() => void register.refetch()} />
  const data = register.data
  const items = agendaItems(agenda.data)
  const people = data.people ?? []
  const events = data.events ?? []
  const ctx: Context = { items, timeZone: m.timeZone, events }
  const recording = data.canRecord
  const correcting = data.correcting

  // Who declared an interest in the item in progress, from its quorum's seats (a recused officer gets no seats).
  const recusedNow = new Set<string>()
  for (const seat of itemQuorum.data?.seats ?? []) {
    if (!seat.recused) continue
    recusedNow.add(seat.holder.userId)
    if (seat.filledBy) recusedNow.add(seat.filledBy.userId)
  }
  for (const p of people) {
    if (p.standingInFor && recusedNow.has(p.standingInFor.userId)) recusedNow.add(p.userId)
  }

  const run = async (person: Person | undefined, action: () => Promise<unknown>, success: string) => {
    if (busy) return
    setBusy(true)
    setRefusal(undefined)
    setDone(undefined)
    try {
      await action()
      setDone(success)
      focusUser.current = person?.userId
    } catch (problem) {
      setRefusal(attendanceRefusal(intl, problem, person?.name))
    } finally {
      setBusy(false)
    }
  }

  const quick = (person: Person, entry: Entry, success: string) => {
    void run(person, () => writes.record(person, entry), success)
  }

  // "Everyone who said yes": expected people whose reply for the current times is yes, in the way they said.
  const expected = new Set(people.filter((p) => p.state === 'EXPECTED').map((p) => p.userId))
  const yes = (replies.data?.people ?? []).filter(
    (r) => expected.has(r.userId) && !r.stale && (r.answer === 'IN_PERSON' || r.answer === 'REMOTE'),
  )
  const offered = modesFor(m.mode)
  const groups = offered
    .map((mode) => ({ mode, userIds: yes.filter((r) => r.answer === mode).map((r) => r.userId) }))
    .filter((g) => g.userIds.length > 0)
  const yesCount = groups.reduce((n, g) => n + g.userIds.length, 0)

  const members = ordered(people.filter((p) => p.capacity === 'MEMBER' || p.capacity === 'ALTERNATE'))
  const others = people.filter((p) => p.capacity === 'GUEST' || p.capacity === 'ATTENDEE')

  const rowProps = {
    meeting: m,
    ctx,
    people,
    recording,
    live,
    correcting,
    busy,
    itemInProgress: itemId,
    recusedNow,
    onQuick: quick,
    onOpen: setOpened,
  }

  return (
    <section ref={panelRef} aria-labelledby={titleId} className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id={titleId} className="text-lg font-semibold">
          {intl.formatMessage(amv.registerTitle, { name: meetingName(m) })}
        </h2>
        {live ? (
          <NowItem
            data={data}
            items={items}
            canChange={recording}
            busy={busy}
            onChange={(item) =>
              void run(
                undefined,
                () => writes.progress(item.id),
                intl.formatMessage(amv.nowDone, {
                  item: itemTitle(intl, item),
                }),
              )
            }
          />
        ) : null}
      </div>
      {correcting && recording ? <p>{intl.formatMessage(am.correctingHint)}</p> : null}
      {!recording && (m.status === 'MINUTES_APPROVED' || m.status === 'CLOSED') ? (
        <p>{intl.formatMessage(am.frozenHint)}</p>
      ) : null}
      <p className="text-muted-foreground">
        {intl.formatMessage(amv.countsLine, {
          inPerson: data.counts.inPerson,
          remote: data.counts.remote,
          apologies: data.counts.apologies,
          expected: data.counts.expected,
          left: data.counts.left,
          absent: data.counts.absent,
        })}
      </p>
      {refusal ? (
        <p role="alert" className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-destructive">
          {refusal}
        </p>
      ) : null}
      <p role="status" className="sr-only">
        {done}
      </p>
      {live && recording && yesCount > 0 ? (
        <div>
          <Button
            className="h-auto min-h-12 whitespace-normal px-5 text-base"
            disabled={busy}
            onClick={() => {
              setOpened({ kind: 'bulk', groups })
            }}
          >
            <UserCheck aria-hidden="true" />
            {intl.formatMessage(amv.bulkAction, { count: yesCount })}
          </Button>
        </div>
      ) : null}
      <Group title={intl.formatMessage(am.groupMembers)} rows={members} {...rowProps} />
      {others.length ? <Group title={intl.formatMessage(am.groupOthers)} rows={others} {...rowProps} /> : null}
      <History events={events} people={people} ctx={ctx} />
      {opened?.kind === 'bulk' ? (
        <BulkDialog
          groups={opened.groups}
          onSubmit={async () => {
            try {
              await writes.arrivals(opened.groups)
              setDone(intl.formatMessage(amv.bulkDone, { count: yesCount }))
              return undefined
            } catch (problem) {
              return attendanceRefusal(intl, problem)
            }
          }}
          onClose={() => {
            setOpened(undefined)
          }}
        />
      ) : null}
      {opened?.kind === 'entry' ? (
        <EntryDialog
          meeting={m}
          person={opened.person}
          kinds={opened.kinds}
          items={items}
          correcting={correcting}
          record={writes.record}
          onDone={() => {
            setDone(intl.formatMessage(am.recorded))
            focusUser.current = opened.person.userId
          }}
          onClose={() => {
            setOpened(undefined)
          }}
        />
      ) : null}
      {opened?.kind === 'correct' ? (
        <CorrectDialog
          meeting={m}
          person={opened.person}
          events={correctable(events, opened.person)}
          items={items}
          correcting={correcting}
          voidEntry={writes.voidEntry}
          record={writes.record}
          onDone={() => {
            setDone(intl.formatMessage(am.corrected))
            focusUser.current = opened.person.userId
          }}
          onClose={() => {
            setOpened(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

/** The person's entries that can be corrected: not voided, newest first. */
function correctable(events: AttendanceEvent[], person: Person): AttendanceEvent[] {
  return events.filter((e) => e.userId === person.userId && e.kind !== 'VOIDED' && !e.voided).reverse()
}

/** The members, each alternate standing in right after the member they replace. */
function ordered(people: Person[]): Person[] {
  const out: Person[] = []
  const placed = new Set<string>()
  for (const p of people) {
    if (p.capacity === 'ALTERNATE' && p.standingInFor && people.some((x) => x.userId === p.standingInFor?.userId)) {
      continue
    }
    out.push(p)
    placed.add(p.userId)
    for (const a of people) {
      if (a.capacity === 'ALTERNATE' && a.standingInFor?.userId === p.userId && !placed.has(a.userId)) {
        out.push(a)
        placed.add(a.userId)
      }
    }
  }
  return out.concat(people.filter((p) => !placed.has(p.userId)))
}

/** "Now: item 3 Minutes of the last meeting [Change ▾]": the item in progress, moved by the secretary. */
function NowItem({
  data,
  items,
  canChange,
  busy,
  onChange,
}: {
  data: AttendanceResponse
  items: AgendaItem[]
  canChange: boolean
  busy: boolean
  onChange: (item: AgendaItem) => void
}) {
  const intl = useIntl()
  const current = items.find((i) => i.id === data.itemInProgress?.itemId)
  return (
    <div className="flex flex-wrap items-center gap-2">
      <p className="font-medium">
        {current ? intl.formatMessage(amv.now, { item: itemTitle(intl, current) }) : intl.formatMessage(am.nowNone)}
      </p>
      {canChange && items.length ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" className="min-h-11" disabled={busy}>
              {intl.formatMessage(am.nowChange)}
              <ChevronDown aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" aria-label={intl.formatMessage(am.nowMenu)}>
            <DropdownMenuRadioGroup
              value={current?.id ?? ''}
              onValueChange={(id) => {
                const item = items.find((i) => i.id === id)
                if (item && item.id !== current?.id) onChange(item)
              }}
            >
              {items.map((i) => (
                <DropdownMenuRadioItem key={i.id} value={i.id} className="min-h-11">
                  {itemTitle(intl, i)}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
    </div>
  )
}

interface RowProps {
  meeting: MeetingResponse
  ctx: Context
  people: Person[]
  recording: boolean
  live: boolean
  correcting: boolean
  busy: boolean
  itemInProgress: string | undefined
  recusedNow: Set<string>
  onQuick: (person: Person, entry: Entry, success: string) => void
  onOpen: (opened: Opened) => void
}

function Group({ title, rows, ...rest }: { title: string; rows: Person[] } & RowProps) {
  const intl = useIntl()
  const id = useId()
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h3 id={id} className="text-base font-semibold">
        {title}
      </h3>
      {rows.length ? (
        <div className="relative overflow-x-auto">
          <table className="w-full min-w-[40rem] border-collapse text-sm">
            <caption className="sr-only">{title}</caption>
            <thead>
              <tr className="border-b text-muted-foreground">
                {[am.colName, am.colRole, am.colAttendance].map((col) => (
                  <th key={col.id} scope="col" className="py-2 pe-3 text-start font-medium">
                    {intl.formatMessage(col)}
                  </th>
                ))}
                <th scope="col" className="py-2 text-end font-medium">
                  <span className="sr-only">{intl.formatMessage(am.colActions)}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((p) => (
                <Row key={p.userId} person={p} {...rest} />
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        <p>{intl.formatMessage(am.nobody)}</p>
      )}
    </section>
  )
}

const MODE_ACTION = { IN_PERSON: am.actionInPerson, REMOTE: am.actionOnline } as const
const BACK_ACTION = { IN_PERSON: am.actionBackInPerson, REMOTE: am.actionBackOnline } as const

function Row({ person: p, ...props }: { person: Person } & RowProps) {
  const intl = useIntl()
  const { meeting: m, ctx, people, recording, live, correcting, busy, itemInProgress, recusedNow } = props
  const modes = modesFor(m.mode)
  const name = p.name
  const standIn = people.find((a) => a.capacity === 'ALTERNATE' && a.standingInFor?.userId === p.userId)
  const declared = itemInProgress != null && recusedNow.has(p.userId)
  const steppedOut = p.steppedOutOf
  const late = lateWords(intl, p, ctx)
  const notes = [
    ...(late ? [late] : []),
    ...(p.capacity === 'ALTERNATE' && p.counted ? [intl.formatMessage(am.numberOnly)] : []),
  ]
  const role =
    p.capacity === 'ALTERNATE'
      ? p.standingInFor
        ? intl.formatMessage(amv.alternateFor, { name: p.standingInFor.name })
        : intl.formatMessage(am.alternate)
      : p.capacity === 'GUEST'
        ? intl.formatMessage(am.guest)
        : p.capacity === 'ATTENDEE'
          ? intl.formatMessage(am.inAttendance)
          : p.role
            ? roleWords(intl, p.role)
            : intl.formatMessage(am.roleMember)
  const entry = (kind: Kind, extra: Partial<Entry> = {}): Entry => ({ kind, ...extra })
  const arrived = (mode: Mode) =>
    intl.formatMessage(mode === 'REMOTE' ? amv.arrivedOnline : amv.arrivedInPerson, { name })

  const quickButtons: ReactNode[] = []
  if (recording && live) {
    if (p.state === 'EXPECTED' || p.state === 'LEFT') {
      for (const mode of modes) {
        const label = intl.formatMessage(p.state === 'LEFT' ? BACK_ACTION[mode] : MODE_ACTION[mode])
        quickButtons.push(
          <Button
            key={mode}
            variant="outline"
            className="min-h-11"
            disabled={busy}
            aria-label={intl.formatMessage(amv.actionFor, { action: label, name })}
            onClick={() => {
              props.onQuick(p, entry('ARRIVED', { mode }), arrived(mode))
            }}
          >
            {label}
          </Button>,
        )
      }
    }
    if (p.state === 'EXPECTED') {
      for (const [kind, label, done] of [
        ['APOLOGIES', am.actionApologies, amv.apologiesDone],
        ['ABSENT', am.actionAbsent, amv.absentDone],
      ] as const) {
        const text = intl.formatMessage(label)
        quickButtons.push(
          <Button
            key={kind}
            variant="outline"
            className="min-h-11"
            disabled={busy}
            aria-label={intl.formatMessage(amv.actionFor, { action: text, name })}
            onClick={() => {
              props.onQuick(p, entry(kind), intl.formatMessage(done, { name }))
            }}
          >
            {text}
          </Button>,
        )
      }
    }
    if (p.state === 'PRESENT' && steppedOut) {
      const text = intl.formatMessage(am.actionBack)
      quickButtons.push(
        <Button
          key="back"
          variant="outline"
          className="min-h-11"
          disabled={busy}
          aria-label={intl.formatMessage(amv.actionFor, { action: text, name })}
          onClick={() => {
            props.onQuick(p, entry('STEPPED_BACK', { itemId: steppedOut }), intl.formatMessage(amv.backDone, { name }))
          }}
        >
          {text}
        </Button>,
      )
    } else if (p.state === 'PRESENT' && declared) {
      const text = intl.formatMessage(am.actionStepOut)
      quickButtons.push(
        <Button
          key="out"
          variant="outline"
          className="min-h-11"
          disabled={busy}
          aria-label={intl.formatMessage(amv.actionFor, { action: text, name })}
          onClick={() => {
            props.onQuick(
              p,
              entry('STEPPED_OUT', { itemId: itemInProgress }),
              intl.formatMessage(amv.steppedOutDone, { name }),
            )
          }}
        >
          {text}
        </Button>,
      )
    }
    if (p.state === 'PRESENT') {
      const text = intl.formatMessage(am.actionLeft)
      quickButtons.push(
        <Button
          key="left"
          variant="outline"
          className="min-h-11"
          disabled={busy}
          aria-label={intl.formatMessage(amv.actionFor, { action: text, name })}
          onClick={() => {
            props.onQuick(p, entry('LEFT'), intl.formatMessage(amv.leftDone, { name }))
          }}
        >
          {text}
        </Button>,
      )
    }
  }

  // The ⋯ menu: everything that is not one press.
  const menu: { key: string; label: string; select: () => void }[] = []
  if (recording && live) {
    if (p.state === 'APOLOGIES' || p.state === 'EXPECTED' || p.state === 'LEFT') {
      if (p.state === 'APOLOGIES') {
        for (const mode of modes) {
          menu.push({
            key: `came-${mode}`,
            label: intl.formatMessage(mode === 'REMOTE' ? am.menuCameOnline : am.menuCameInPerson),
            select: () => {
              props.onQuick(p, entry('ARRIVED', { mode }), arrived(mode))
            },
          })
        }
      }
      menu.push({
        key: 'later-arrival',
        label: intl.formatMessage(am.menuArrivedAt),
        select: () => {
          props.onOpen({ kind: 'entry', person: p, kinds: ['ARRIVED'] })
        },
      })
    }
    if (p.state === 'PRESENT') {
      menu.push({
        key: 'later-left',
        label: intl.formatMessage(am.menuLeftAt),
        select: () => {
          props.onOpen({ kind: 'entry', person: p, kinds: ['LEFT'] })
        },
      })
      for (const mode of modes.filter((x) => x !== p.mode)) {
        menu.push({
          key: `mode-${mode}`,
          label: intl.formatMessage(mode === 'REMOTE' ? am.menuNowOnline : am.menuNowInPerson),
          select: () => {
            props.onQuick(p, entry('MODE_CHANGED', { mode }), intl.formatMessage(am.recorded))
          },
        })
      }
      if (itemInProgress && !steppedOut && !declared) {
        menu.push({
          key: 'out',
          label: intl.formatMessage(am.actionStepOut),
          select: () => {
            props.onQuick(
              p,
              entry('STEPPED_OUT', { itemId: itemInProgress }),
              intl.formatMessage(amv.steppedOutDone, { name }),
            )
          },
        })
      }
    }
  }
  if (recording && correcting) {
    menu.push({
      key: 'record',
      label: intl.formatMessage(am.menuRecordEntry),
      select: () => {
        props.onOpen({ kind: 'entry', person: p, kinds: ['ARRIVED', 'LEFT', 'APOLOGIES', 'ABSENT'] })
      },
    })
  }
  if (recording && ctx.events.some((e) => e.userId === p.userId && e.kind !== 'VOIDED' && !e.voided)) {
    menu.push({
      key: 'correct',
      label: intl.formatMessage(am.menuCorrect),
      select: () => {
        props.onOpen({ kind: 'correct', person: p })
      },
    })
  }

  const alternate = p.capacity === 'ALTERNATE' && p.standingInFor != null
  return (
    <tr className="border-b align-top">
      <th scope="row" className={`py-3 pe-3 text-start font-medium ${alternate ? 'ps-4' : ''}`}>
        {name}
      </th>
      <td className="py-3 pe-3">{role}</td>
      <td className="py-3 pe-3">
        <p
          data-state-of={p.userId}
          tabIndex={-1}
          className="font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {notes.length
            ? intl.formatMessage(amv.stateNotes, { state: stateWords(intl, p, ctx), notes: intl.formatList(notes) })
            : stateWords(intl, p, ctx)}
        </p>
        {steppedOut ? (
          <p>{intl.formatMessage(amv.steppedOutFor, { item: itemWords(intl, ctx.items, steppedOut) })}</p>
        ) : null}
        {declared && !steppedOut ? <p>{intl.formatMessage(am.declaredHere)}</p> : null}
        {standIn ? <p>{intl.formatMessage(amv.standsIn, { name: standIn.name })}</p> : null}
      </td>
      <td className="py-3 text-end">
        <div className="flex flex-wrap justify-end gap-2">
          {quickButtons}
          {menu.length ? (
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button
                  variant="ghost"
                  size="icon"
                  disabled={busy}
                  aria-label={intl.formatMessage(amv.moreFor, { name })}
                >
                  <Ellipsis aria-hidden="true" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end">
                {menu.map((item) => (
                  <DropdownMenuItem key={item.key} className="min-h-11" onSelect={item.select}>
                    {item.label}
                  </DropdownMenuItem>
                ))}
              </DropdownMenuContent>
            </DropdownMenu>
          ) : null}
        </div>
      </td>
    </tr>
  )
}

/** "Mark 4 people present in the way they said (3 in person, 1 online)? You can change anyone afterwards." */
function BulkDialog({
  groups,
  onSubmit,
  onClose,
}: {
  groups: { mode: Mode; userIds: string[] }[]
  onSubmit: () => Promise<string | undefined>
  onClose: () => void
}) {
  const intl = useIntl()
  const count = (mode: Mode) => groups.find((g) => g.mode === mode)?.userIds.length ?? 0
  const total = groups.reduce((n, g) => n + g.userIds.length, 0)
  return (
    <FormDialog
      large
      title={intl.formatMessage(am.bulkTitle)}
      description={intl.formatMessage(amv.bulkConfirm, {
        count: total,
        inPerson: count('IN_PERSON'),
        remote: count('REMOTE'),
      })}
      submitLabel={intl.formatMessage(amv.bulkSubmit, { count: total })}
      cancelLabel={intl.formatMessage(am.cancel)}
      onSubmit={async () => {
        const refused = await onSubmit()
        if (!refused) onClose()
        return refused
      }}
      onClose={onClose}
    />
  )
}

/**
 * "History": every arrival, departure and correction, newest first; a voided entry stays, struck through, with who
 * corrected it, when and why.
 */
function History({ events, people, ctx }: { events: AttendanceEvent[]; people: Person[]; ctx: Context }) {
  const intl = useIntl()
  const [open, setOpen] = useState(false)
  const listId = useId()
  const names = new Map(people.map((p) => [p.userId, p.name]))
  const voids = new Map(events.filter((e) => e.voidsEventId).map((e) => [e.voidsEventId ?? '', e]))
  const shown = events.filter((e) => e.kind !== 'VOIDED').reverse()
  return (
    <section className="flex flex-col gap-2 border-t pt-3">
      <div>
        <Button
          variant="ghost"
          className="h-auto min-h-11 max-w-full whitespace-normal py-2 text-start"
          aria-expanded={open}
          aria-controls={open ? listId : undefined}
          onClick={() => {
            setOpen(!open)
          }}
        >
          <ChevronDown aria-hidden="true" className={open ? 'rotate-180' : ''} />
          {intl.formatMessage(amv.history, { count: shown.length })}
        </Button>
      </div>
      {open ? (
        shown.length ? (
          <ol id={listId} className="flex flex-col gap-2">
            {shown.map((e) => {
              const voided = voids.get(e.id)
              const line = intl.formatMessage(amv.historyLine, {
                name: names.get(e.userId) ?? '',
                entry: eventWords(intl, e, ctx),
              })
              return (
                <li key={e.id} className="flex flex-col">
                  {voided ? (
                    <s>
                      <span className="sr-only">{intl.formatMessage(am.voidedPrefix)} </span>
                      {line}
                    </s>
                  ) : (
                    <span>{line}</span>
                  )}
                  <span className="text-sm text-muted-foreground">
                    {intl.formatMessage(amv.recordedByAt, {
                      name: e.recordedBy?.name ?? intl.formatMessage(am.theSystem),
                      at: clock(intl, e.recordedAt, ctx.timeZone),
                    })}
                    {e.reason ? (
                      <span className="block">{intl.formatMessage(amv.correctionNote, { reason: e.reason })}</span>
                    ) : null}
                  </span>
                  {voided ? (
                    <span className="text-sm">
                      {intl.formatMessage(amv.voidedBy, {
                        name: voided.recordedBy?.name ?? '',
                        at: clock(intl, voided.recordedAt, ctx.timeZone),
                        reason: voided.reason ?? '',
                      })}
                    </span>
                  ) : null}
                </li>
              )
            })}
          </ol>
        ) : (
          <p id={listId}>{intl.formatMessage(am.historyEmpty)}</p>
        )
      ) : null}
    </section>
  )
}
