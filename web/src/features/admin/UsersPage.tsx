import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { MoreHorizontal, UserPlus } from 'lucide-react'
import { useRef, useState, type SyntheticEvent } from 'react'
import { defineMessages, useIntl, type NoMessageValues } from 'react-intl'
import { listUsersOptions, listUsersQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import {
  deactivateUser,
  inviteUser,
  reactivateUser,
  resetUserMfa,
  revokeUserSessions,
  unlockUser,
} from '@/api/generated/sdk.gen'
import type { AdminUser } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { useSession } from '@/features/auth/session'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog, Dialog } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'

type Status = AdminUser['status']
type Action = 'deactivate' | 'reactivate' | 'unlock' | 'mfaReset' | 'revokeSessions'

const m = defineMessages<{
  title: NoMessageValues
  lead: NoMessageValues
  invite: NoMessageValues
  search: NoMessageValues
  status: NoMessageValues
  all: NoMessageValues
  apply: NoMessageValues
  caption: NoMessageValues
  colUser: NoMessageValues
  colStatus: NoMessageValues
  colMfa: NoMessageValues
  colLastSignIn: NoMessageValues
  colActions: NoMessageValues
  admin: NoMessageValues
  mfaOn: NoMessageValues
  mfaOff: NoMessageValues
  never: NoMessageValues
  empty: NoMessageValues
  actionsFor: { name: string }
  INVITED: NoMessageValues
  ACTIVE: NoMessageValues
  LOCKED: NoMessageValues
  DEACTIVATED: NoMessageValues
  deactivate: NoMessageValues
  reactivate: NoMessageValues
  unlock: NoMessageValues
  mfaReset: NoMessageValues
  revokeSessions: NoMessageValues
  confirmDeactivate: { name: string }
  confirmReactivate: { name: string }
  confirmUnlock: { name: string }
  confirmMfaReset: { name: string }
  confirmRevokeSessions: { name: string }
  explainDeactivate: NoMessageValues
  explainReactivate: NoMessageValues
  explainUnlock: NoMessageValues
  explainMfaReset: NoMessageValues
  explainRevokeSessions: NoMessageValues
  cancel: NoMessageValues
  done: NoMessageValues
  self: NoMessageValues
  lastAdmin: NoMessageValues
  wrongState: NoMessageValues
  changed: NoMessageValues
  failed: NoMessageValues
  inviteTitle: NoMessageValues
  inviteLead: NoMessageValues
  email: NoMessageValues
  name: NoMessageValues
  makeAdmin: NoMessageValues
  send: NoMessageValues
  sent: { email: string }
  taken: NoMessageValues
  required: NoMessageValues
}>({
  title: { id: 'users.title', defaultMessage: 'Users' },
  lead: { id: 'users.lead', defaultMessage: 'Everyone who can sign in to your organisation.' },
  invite: { id: 'users.invite', defaultMessage: 'Invite user' },
  search: { id: 'users.search', defaultMessage: 'Search by name or email' },
  status: { id: 'users.status', defaultMessage: 'Status' },
  all: { id: 'users.status.all', defaultMessage: 'All' },
  apply: { id: 'users.apply', defaultMessage: 'Search' },
  caption: { id: 'users.caption', defaultMessage: 'Users of the organisation' },
  colUser: { id: 'users.col.user', defaultMessage: 'User' },
  colStatus: { id: 'users.col.status', defaultMessage: 'Status' },
  colMfa: { id: 'users.col.mfa', defaultMessage: 'Two-step sign-in' },
  colLastSignIn: { id: 'users.col.lastSignIn', defaultMessage: 'Last sign-in' },
  colActions: { id: 'users.col.actions', defaultMessage: 'Actions' },
  admin: { id: 'users.admin', defaultMessage: 'Administrator' },
  mfaOn: { id: 'users.mfa.on', defaultMessage: 'On' },
  mfaOff: { id: 'users.mfa.off', defaultMessage: 'Off' },
  never: { id: 'users.never', defaultMessage: 'Never' },
  empty: { id: 'users.empty', defaultMessage: 'No users match.' },
  actionsFor: { id: 'users.actionsFor', defaultMessage: 'Actions for {name}' },
  INVITED: { id: 'users.status.invited', defaultMessage: 'Invited' },
  ACTIVE: { id: 'users.status.active', defaultMessage: 'Active' },
  LOCKED: { id: 'users.status.locked', defaultMessage: 'Locked' },
  DEACTIVATED: { id: 'users.status.deactivated', defaultMessage: 'Deactivated' },
  deactivate: { id: 'users.action.deactivate', defaultMessage: 'Deactivate' },
  reactivate: { id: 'users.action.reactivate', defaultMessage: 'Reactivate' },
  unlock: { id: 'users.action.unlock', defaultMessage: 'Unlock' },
  mfaReset: { id: 'users.action.mfaReset', defaultMessage: 'Reset two-step sign-in' },
  revokeSessions: { id: 'users.action.revokeSessions', defaultMessage: 'Sign out everywhere' },
  confirmDeactivate: { id: 'users.confirm.deactivate', defaultMessage: 'Deactivate {name}?' },
  confirmReactivate: { id: 'users.confirm.reactivate', defaultMessage: 'Reactivate {name}?' },
  confirmUnlock: { id: 'users.confirm.unlock', defaultMessage: 'Unlock {name}?' },
  confirmMfaReset: { id: 'users.confirm.mfaReset', defaultMessage: 'Reset two-step sign-in for {name}?' },
  confirmRevokeSessions: { id: 'users.confirm.revokeSessions', defaultMessage: 'Sign {name} out everywhere?' },
  explainDeactivate: {
    id: 'users.explain.deactivate',
    defaultMessage: 'They are signed out at once and cannot sign in until reactivated. Their work stays.',
  },
  explainReactivate: { id: 'users.explain.reactivate', defaultMessage: 'They can sign in again.' },
  explainUnlock: { id: 'users.explain.unlock', defaultMessage: 'They can try to sign in again right away.' },
  explainMfaReset: {
    id: 'users.explain.mfaReset',
    defaultMessage: 'They are signed out and set up an authenticator app again at the next sign-in. They get an email.',
  },
  explainRevokeSessions: { id: 'users.explain.revokeSessions', defaultMessage: 'Every device they use is signed out.' },
  cancel: { id: 'users.cancel', defaultMessage: 'Cancel' },
  done: { id: 'users.done', defaultMessage: 'Done.' },
  self: { id: 'users.refused.self', defaultMessage: 'You cannot do this to your own account.' },
  lastAdmin: {
    id: 'users.refused.lastAdmin',
    defaultMessage: 'The organisation needs at least one active administrator.',
  },
  wrongState: { id: 'users.refused.state', defaultMessage: 'Not possible in the account’s current status.' },
  changed: { id: 'users.changed', defaultMessage: 'Someone changed this account meanwhile. The list was reloaded.' },
  failed: { id: 'users.failed', defaultMessage: 'That did not work. Try again.' },
  inviteTitle: { id: 'users.inviteTitle', defaultMessage: 'Invite a user' },
  inviteLead: {
    id: 'users.inviteLead',
    defaultMessage: 'They get an email with a link to set their password. The link works for 7 days.',
  },
  email: { id: 'users.email', defaultMessage: 'Work email' },
  name: { id: 'users.name', defaultMessage: 'Full name' },
  makeAdmin: { id: 'users.makeAdmin', defaultMessage: 'Administrator of the organisation' },
  send: { id: 'users.send', defaultMessage: 'Send invitation' },
  sent: { id: 'users.sent', defaultMessage: 'Invitation sent to {email}.' },
  taken: { id: 'users.taken', defaultMessage: 'An account with this email already exists.' },
  required: { id: 'users.required', defaultMessage: 'Required.' },
})

const statusTone: Record<Status, Tone> = { INVITED: 'warn', ACTIVE: 'ok', LOCKED: 'danger', DEACTIVATED: 'neutral' }
const STATUSES: Status[] = ['ACTIVE', 'INVITED', 'LOCKED', 'DEACTIVATED']

/** `/admin/users` (design E03 §3, §11): list with search and status filter, invitations and lifecycle actions. */
export function UsersPage() {
  const intl = useIntl()
  const [query, setQuery] = useState('')
  const [applied, setApplied] = useState<{ q?: string; status?: Status }>({})
  const [cursors, setCursors] = useState<(string | undefined)[]>([undefined])
  const [statusFilter, setStatusFilter] = useState<Status | ''>('')
  const [inviting, setInviting] = useState(false)
  const cursor = cursors[cursors.length - 1]
  const list = useQuery(
    listUsersOptions({
      query: {
        ...(applied.q ? { q: applied.q } : {}),
        ...(applied.status ? { status: applied.status } : {}),
        ...(cursor ? { cursor } : {}),
        limit: 25,
      },
    }),
  )
  const [pending, setPending] = useState<{ user: AdminUser; action: Action }>()

  const apply = (e: SyntheticEvent) => {
    e.preventDefault()
    setApplied({ ...(query.trim() ? { q: query.trim() } : {}), ...(statusFilter ? { status: statusFilter } : {}) })
    setCursors([undefined])
  }

  const columns: DataTableColumn<AdminUser>[] = [
    {
      id: 'user',
      header: intl.formatMessage(m.colUser),
      cell: ({ row }) => (
        <div>
          <div className="flex flex-wrap items-center gap-2 font-medium">
            {row.original.displayName}
            {row.original.tenantAdmin ? <Badge tone="neutral">{intl.formatMessage(m.admin)}</Badge> : null}
          </div>
          <div className="text-xs text-muted-foreground">{row.original.email}</div>
        </div>
      ),
    },
    {
      id: 'status',
      header: intl.formatMessage(m.colStatus),
      cell: ({ row }) => (
        <Badge tone={statusTone[row.original.status]}>{intl.formatMessage(m[row.original.status])}</Badge>
      ),
    },
    {
      id: 'mfa',
      header: intl.formatMessage(m.colMfa),
      cell: ({ row }) => intl.formatMessage(row.original.mfaEnrolled ? m.mfaOn : m.mfaOff),
    },
    {
      id: 'lastLoginAt',
      header: intl.formatMessage(m.colLastSignIn),
      cell: ({ row }) =>
        row.original.lastLoginAt
          ? intl.formatDate(row.original.lastLoginAt, { dateStyle: 'medium', timeStyle: 'short' })
          : intl.formatMessage(m.never),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(m.colActions)}</span>,
      cell: ({ row }) => (
        <RowActions
          user={row.original}
          onAction={(action) => {
            setPending({ user: row.original, action })
          }}
        />
      ),
    },
  ]

  return (
    <section aria-labelledby="users-title">
      <PageHeader
        title={<span id="users-title">{intl.formatMessage(m.title)}</span>}
        description={intl.formatMessage(m.lead)}
        actions={
          <Button
            onClick={() => {
              setInviting(true)
            }}
          >
            <UserPlus aria-hidden="true" />
            {intl.formatMessage(m.invite)}
          </Button>
        }
      />
      <form role="search" onSubmit={apply} className="mb-4 flex flex-wrap items-end gap-3">
        <Field label={intl.formatMessage(m.search)} className="min-w-60 flex-1">
          {(p) => (
            <Input
              {...p}
              type="search"
              name="q"
              value={query}
              onChange={(e) => {
                setQuery(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.status)}>
          {(p) => (
            <Select
              {...p}
              name="status"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value as Status | '')
              }}
            >
              <option value="">{intl.formatMessage(m.all)}</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {intl.formatMessage(m[s])}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Button type="submit" variant="outline">
          {intl.formatMessage(m.apply)}
        </Button>
      </form>
      <DataTable
        caption={intl.formatMessage(m.caption)}
        columns={columns}
        rows={list.data?.items ?? []}
        getRowId={(u) => u.id}
        state={list.isPending ? 'loading' : list.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void list.refetch() }}
        empty={intl.formatMessage(m.empty)}
        paging={{
          hasPrevious: cursors.length > 1,
          hasNext: !!list.data?.nextCursor,
          onPrevious: () => {
            setCursors(cursors.slice(0, -1))
          },
          onNext: () => {
            if (list.data?.nextCursor) setCursors([...cursors, list.data.nextCursor])
          },
        }}
      />
      {pending ? (
        <ConfirmAction
          user={pending.user}
          action={pending.action}
          onClose={() => {
            setPending(undefined)
          }}
        />
      ) : null}
      <InviteDialog open={inviting} onOpenChange={setInviting} />
    </section>
  )
}

function RowActions({ user, onAction }: { user: AdminUser; onAction: (action: Action) => void }) {
  const intl = useIntl()
  const session = useSession()
  const self = session.data?.user?.id === user.id
  const actions: Action[] = [
    ...(user.status === 'DEACTIVATED' ? (['reactivate'] as const) : self ? [] : (['deactivate'] as const)),
    ...(user.status === 'LOCKED' ? (['unlock'] as const) : []),
    ...(user.mfaEnrolled && !self ? (['mfaReset'] as const) : []),
    ...(user.status !== 'DEACTIVATED' && !self ? (['revokeSessions'] as const) : []),
  ]
  if (actions.length === 0) return null
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={intl.formatMessage(m.actionsFor, { name: user.displayName })}>
          <MoreHorizontal aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        {actions.map((action) => (
          <DropdownMenuItem
            key={action}
            onSelect={() => {
              onAction(action)
            }}
          >
            {intl.formatMessage(m[action])}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

const confirmTitles = {
  deactivate: m.confirmDeactivate,
  reactivate: m.confirmReactivate,
  unlock: m.confirmUnlock,
  mfaReset: m.confirmMfaReset,
  revokeSessions: m.confirmRevokeSessions,
} as const

const explanations = {
  deactivate: m.explainDeactivate,
  reactivate: m.explainReactivate,
  unlock: m.explainUnlock,
  mfaReset: m.explainMfaReset,
  revokeSessions: m.explainRevokeSessions,
} as const

function ConfirmAction({ user, action, onClose }: { user: AdminUser; action: Action; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  // The dialog closes itself on confirm; this stays mounted until the action (and any step-up) has settled.
  const confirmed = useRef(false)
  const run = useMutation({
    mutationFn: () =>
      withStepUp(async () => {
        const path = { id: user.id }
        const headers = { 'If-Match': `"${String(user.version)}"` }
        switch (action) {
          case 'deactivate':
            return deactivateUser({ path, headers, throwOnError: true })
          case 'reactivate':
            return reactivateUser({ path, headers, throwOnError: true })
          case 'unlock':
            return unlockUser({ path, headers, throwOnError: true })
          case 'mfaReset':
            return resetUserMfa({ path, headers, throwOnError: true })
          case 'revokeSessions':
            return revokeUserSessions({ path, throwOnError: true })
        }
      }),
    onSuccess: () => {
      toast.show(intl.formatMessage(m.done))
    },
    onError: (error) => {
      const reason = isProblem(error) ? error.reason : undefined
      const status = isProblem(error) ? error.status : undefined
      const message =
        reason === 'SELF'
          ? m.self
          : reason === 'LAST_ADMIN'
            ? m.lastAdmin
            : reason === 'STATUS'
              ? m.wrongState
              : status === 412
                ? m.changed
                : m.failed
      toast.show(intl.formatMessage(message), 'error')
    },
    onSettled: async () => {
      onClose()
      await queryClient.invalidateQueries({ queryKey: listUsersQueryKey() })
    },
  })
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !confirmed.current) onClose()
      }}
      title={intl.formatMessage(confirmTitles[action], { name: user.displayName })}
      description={intl.formatMessage(explanations[action])}
      confirmLabel={intl.formatMessage(m[action])}
      cancelLabel={intl.formatMessage(m.cancel)}
      destructive={action === 'deactivate' || action === 'mfaReset' || action === 'revokeSessions'}
      onConfirm={() => {
        confirmed.current = true
        run.mutate()
      }}
    />
  )
}

function InviteDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const [email, setEmail] = useState('')
  const [name, setName] = useState('')
  const [admin, setAdmin] = useState(false)
  const [errors, setErrors] = useState<{ email?: string; displayName?: string }>({})
  // One key per invitation: a double click or a retry after a lost response creates the user once.
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)

  const close = () => {
    setEmail('')
    setName('')
    setAdmin(false)
    setErrors({})
    setKey(crypto.randomUUID())
    onOpenChange(false)
  }

  const submit = async (e: SyntheticEvent) => {
    e.preventDefault()
    const missing = {
      ...(email.trim() ? {} : { email: intl.formatMessage(m.required) }),
      ...(name.trim() ? {} : { displayName: intl.formatMessage(m.required) }),
    }
    setErrors(missing)
    if (Object.keys(missing).length > 0) return
    setBusy(true)
    try {
      await withStepUp(() =>
        inviteUser({
          body: { email: email.trim(), displayName: name.trim(), tenantAdmin: admin },
          headers: { 'Idempotency-Key': key },
          throwOnError: true,
        }),
      )
      toast.show(intl.formatMessage(m.sent, { email: email.trim() }))
      await queryClient.invalidateQueries({ queryKey: listUsersQueryKey() })
      close()
    } catch (err) {
      const taken = isProblem(err) && err.errors?.some((f) => f.field === 'email')
      if (taken) setErrors({ email: intl.formatMessage(m.taken) })
      else toast.show(intl.formatMessage(m.failed), 'error')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        if (!next) close()
      }}
      title={intl.formatMessage(m.inviteTitle)}
      description={intl.formatMessage(m.inviteLead)}
    >
      <form noValidate onSubmit={(e) => void submit(e)} className="flex flex-col gap-4">
        <Field label={intl.formatMessage(m.email)} required error={errors.email}>
          {(p) => (
            <Input
              {...p}
              name="email"
              type="email"
              autoComplete="off"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value)
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(m.name)} required error={errors.displayName}>
          {(p) => (
            <Input
              {...p}
              name="displayName"
              autoComplete="off"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
              }}
            />
          )}
        </Field>
        <label className="flex items-center gap-2 text-sm">
          <Checkbox
            checked={admin}
            onChange={(e) => {
              setAdmin(e.target.checked)
            }}
          />
          {intl.formatMessage(m.makeAdmin)}
        </label>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={close}>
            {intl.formatMessage(m.cancel)}
          </Button>
          <Button type="submit" disabled={busy}>
            {intl.formatMessage(m.send)}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
