import { useQuery, useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { addMemoCopies, replaceMemoCopies, revokeMemoShare, shareMemo } from '@/api/generated/sdk.gen'
import {
  listDistributionListsOptions,
  listMemoCopiesOptions,
  listMemoCopiesQueryKey,
  listMemoReceiptsOptions,
  listMemoSharesOptions,
  listMemoSharesQueryKey,
} from '@/api/generated/@tanstack/react-query.gen'
import type { CopyView, MemoView, ReceiptView } from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { routeEntryLabel } from './route-entry'

const m = defineMessages({
  title: { id: 'memo.access.title', defaultMessage: 'People with access' },
  approvers: { id: 'memo.access.approvers', defaultMessage: 'Approvers' },
  copies: { id: 'memo.access.copies', defaultMessage: 'Copies' },
  shared: { id: 'memo.access.shared', defaultMessage: 'Shared with' },
  nobody: { id: 'memo.access.nobody', defaultMessage: 'Nobody' },
  share: { id: 'memo.access.share', defaultMessage: 'Share' },
  copyTo: { id: 'memo.access.copyTo', defaultMessage: 'Copy to…' },
  revoke: { id: 'memo.access.revoke', defaultMessage: 'Revoke' },
  copyNotice: {
    id: 'memo.access.copyNotice',
    defaultMessage: 'You received a copy of this memo for information. You cannot act on it.',
  },
  shareTitle: { id: 'memo.share.title', defaultMessage: 'Share this memo' },
  person: { id: 'memo.share.person', defaultMessage: 'Person' },
  reason: { id: 'memo.share.reason', defaultMessage: 'Reason' },
  until: { id: 'memo.share.until', defaultMessage: 'Until (optional, at most a year)' },
  shareHint: {
    id: 'memo.share.hint',
    defaultMessage: 'Only people cleared for the memo’s classification can be chosen.',
  },
  cancel: { id: 'memo.share.cancel', defaultMessage: 'Cancel' },
  copyTitle: { id: 'memo.copy.title', defaultMessage: 'Copy to' },
  copyLead: { id: 'memo.copy.lead', defaultMessage: 'Copies can read the memo and its notes. They cannot act on it.' },
  addPerson: { id: 'memo.copy.addPerson', defaultMessage: 'Add a person' },
  addPosition: { id: 'memo.copy.addPosition', defaultMessage: 'Add a position' },
  addList: { id: 'memo.copy.addList', defaultMessage: 'Add a distribution list' },
  chooseList: { id: 'memo.copy.chooseList', defaultMessage: 'Choose a list' },
  atSubmit: { id: 'memo.copy.atSubmit', defaultMessage: 'At submit' },
  afterApproval: { id: 'memo.copy.afterApproval', defaultMessage: 'After approval' },
  save: { id: 'memo.copy.save', defaultMessage: 'Save' },
  receiptsTitle: { id: 'memo.receipts.title', defaultMessage: 'Copies and receipts' },
  unread: { id: 'memo.receipts.unread', defaultMessage: 'Not opened yet' },
  close: { id: 'memo.receipts.close', defaultMessage: 'Close' },
  kindPosition: { id: 'memo.copy.kind.position', defaultMessage: 'position' },
  kindList: { id: 'memo.copy.kind.list', defaultMessage: 'list' },
})

const mv = defineMessages<{
  copiesCount: { sent: number; read: number }
  sharedBy: { name: string; reason: string }
  sharedUntil: { date: string }
  remove: { name: string }
  sendWhen: { name: string }
  outcome: { outcome: string }
  readAt: { time: string; version: number }
}>({
  copiesCount: {
    id: 'memo.access.copiesCount',
    defaultMessage: '{sent, plural, one {# person} other {# people}} · {read, number} read',
  },
  sharedBy: { id: 'memo.access.sharedBy', defaultMessage: 'by {name}: “{reason}”' },
  sharedUntil: { id: 'memo.access.sharedUntil', defaultMessage: 'until {date}' },
  remove: { id: 'memo.copy.remove', defaultMessage: 'Remove {name}' },
  sendWhen: { id: 'memo.copy.sendWhen', defaultMessage: 'When to send to {name}' },
  outcome: {
    id: 'memo.receipts.outcome',
    defaultMessage: '{outcome, select, NOT_CLEARED {Not sent: not cleared} INACTIVE {Not sent: inactive} other {Sent}}',
  },
  readAt: { id: 'memo.receipts.readAt', defaultMessage: '{time} (v{version, number})' },
})

/**
 * Who can see the memo (ADR-0050, flow D1): approvers, copies with how many have read, and shares with their reason.
 * Everyone who reads the memo sees it; the actions show only to those the server lets act.
 */
export function MemoAccess({ memo, onProblem }: { memo: MemoView; onProblem: (problem: Problem | undefined) => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const copies = useQuery(listMemoCopiesOptions({ path: { id: memo.id } }))
  const shares = useQuery(listMemoSharesOptions({ path: { id: memo.id } }))
  const [dialog, setDialog] = useState<'share' | 'copy' | 'receipts' | undefined>()
  const approvers = memo.route.map((e) => routeEntryLabel(intl, e))
  const sharer = memo.readingAs === 'INITIATOR' || memo.readingAs === 'COAUTHOR' || memo.readingAs === 'APPROVER'
  const sent = (copies.data?.copies ?? []).reduce((n, c) => n + c.sent, 0)
  const read = (copies.data?.copies ?? []).reduce((n, c) => n + c.read, 0)
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: listMemoCopiesQueryKey({ path: { id: memo.id } }) }),
      queryClient.invalidateQueries({ queryKey: listMemoSharesQueryKey({ path: { id: memo.id } }) }),
    ])

  const revoke = async (shareId: string) => {
    try {
      await revokeMemoShare({ path: { id: memo.id, shareId }, throwOnError: true })
      await refresh()
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    }
  }

  return (
    <section aria-labelledby="memo-access" className="flex flex-col gap-3 rounded-md border p-4">
      <div className="flex items-center justify-between gap-2">
        <h2 id="memo-access" className="text-base font-semibold">
          {intl.formatMessage(m.title)}
        </h2>
        {sharer ? (
          <Button
            variant="outline"
            onClick={() => {
              setDialog('share')
            }}
          >
            {intl.formatMessage(m.share)}
          </Button>
        ) : null}
      </div>
      {memo.readingAs === 'COPY' ? (
        <p role="note" className="text-sm">
          {intl.formatMessage(m.copyNotice)}
        </p>
      ) : null}
      <dl className="grid gap-2 text-sm">
        <dt className="font-medium">{intl.formatMessage(m.approvers)}</dt>
        <dd className="text-muted-foreground">
          {approvers.length > 0 ? intl.formatList(approvers) : intl.formatMessage(m.nobody)}
        </dd>
        <dt className="font-medium">{intl.formatMessage(m.copies)}</dt>
        <dd className="flex flex-wrap items-center gap-2 text-muted-foreground">
          {(copies.data?.copies.length ?? 0) === 0 ? (
            intl.formatMessage(m.nobody)
          ) : (
            <button
              type="button"
              className="underline"
              onClick={() => {
                setDialog('receipts')
              }}
            >
              {intl.formatMessage(mv.copiesCount, { sent, read })}
            </button>
          )}
          {copies.data?.canEdit || copies.data?.canAdd ? (
            <Button
              variant="outline"
              onClick={() => {
                setDialog('copy')
              }}
            >
              {intl.formatMessage(m.copyTo)}
            </Button>
          ) : null}
        </dd>
        <dt className="font-medium">{intl.formatMessage(m.shared)}</dt>
        <dd>
          {(shares.data ?? []).length === 0 ? (
            <span className="text-muted-foreground">{intl.formatMessage(m.nobody)}</span>
          ) : (
            <ul className="flex flex-col gap-2">
              {(shares.data ?? []).map((s) => (
                <li key={s.id} className="flex items-start justify-between gap-2">
                  <span>
                    <span className="me-1 font-medium">{s.person.name}</span>
                    {s.expiresAt ? (
                      <span className="text-muted-foreground">
                        {intl.formatMessage(mv.sharedUntil, {
                          date: intl.formatDate(s.expiresAt, { dateStyle: 'medium' }),
                        })}
                      </span>
                    ) : null}
                    <span className="block text-xs text-muted-foreground">
                      {intl.formatMessage(mv.sharedBy, { name: s.grantedBy.name, reason: s.reason })}
                    </span>
                  </span>
                  {s.canRevoke ? (
                    <Button variant="ghost" onClick={() => void revoke(s.id)}>
                      {intl.formatMessage(m.revoke)}
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </dd>
      </dl>
      {dialog === 'share' ? (
        <ShareDialog
          memoId={memo.id}
          onClose={() => {
            setDialog(undefined)
          }}
          onDone={() => {
            setDialog(undefined)
            void refresh()
          }}
          onProblem={onProblem}
        />
      ) : null}
      {dialog === 'copy' && copies.data ? (
        <CopyDialog
          memoId={memo.id}
          adding={!copies.data.canEdit}
          memoVersion={copies.data.memoVersion}
          initial={copies.data.copies
            .filter((c) => !c.sentAt)
            .map((c) => ({ kind: c.recipient.kind, id: c.recipient.id, sendWhen: c.sendWhen, name: c.recipient.name }))}
          sentNames={copies.data.copies.filter((c) => c.sentAt).map((c) => c.recipient.name)}
          onClose={() => {
            setDialog(undefined)
          }}
          onDone={() => {
            setDialog(undefined)
            void refresh()
          }}
          onProblem={onProblem}
        />
      ) : null}
      {dialog === 'receipts' ? (
        <ReceiptsDialog
          memoId={memo.id}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function ShareDialog({
  memoId,
  onClose,
  onDone,
  onProblem,
}: {
  memoId: string
  onClose: () => void
  onDone: () => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const [person, setPerson] = useState<Picked>()
  const [reason, setReason] = useState('')
  const [until, setUntil] = useState('')
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = async () => {
    if (!person || !reason.trim()) return
    setBusy(true)
    const res = await shareMemo({
      path: { id: memoId },
      body: {
        userId: person.id,
        reason: reason.trim(),
        expiresAt: until ? new Date(`${until}T23:59:59`).toISOString() : null,
      },
    })
    setBusy(false)
    if (res.response?.ok) {
      onDone()
      return
    }
    const problem = isProblem(res.error) ? res.error : undefined
    if (problem?.errors?.length) setErrors(Object.fromEntries(problem.errors.map((e) => [e.field, e.message])))
    else onProblem(problem)
  }
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(m.shareTitle)}
      description={intl.formatMessage(m.shareHint)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button onClick={() => void submit()} disabled={busy || !person || !reason.trim()}>
            {intl.formatMessage(m.share)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field label={intl.formatMessage(m.person)} required error={errors.userId}>
          {(p) => <PeoplePicker id={p.id} kind="user" value={person} onChange={setPerson} invalid={!!errors.userId} />}
        </Field>
        <Field label={intl.formatMessage(m.reason)} required error={errors.reason}>
          {(p) => (
            <Input
              {...p}
              value={reason}
              maxLength={500}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.until)} error={errors.expiresAt}>
          {(p) => (
            <Input
              {...p}
              type="date"
              value={until}
              onChange={(e) => {
                setUntil(e.target.value)
              }}
            />
          )}
        </Field>
      </div>
    </Dialog>
  )
}

interface Row {
  kind: CopyView['recipient']['kind']
  id: string
  sendWhen: CopyView['sendWhen']
  name: string
}

function CopyDialog({
  memoId,
  adding,
  memoVersion,
  initial,
  sentNames,
  onClose,
  onDone,
  onProblem,
}: {
  memoId: string
  /** After approval: recipients can only be added, and go at once. */
  adding: boolean
  memoVersion: number
  initial: Row[]
  sentNames: string[]
  onClose: () => void
  onDone: () => void
  onProblem: (problem: Problem | undefined) => void
}) {
  const intl = useIntl()
  const [rows, setRows] = useState<Row[]>(adding ? [] : initial)
  const [busy, setBusy] = useState(false)
  const lists = useQuery(listDistributionListsOptions())
  const add = (row: Row) => {
    if (!rows.some((r) => r.kind === row.kind && r.id === row.id)) setRows([...rows, row])
  }
  const save = async () => {
    setBusy(true)
    const copies = rows.map(({ kind, id, sendWhen }) => ({ kind, id, sendWhen }))
    try {
      if (adding) await addMemoCopies({ path: { id: memoId }, body: { copies }, throwOnError: true })
      else
        await replaceMemoCopies({
          path: { id: memoId },
          body: { copies },
          headers: { 'If-Match': `"${String(memoVersion)}"` },
          throwOnError: true,
        })
      onDone()
    } catch (problem) {
      onProblem(isProblem(problem) ? problem : undefined)
    } finally {
      setBusy(false)
    }
  }
  const kindLabel = (row: Row) =>
    row.kind === 'POSITION'
      ? ` (${intl.formatMessage(m.kindPosition)})`
      : row.kind === 'LIST'
        ? ` (${intl.formatMessage(m.kindList)})`
        : ''
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      className="w-[min(40rem,calc(100vw-2rem))]"
      title={intl.formatMessage(m.copyTitle)}
      description={intl.formatMessage(m.copyLead)}
      footer={
        <>
          <Button variant="outline" onClick={onClose}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button onClick={() => void save()} disabled={busy}>
            {intl.formatMessage(m.save)}
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <div className="grid gap-2 sm:grid-cols-3">
          <Field label={intl.formatMessage(m.addPerson)}>
            {(p) => (
              <PeoplePicker
                id={p.id}
                kind="user"
                onChange={(picked) => {
                  if (picked) add({ kind: 'USER', id: picked.id, name: picked.label, sendWhen: 'AFTER_APPROVAL' })
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(m.addPosition)}>
            {(p) => (
              <PeoplePicker
                id={p.id}
                kind="position"
                onChange={(picked) => {
                  if (picked) add({ kind: 'POSITION', id: picked.id, name: picked.label, sendWhen: 'AFTER_APPROVAL' })
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(m.addList)}>
            {(p) => (
              <Select
                {...p}
                value=""
                onChange={(e) => {
                  const list = lists.data?.find((l) => l.id === e.target.value)
                  if (list) add({ kind: 'LIST', id: list.id, name: list.name, sendWhen: 'AFTER_APPROVAL' })
                }}
              >
                <option value="">{intl.formatMessage(m.chooseList)}</option>
                {(lists.data ?? []).map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
        <ul className="flex flex-col divide-y rounded-md border">
          {sentNames.map((name) => (
            <li key={`sent-${name}`} className="px-3 py-2 text-sm text-muted-foreground">
              {name}
            </li>
          ))}
          {rows.map((row) => (
            <li key={`${row.kind}-${row.id}`} className="flex flex-wrap items-center gap-3 px-3 py-2 text-sm">
              <span className="flex-1">{row.name + kindLabel(row)}</span>
              {adding ? null : (
                <Select
                  aria-label={intl.formatMessage(mv.sendWhen, { name: row.name })}
                  value={row.sendWhen}
                  onChange={(e) => {
                    setRows(rows.map((r) => (r === row ? { ...r, sendWhen: e.target.value as Row['sendWhen'] } : r)))
                  }}
                >
                  <option value="AT_SUBMIT">{intl.formatMessage(m.atSubmit)}</option>
                  <option value="AFTER_APPROVAL">{intl.formatMessage(m.afterApproval)}</option>
                </Select>
              )}
              <button
                type="button"
                aria-label={intl.formatMessage(mv.remove, { name: row.name })}
                onClick={() => {
                  setRows(rows.filter((r) => r !== row))
                }}
              >
                <X className="size-4" aria-hidden="true" />
              </button>
            </li>
          ))}
        </ul>
      </div>
    </Dialog>
  )
}

function ReceiptsDialog({ memoId, onClose }: { memoId: string; onClose: () => void }) {
  const intl = useIntl()
  const receipts = useQuery({ ...listMemoReceiptsOptions({ path: { id: memoId } }), retry: false })
  const time = (at: string | null | undefined, version: number | null | undefined) =>
    at
      ? intl.formatMessage(mv.readAt, {
          time: intl.formatDate(at, { dateStyle: 'medium', timeStyle: 'short' }),
          version: version ?? 0,
        })
      : intl.formatMessage(m.unread)
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      className="w-[min(40rem,calc(100vw-2rem))]"
      title={intl.formatMessage(m.receiptsTitle)}
      footer={
        <Button variant="outline" onClick={onClose}>
          {intl.formatMessage(m.close)}
        </Button>
      }
    >
      <div className="flex flex-col gap-4 text-sm">
        <ReceiptList title={intl.formatMessage(m.copies)} rows={receipts.data?.copies ?? []} time={time} />
        <ReceiptList title={intl.formatMessage(m.approvers)} rows={receipts.data?.approvers ?? []} time={time} />
      </div>
    </Dialog>
  )
}

function ReceiptList({
  title,
  rows,
  time,
}: {
  title: string
  rows: ReceiptView[]
  time: (at: string | null | undefined, version: number | null | undefined) => string
}) {
  const intl = useIntl()
  return (
    <section>
      <h3 className="mb-1 font-medium">{title}</h3>
      <ul className="flex flex-col gap-1">
        {rows.map((r) => (
          <li key={`${r.person.id}-${r.via ?? ''}`} className="flex flex-wrap justify-between gap-2">
            <span>
              {r.person.name}
              {r.via ? <span className="ms-2 text-muted-foreground">{r.via}</span> : null}
            </span>
            <span className="text-muted-foreground">
              {r.outcome && r.outcome !== 'SENT'
                ? intl.formatMessage(mv.outcome, { outcome: r.outcome })
                : time(r.openedAt, r.documentVersion)}
            </span>
          </li>
        ))}
      </ul>
    </section>
  )
}
