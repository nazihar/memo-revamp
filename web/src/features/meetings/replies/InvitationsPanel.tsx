import { useQuery } from '@tanstack/react-query'
import { Ellipsis, Send } from 'lucide-react'
import { useState } from 'react'
import { useIntl, type IntlShape } from 'react-intl'
import { getMeetingInvitationsOptions, getMeetingRsvpsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { InviteeResponse, MeetingResponse, ReplyResponse } from '@/api/generated/types.gen'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { useSession } from '@/features/auth/session'
import { ForecastLines } from '@/features/meetings/attendance/ForecastLines'
import { rm, rmv } from './messages'
import { RecordDialog } from './RecordDialog'
import { ResendDialog } from './ResendDialog'
import { howWords, replyWords, shortWhen } from './words'
import { TableScroll } from '@/components/page/TableScroll'

/** Every invited person, each alternate standing in right after the member they stand in for (flow W1). */
function inOrder(people: InviteeResponse[]): InviteeResponse[] {
  const standing = (p: InviteeResponse) => p.reply?.standingInFor?.userId
  const placed = new Set<string>()
  const ordered: InviteeResponse[] = []
  for (const p of people) {
    if (standing(p) && people.some((m) => m.userId === standing(p))) continue
    ordered.push(p)
    placed.add(p.userId)
    for (const a of people) {
      if (standing(a) === p.userId) {
        ordered.push(a)
        placed.add(a.userId)
      }
    }
  }
  return ordered.concat(people.filter((p) => !placed.has(p.userId)))
}

/** "Member", "Alternate for Demo Director", "Guest". */
function roleWords(intl: IntlShape, p: InviteeResponse): string {
  if (p.reply?.standingInFor) return intl.formatMessage(rmv.alternateFor, { name: p.reply.standingInFor.name })
  if (p.reply) return intl.formatMessage(rm[p.reply.capacity])
  return intl.formatMessage(p.guest ? rm.GUEST : rm.MEMBER)
}

/** Where the person's invitation stands, with the relay's answer: "Sent 3 Dec, 15:40 (update)", "Not delivered …". */
function invitationWords(intl: IntlShape, p: InviteeResponse): string {
  const at = p.lastSentAt ? shortWhen(intl, p.lastSentAt) : ''
  switch (p.state) {
    case 'NOT_INVITED':
      // A member who answers but cannot hold the invitation (no sign-in, or not cleared): the secretary records for them.
      return intl.formatMessage(p.invitable ? rm.notSent : rm.notInvitable)
    case 'UNINVITED':
      return intl.formatMessage(rmv.withdrawnOn, { at })
    case 'CANCELLED':
      return intl.formatMessage(rmv.cancelSentOn, { at })
    case 'INVITED':
      break
  }
  const kind = intl.formatMessage(rm[p.lastKind ?? 'INVITATION'])
  if (p.emailOff || !p.delivery) return intl.formatMessage(rm.appOnly)
  switch (p.delivery.state) {
    case 'SENT':
      return intl.formatMessage(rmv.sentOn, { kind, at: shortWhen(intl, p.delivery.sentAt ?? p.lastSentAt ?? '') })
    case 'FAILED':
      return intl.formatMessage(rmv.failedOn, { kind, at })
    case 'PENDING':
      return p.delivery.attempts > 0
        ? intl.formatMessage(rmv.retrying, { attempts: p.delivery.attempts })
        : intl.formatMessage(rm.waiting)
  }
}

/**
 * The "Invitations and replies" tab of the meeting page for its officers (flow W1): when the invitations went out and
 * the last message, the replies counted ("5 yes (3 in person, 2 online) · 1 apology · 1 not yet · 1 for the old time"),
 * and per person their role, reply with their note, how it reached memo (with who recorded it), and where their
 * invitation stands. Each row's ⋯ menu records an answer given by email, phone or in person (W2) and resends the
 * invitation (W3); "Resend…" resends to those who have not replied or to everyone. The chair reads only.
 */
export function InvitationsPanel({ meeting: m }: { meeting: MeetingResponse }) {
  const intl = useIntl()
  const session = useSession()
  const me = session.data?.user?.id
  const panel = useQuery(getMeetingInvitationsOptions({ path: { meetingId: m.id } }))
  const replies = useQuery(getMeetingRsvpsOptions({ path: { meetingId: m.id } }))
  const [recording, setRecording] = useState<ReplyResponse>()
  const [resending, setResending] = useState<{ only?: InviteeResponse }>()

  if (panel.isPending || replies.isPending) return <LoadingState rows={4} />
  if (panel.isError || replies.isError) {
    return (
      <ErrorState
        onRetry={() => {
          void panel.refetch()
          void replies.refetch()
        }}
      />
    )
  }
  const data = panel.data
  const counts = replies.data.counts
  const members = replies.data.members
  const canRecord = replies.data.canRecord
  const people = inOrder(data.people)
  const invitation = m.invitation

  return (
    <section aria-labelledby="meeting-replies" className="flex flex-col gap-4">
      <h2 id="meeting-replies" className="sr-only">
        {intl.formatMessage(rm.tabReplies)}
      </h2>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          {!data.created ? (
            <p>{intl.formatMessage(rm.notCreated)}</p>
          ) : invitation?.sentAt ? (
            <p>
              {invitation.lastSentAt && invitation.lastSentAt !== invitation.sentAt
                ? intl.formatMessage(rmv.sentWithLast, {
                    at: shortWhen(intl, invitation.sentAt),
                    last: shortWhen(intl, invitation.lastSentAt),
                  })
                : intl.formatMessage(rmv.invitationsSent, { at: shortWhen(intl, invitation.sentAt) })}
            </p>
          ) : null}
          {data.cancelled ? <p>{intl.formatMessage(rm.cancelledSent)}</p> : null}
          {data.pending && !data.cancelled ? <p>{intl.formatMessage(rm.pending)}</p> : null}
        </div>
        {data.canResend ? (
          <Button
            variant="outline"
            className="min-h-11"
            onClick={() => {
              setResending({})
            }}
          >
            <Send aria-hidden="true" />
            {intl.formatMessage(rm.resendAction)}
          </Button>
        ) : null}
      </div>
      <div className="flex flex-col gap-1">
        <p className="font-medium">
          {intl.formatMessage(rmv.repliesLine, {
            yes: counts.inPerson + counts.remote,
            inPerson: counts.inPerson,
            remote: counts.remote,
            apologies: counts.apologies,
            noAnswer: counts.noAnswer,
            stale: counts.stale,
          })}
        </p>
        {members.total ? (
          <p>
            {intl.formatMessage(rmv.membersLine, {
              attending: members.inPerson + members.remote,
              total: members.total,
            })}
          </p>
        ) : null}
        {m.status === 'SCHEDULED' ? <ForecastLines meeting={m} /> : null}
      </div>
      {people.length ? (
        <TableScroll>
          <table className="w-full min-w-[44rem] border-collapse text-sm">
            <caption className="sr-only">{intl.formatMessage(rm.tabReplies)}</caption>
            <thead>
              <tr className="border-b text-muted-foreground">
                {[rm.colName, rm.colRole, rm.colReply, rm.colHow, rm.colInvitation].map((col) => (
                  <th key={col.id} scope="col" className="py-2 pe-3 text-start font-medium">
                    {intl.formatMessage(col)}
                  </th>
                ))}
                <th scope="col" className="py-2 text-end font-medium">
                  <span className="sr-only">{intl.formatMessage(rm.colActions)}</span>
                </th>
              </tr>
            </thead>
            <tbody>
              {people.map((p) => {
                const reply = p.reply
                const record = canRecord && reply != null && p.userId !== me ? reply : undefined
                const resend = data.canResend && p.state === 'INVITED'
                return (
                  <tr key={p.userId} className="border-b align-top">
                    <th
                      scope="row"
                      className={`py-3 pe-3 text-start font-medium ${reply?.standingInFor ? 'ps-4' : ''}`}
                    >
                      {p.name}
                    </th>
                    <td className="py-3 pe-3">{roleWords(intl, p)}</td>
                    <td className="py-3 pe-3">
                      <span className="block">{reply ? replyWords(intl, reply) : intl.formatMessage(rm.none)}</span>
                      {reply?.note ? (
                        <span className="block text-muted-foreground">
                          {intl.formatMessage(rmv.memberNote, { note: reply.note })}
                        </span>
                      ) : null}
                    </td>
                    <td className="py-3 pe-3">
                      <span className="block">
                        {reply?.how ? howWords(intl, reply.how) : intl.formatMessage(rm.none)}
                      </span>
                      {reply?.recordedBy && reply.how !== 'IN_APP' && reply.answeredAt ? (
                        <span className="block text-muted-foreground">
                          {reply.howNote
                            ? intl.formatMessage(rmv.recordedNote, {
                                name: reply.recordedBy.name,
                                at: shortWhen(intl, reply.answeredAt),
                                note: reply.howNote,
                              })
                            : intl.formatMessage(rmv.recordedOn, {
                                name: reply.recordedBy.name,
                                at: shortWhen(intl, reply.answeredAt),
                              })}
                        </span>
                      ) : null}
                    </td>
                    <td className="py-3 pe-3">{invitationWords(intl, p)}</td>
                    <td className="py-3 text-end">
                      {record || resend ? (
                        <DropdownMenu>
                          <DropdownMenuTrigger asChild>
                            <Button
                              variant="ghost"
                              size="icon"
                              aria-label={intl.formatMessage(rmv.actionsFor, { name: p.name })}
                            >
                              <Ellipsis aria-hidden="true" />
                            </Button>
                          </DropdownMenuTrigger>
                          <DropdownMenuContent align="end">
                            {record ? (
                              <DropdownMenuItem
                                onSelect={() => {
                                  setRecording(record)
                                }}
                              >
                                {intl.formatMessage(rm.record)}
                              </DropdownMenuItem>
                            ) : null}
                            {resend ? (
                              <DropdownMenuItem
                                onSelect={() => {
                                  setResending({ only: p })
                                }}
                              >
                                {intl.formatMessage(rm.resendPerson)}
                              </DropdownMenuItem>
                            ) : null}
                          </DropdownMenuContent>
                        </DropdownMenu>
                      ) : null}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </TableScroll>
      ) : data.created ? (
        <p>{intl.formatMessage(rm.nobody)}</p>
      ) : null}
      {recording ? (
        <RecordDialog
          meeting={m}
          reply={recording}
          onClose={() => {
            setRecording(undefined)
          }}
        />
      ) : null}
      {resending ? (
        <ResendDialog
          meetingId={m.id}
          people={data.people}
          {...(resending.only ? { only: resending.only } : {})}
          onClose={() => {
            setResending(undefined)
          }}
        />
      ) : null}
    </section>
  )
}
