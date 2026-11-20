import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Info, Plus, TriangleAlert } from 'lucide-react'
import { useCallback, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getAgendaDraftOptions,
  getAgendaDraftQueryKey,
  getMeetingAgendaOptions,
  getMeetingDeclarationRegisterOptions,
  getMeetingDeclarationRegisterQueryKey,
  getMyDeclarationsQueryKey,
  listForumMembersOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { remindDeclarations } from '@/api/generated/sdk.gen'
import type {
  DeclarationResponse,
  MeetingDeclarationRegisterResponse,
  MeetingResponse,
  RegisterItemResponse,
} from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { useSession } from '@/features/auth/session'
import { dm, dmv } from './messages'
import { om, omv } from './officer-messages'
import {
  chairsOf,
  itemLabel,
  officerRefusal,
  officersAct,
  originWords,
  parentsOf,
  quorumWords,
  recordableItems,
  recusals,
  rulerOf,
  rulingWords,
  stateWords,
  type Chairs,
} from './officers'
import { RecordSheet, type Choice } from './RecordSheet'
import { RulingDialog, WithdrawForDialog } from './RulingDialog'

const TITLE_ID = 'officer-declarations-title'

const button = 'h-auto min-h-11 whitespace-normal text-base'

type Dialog =
  | { kind: 'record'; opener: HTMLElement | null }
  | { kind: 'rule'; d: DeclarationResponse; item: string; onBehalf: boolean; opener: HTMLElement | null }
  | { kind: 'withdraw'; d: DeclarationResponse; item: string; opener: HTMLElement | null }

/** Gives focus back to [opener]; when it has gone with a change, to the tab's heading. */
function restoreFocus(opener: HTMLElement | null): void {
  window.setTimeout(() => {
    if (opener?.isConnected) opener.focus()
    else document.getElementById(TITLE_ID)?.focus()
  }, 0)
}

const tones: Record<DeclarationResponse['state'], Tone> = {
  RECUSED: 'warn',
  AWAITING_RULING: 'danger',
  TAKING_PART: 'ok',
  WITHDRAWN: 'neutral',
}

/**
 * The Declarations tab of a meeting (Phase 4 E04 T7, flow T1), for the committee's officers and its chair or
 * vice-chair: who has reviewed the agenda for interests, with "Remind them now"; the declarations by item with their
 * interest, how they were made and the chair's decision; "Decide…" for the person who rules, "Record the chair's
 * decision…" for the secretaries; withdrawals with a reason; the quorum warning and "also reads the memo"; and "Record a
 * declaration…" (flow T2). An officer recused from an item sees only their own declaration on it.
 */
export function OfficerDeclarations({ meeting }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const session = useSession()
  const me = session.data?.user?.id
  const officer = meeting.relation === 'OFFICER'
  const meetingId = meeting.id
  const register = useQuery(getMeetingDeclarationRegisterOptions({ path: { meetingId } }))
  const members = useQuery(listForumMembersOptions({ path: { forumId: meeting.forumId } }))
  const draft = useQuery({ ...getAgendaDraftOptions({ path: { meetingId } }), enabled: officer })
  const agenda = useQuery({ ...getMeetingAgendaOptions({ path: { meetingId } }), enabled: !officer })
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [reminding, setReminding] = useState(false)
  const [status, setStatus] = useState<{ text: string; problem: boolean }>()

  const refresh = useCallback(async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: getMeetingDeclarationRegisterQueryKey({ path: { meetingId } }) }),
      queryClient.invalidateQueries({ queryKey: getAgendaDraftQueryKey({ path: { meetingId } }) }),
      queryClient.invalidateQueries({ queryKey: getMyDeclarationsQueryKey({ path: { meetingId } }) }),
      queryClient.invalidateQueries({
        predicate: (query) => {
          const k = query.queryKey[0] as { _id?: string; path?: { meetingId?: string } } | undefined
          return k?._id === 'getMeetingAgenda' && k.path?.meetingId === meetingId
        },
      }),
    ])
  }, [queryClient, meetingId])

  const chairs = chairsOf(members.data)
  const names = new Map<string, string>()
  for (const m of members.data ?? []) if (m.person) names.set(m.person.id, m.person.name)
  const people = ((): Choice[] => {
    const map = new Map<string, Choice>()
    for (const m of members.data ?? []) {
      if (m.person && m.state === 'IN_OFFICE') map.set(m.person.id, { id: m.person.id, name: m.person.name })
    }
    for (const g of meeting.guests) map.set(g.userId, { id: g.userId, name: g.name })
    for (const s of draft.data?.sections ?? []) {
      for (const i of s.items.flatMap((x) => [x, ...x.subItems])) {
        for (const a of i.attendees) map.set(a.id, { id: a.id, name: a.name })
      }
    }
    return [...map.values()].sort((a, b) => a.name.localeCompare(b.name, intl.locale))
  })()
  const items: Choice[] = recordableItems(draft.data).map((i) => ({
    id: i.id,
    name: itemLabel(intl, i.number, i.title),
  }))

  if (register.isPending) return <LoadingState rows={4} />
  if (register.isError) {
    return isProblem(register.error) && register.error.status === 403 ? (
      <p className="text-lg">{intl.formatMessage(om.forbidden)}</p>
    ) : (
      <ErrorState title={intl.formatMessage(om.loadFailed)} onRetry={() => void register.refetch()} />
    )
  }
  const data = register.data
  const parents = parentsOf(officer ? draft.data : agenda.data)
  const recusedFrom = recusals(data.items, parents)
  const acting = officersAct(meeting.status)
  const correcting = meeting.status === 'HELD' && meeting.canManage
  const canRecord = officer && (acting || correcting)
  const timeZone = meeting.timeZone
  const meetingDate = intl.formatDate(meeting.startsAt, { day: 'numeric', month: 'long', year: 'numeric', timeZone })

  const close = () => {
    const opener = dialog?.opener ?? null
    setDialog(null)
    restoreFocus(opener)
  }

  const remind = async () => {
    setReminding(true)
    setStatus(undefined)
    try {
      const answer = await remindDeclarations({ path: { meetingId }, throwOnError: true })
      const reminded = answer.data.reminded
      setStatus({
        text: reminded.length
          ? intl.formatMessage(omv.reminded, {
              count: reminded.length,
              names: intl.formatList(reminded.map((p) => p.name)),
            })
          : intl.formatMessage(om.remindedNone),
        problem: false,
      })
      await refresh()
    } catch (problem) {
      setStatus({ text: officerRefusal(intl, problem), problem: true })
      await refresh()
    } finally {
      setReminding(false)
    }
  }

  return (
    <section aria-labelledby={TITLE_ID} className="flex flex-col gap-5 text-lg">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id={TITLE_ID} tabIndex={-1} className="text-2xl font-semibold">
          {intl.formatMessage(dm.panelTitle)}
        </h2>
        {data.latestRevision > 0 ? (
          <span className="text-base text-muted-foreground">
            {intl.formatMessage(omv.revision, { revision: data.latestRevision })}
          </span>
        ) : null}
      </div>
      <Reviews data={data} reminding={reminding} onRemind={() => void remind()} />
      {status ? (
        <p
          role={status.problem ? 'alert' : 'status'}
          className={status.problem ? 'text-destructive' : 'text-status-ok-fg'}
        >
          {status.text}
        </p>
      ) : null}
      {data.items.length === 0 ? <p className="text-muted-foreground">{intl.formatMessage(om.none)}</p> : null}
      {data.items.map((item) => (
        <ItemDeclarations
          key={item.itemId}
          item={item}
          me={me}
          chairs={chairs}
          names={names}
          meeting={meeting}
          recusedFrom={recusedFrom}
          onRule={(d, onBehalf, opener) => {
            setDialog({ kind: 'rule', d, item: itemLabel(intl, item.number, item.title), onBehalf, opener })
          }}
          onWithdraw={(d, opener) => {
            setDialog({ kind: 'withdraw', d, item: itemLabel(intl, item.number, item.title), opener })
          }}
        />
      ))}
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 border-t pt-4">
        {canRecord ? (
          <Button
            variant="outline"
            className={button}
            onClick={(e) => {
              setDialog({ kind: 'record', opener: e.currentTarget })
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(om.record)}
          </Button>
        ) : null}
        <Link
          to="/declarations"
          search={{ forumId: meeting.forumId }}
          className="inline-flex min-h-11 items-center text-base text-primary underline-offset-4 hover:underline"
        >
          {intl.formatMessage(om.registerLink)}
        </Link>
      </div>
      {dialog?.kind === 'record' ? (
        <RecordSheet
          meetingId={meetingId}
          status={meeting.status}
          people={people}
          items={items}
          refresh={refresh}
          onDone={close}
          onClose={close}
        />
      ) : null}
      {dialog?.kind === 'rule' ? (
        <RulingDialog
          declaration={dialog.d}
          item={dialog.item}
          meetingDate={meetingDate}
          timeZone={timeZone}
          onBehalf={dialog.onBehalf}
          ruler={(() => {
            const id = rulerOf(dialog.d, chairs, recusedFrom)
            return id ? names.get(id) : undefined
          })()}
          chairDeclared={chairs.chair !== undefined && dialog.d.user.id === chairs.chair}
          refresh={refresh}
          onDone={close}
          onClose={close}
        />
      ) : null}
      {dialog?.kind === 'withdraw' ? (
        <WithdrawForDialog declaration={dialog.d} item={dialog.item} refresh={refresh} onDone={close} onClose={close} />
      ) : null}
    </section>
  )
}

/** "Reviewed: 5 of 7 · Not yet: N. Rahman, K. Hossain [Remind them now]" (flow T1). */
function Reviews({
  data,
  reminding,
  onRemind,
}: {
  data: MeetingDeclarationRegisterResponse
  reminding: boolean
  onRemind: () => void
}) {
  const intl = useIntl()
  if (data.latestRevision === 0) return <p className="text-muted-foreground">{intl.formatMessage(om.notPublished)}</p>
  const waiting = data.reviews.filter((r) => r.toReview > 0)
  const done = data.reviews.length - waiting.length
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
      <p className="font-medium">{intl.formatMessage(omv.reviewed, { reviewed: done, total: data.reviews.length })}</p>
      {waiting.length ? (
        <p>
          {intl.formatMessage(omv.notYet, {
            names: intl.formatList(
              waiting.map((r) =>
                r.remindedRevision === data.latestRevision
                  ? intl.formatMessage(omv.remindedName, { name: r.user.name })
                  : r.user.name,
              ),
            ),
          })}
        </p>
      ) : (
        <p>{intl.formatMessage(om.everyoneReviewed)}</p>
      )}
      {data.canRemind && waiting.length ? (
        <Button variant="outline" className={button} disabled={reminding} onClick={onRemind}>
          {intl.formatMessage(om.remind)}
        </Button>
      ) : null}
    </div>
  )
}

/** One item of the register: its declarations, the memo flag and the quorum warning. */
function ItemDeclarations({
  item,
  me,
  chairs,
  names,
  meeting,
  recusedFrom,
  onRule,
  onWithdraw,
}: {
  item: RegisterItemResponse
  me: string | undefined
  chairs: Chairs
  names: Map<string, string>
  meeting: MeetingResponse
  recusedFrom: (itemId: string) => Set<string>
  onRule: (d: DeclarationResponse, onBehalf: boolean, opener: HTMLElement) => void
  onWithdraw: (d: DeclarationResponse, opener: HTMLElement) => void
}) {
  const intl = useIntl()
  const label = itemLabel(intl, item.number, item.title)
  const headingId = `declarations-item-${item.itemId}`
  const short = item.number ?? label
  const acting = officersAct(meeting.status)
  const officer = meeting.relation === 'OFFICER'
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3 rounded-md border p-4">
      <h3 id={headingId} dir="auto" className="text-xl font-semibold">
        {intl.formatMessage(omv.item, { name: label })}
      </h3>
      {item.recused ? <p className="text-muted-foreground">{intl.formatMessage(om.youRecused)}</p> : null}
      {item.declarations.length ? (
        <ul className="flex flex-col gap-4">
          {item.declarations.map((d) => {
            const open = d.state !== 'WITHDRAWN'
            const own = d.user.id === me
            const ruler = rulerOf(d, chairs, recusedFrom)
            const rules = open && acting && !own && !item.recused && ruler !== undefined && ruler === me
            const records = open && acting && !own && !item.recused && !rules && meeting.canManage
            const decision =
              d.state === 'AWAITING_RULING' || d.state === 'TAKING_PART' || (d.state === 'RECUSED' && d.ruling)
            const withdraws =
              open &&
              (own || !item.recused) &&
              ((officer && acting) || (meeting.status === 'HELD' && meeting.canManage))
            const values = { name: d.user.name, item: short }
            return (
              <li key={d.id} className="flex flex-col gap-1.5 border-t pt-3 first:border-t-0 first:pt-0">
                <p className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <span className="font-medium">{d.user.name}</span>
                  <Badge tone={tones[d.state]}>{stateWords(intl, d.state)}</Badge>
                </p>
                <p className="text-base text-muted-foreground">{originWords(intl, d, meeting.timeZone)}</p>
                {d.correction ? <p className="text-base">{intl.formatMessage(om.correction)}</p> : null}
                <blockquote dir="auto" className="whitespace-pre-line border-s-4 ps-3">
                  {intl.formatMessage(dmv.nature, { nature: d.nature })}
                </blockquote>
                {d.ruling && d.state !== 'AWAITING_RULING' ? (
                  <p className="text-base">
                    {rulingWords(intl, d.ruling, meeting.timeZone)}
                    {d.ruling.note ? <> {intl.formatMessage(omv.rulingNote, { note: d.ruling.note })}</> : null}
                  </p>
                ) : null}
                {d.state === 'AWAITING_RULING' ? (
                  <p className="text-base">
                    {ruler === undefined
                      ? intl.formatMessage(om.noRuler)
                      : ruler === me
                        ? intl.formatMessage(om.waitingYou)
                        : intl.formatMessage(omv.waitingFor, { name: names.get(ruler) ?? '' })}
                  </p>
                ) : null}
                {((rules || records) && decision) || withdraws ? (
                  <div className="flex flex-wrap gap-2 pt-1">
                    {rules && decision ? (
                      <Button
                        className={button}
                        aria-label={intl.formatMessage(
                          d.state === 'AWAITING_RULING' ? omv.decideNamed : omv.changeNamed,
                          values,
                        )}
                        onClick={(e) => {
                          onRule(d, false, e.currentTarget)
                        }}
                      >
                        {intl.formatMessage(d.state === 'AWAITING_RULING' ? om.decide : om.changeDecision)}
                      </Button>
                    ) : null}
                    {records && decision ? (
                      <Button
                        variant={d.state === 'AWAITING_RULING' ? 'default' : 'outline'}
                        className={button}
                        aria-label={intl.formatMessage(omv.recordRulingNamed, values)}
                        onClick={(e) => {
                          onRule(d, true, e.currentTarget)
                        }}
                      >
                        {intl.formatMessage(om.recordRuling)}
                      </Button>
                    ) : null}
                    {withdraws ? (
                      <Button
                        variant="outline"
                        className={`${button} text-destructive`}
                        aria-label={intl.formatMessage(omv.withdrawNamed, values)}
                        onClick={(e) => {
                          onWithdraw(d, e.currentTarget)
                        }}
                      >
                        {intl.formatMessage(om.withdraw)}
                      </Button>
                    ) : null}
                  </div>
                ) : null}
              </li>
            )
          })}
        </ul>
      ) : null}
      {item.alsoReadsMemo.length ? (
        <p className="flex gap-2 text-base">
          <Info aria-hidden="true" className="mt-1 size-5 shrink-0" />
          <span>
            {intl.formatMessage(omv.alsoReads, {
              names: intl.formatList(item.alsoReadsMemo.map((p) => p.name)),
              count: item.alsoReadsMemo.length,
            })}
          </span>
        </p>
      ) : null}
      {item.quorumWarning ? (
        <div className="flex gap-2 rounded-md border border-status-warn-fg/40 bg-status-warn-bg p-3 text-base text-status-warn-fg">
          <TriangleAlert aria-hidden="true" className="mt-1 size-5 shrink-0" />
          <div className="flex flex-col gap-1">
            <p>{quorumWords(intl, short, item.quorumWarning)}</p>
            <p>{intl.formatMessage(om.quorumTip)}</p>
          </div>
        </div>
      ) : null}
    </section>
  )
}
