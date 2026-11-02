import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getUserAccessOptions,
  getUserOptions,
  listClassificationLevelsOptions,
  listOrgUnitsOptions,
  listPermissionsOptions,
  listRolesOptions,
  listUserAssignmentsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import { grantRole, revokeGrant, setClearance } from '@/api/generated/sdk.gen'
import type { Grant, OrgUserPosition } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FormDialog } from '@/features/org/OrgDialogs'
import { accessProblemMessage, am, catalogueLabel } from './access-messages'

/** `/admin/users/$userId` (design E04 §13): a person's positions, role grants and clearance. */
export function UserDetailPage({ userId }: { userId: string }) {
  const intl = useIntl()
  const user = useQuery(getUserOptions({ path: { id: userId } }))
  const assignments = useQuery(listUserAssignmentsOptions({ path: { id: userId } }))
  const access = useQuery(getUserAccessOptions({ path: { id: userId } }))
  const units = useQuery(listOrgUnitsOptions())
  const [granting, setGranting] = useState(false)
  const [revoking, setRevoking] = useState<Grant>()
  if (user.isPending) return <LoadingState />
  if (user.isError) return <ErrorState onRetry={() => void user.refetch()} />
  const unitName = (id?: string | null) =>
    id ? (units.data?.find((u) => u.id === id)?.name ?? id) : intl.formatMessage(am.wholeOrganisation)

  const positionColumns: DataTableColumn<OrgUserPosition>[] = [
    {
      id: 'position',
      header: intl.formatMessage(am.colPosition),
      cell: ({ row }) => (
        <span>
          <Link
            to="/org/positions/$positionId"
            params={{ positionId: row.original.position.id }}
            className="font-medium hover:underline"
          >
            {row.original.position.title}
          </Link>
          <span className="block text-xs text-muted-foreground">{row.original.unitName}</span>
        </span>
      ),
    },
    {
      id: 'from',
      header: intl.formatMessage(am.colFrom),
      cell: ({ row }) => intl.formatDate(row.original.validFrom, { dateStyle: 'medium' }),
    },
    {
      id: 'until',
      header: intl.formatMessage(am.colUntil),
      cell: ({ row }) =>
        row.original.validTo
          ? intl.formatDate(row.original.validTo, { dateStyle: 'medium' })
          : intl.formatMessage(am.ongoing),
    },
  ]
  const grantColumns: DataTableColumn<Grant>[] = [
    {
      id: 'role',
      header: intl.formatMessage(am.colRole),
      cell: ({ row }) => (row.original.builtIn ? catalogueLabel(intl, row.original.builtIn) : row.original.roleName),
    },
    { id: 'scope', header: intl.formatMessage(am.colScope), cell: ({ row }) => unitName(row.original.scopeUnitId) },
    {
      id: 'granted',
      header: intl.formatMessage(am.colGranted),
      cell: ({ row }) => intl.formatDate(row.original.grantedAt, { dateStyle: 'medium' }),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(am.colActions)}</span>,
      cell: ({ row }) => (
        <Button
          variant="ghost"
          onClick={() => {
            setRevoking(row.original)
          }}
        >
          {intl.formatMessage(am.revoke)}
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby="user-title">
      <PageHeader
        title={<span id="user-title">{user.data.displayName}</span>}
        description={`${user.data.email} · ${intl.formatMessage(am.userLead)}`}
      />
      <h2 className="mb-2 text-base font-semibold">{intl.formatMessage(am.positions)}</h2>
      <DataTable
        caption={intl.formatMessage(am.positionsCaption)}
        columns={positionColumns}
        rows={assignments.data?.filter((a) => a.status === 'ACTIVE') ?? []}
        getRowId={(a) => a.id}
        state={assignments.isPending ? 'loading' : assignments.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void assignments.refetch() }}
        empty={intl.formatMessage(am.noPositions)}
      />
      <div className="mb-2 mt-6 flex flex-wrap items-center gap-3">
        <h2 className="text-base font-semibold">{intl.formatMessage(am.roles)}</h2>
        <Button
          variant="outline"
          className="ms-auto"
          onClick={() => {
            setGranting(true)
          }}
        >
          {intl.formatMessage(am.grant)}
        </Button>
      </div>
      <DataTable
        caption={intl.formatMessage(am.rolesCaption)}
        columns={grantColumns}
        rows={access.data?.grants ?? []}
        getRowId={(g) => g.id}
        state={access.isPending ? 'loading' : access.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void access.refetch() }}
        empty={intl.formatMessage(am.noRoles)}
      />
      {access.data ? <ClearanceForm userId={userId} current={access.data.clearance.id} /> : null}
      {granting ? (
        <GrantDialog
          userId={userId}
          onClose={() => {
            setGranting(false)
          }}
        />
      ) : null}
      {revoking ? (
        <RevokeDialog
          grant={revoking}
          onClose={() => {
            setRevoking(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function GrantDialog({ userId, onClose }: { userId: string; onClose: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const roles = useQuery(listRolesOptions())
  const units = useQuery(listOrgUnitsOptions())
  const [roleId, setRoleId] = useState('')
  const [scope, setScope] = useState('')
  const permissions = useQuery(listPermissionsOptions())
  const role = roles.data?.find((r) => r.id === roleId)
  const scopableKeys = new Set((permissions.data ?? []).filter((p) => p.scopable).map((p) => p.key))
  const scopable = role?.permissions.some((p) => scopableKeys.has(p)) ?? false
  const choices = (roles.data ?? []).filter((r) => r.builtIn !== 'user')
  return (
    <FormDialog
      title={intl.formatMessage(am.grantTitle)}
      submitLabel={intl.formatMessage(am.grant)}
      valid={roleId !== ''}
      problemMessage={(error) => accessProblemMessage(intl, error)}
      onSubmit={async () => {
        await withStepUp(() =>
          grantRole({
            path: { id: userId },
            body: { roleId, ...(scopable && scope ? { scopeUnitId: scope } : {}) },
            throwOnError: true,
          }),
        )
        await queryClient.invalidateQueries()
      }}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(am.role)} required>
        {(p) => (
          <Select
            {...p}
            value={roleId}
            onChange={(e) => {
              setRoleId(e.target.value)
            }}
          >
            <option value="" />
            {choices.map((r) => (
              <option key={r.id} value={r.id}>
                {r.builtIn ? catalogueLabel(intl, r.builtIn, r.name) : r.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <Field label={intl.formatMessage(am.scope)} hint={intl.formatMessage(am.scopeHint)}>
        {(p) => (
          <Select
            {...p}
            value={scope}
            disabled={!scopable}
            onChange={(e) => {
              setScope(e.target.value)
            }}
          >
            <option value="">{intl.formatMessage(am.wholeOrganisation)}</option>
            {(units.data ?? []).map((u) => (
              <option key={u.id} value={u.id}>
                {u.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </FormDialog>
  )
}

function RevokeDialog({ grant, onClose }: { grant: Grant; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const confirmed = useRef(false)
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !confirmed.current) onClose()
      }}
      title={intl.formatMessage(am.revokeTitle)}
      description={intl.formatMessage(am.revokeLead)}
      confirmLabel={intl.formatMessage(am.revoke)}
      cancelLabel={intl.formatMessage(am.keep)}
      destructive
      onConfirm={() => {
        confirmed.current = true
        withStepUp(() => revokeGrant({ path: { id: grant.id }, throwOnError: true }))
          .then(async () => {
            toast.show(intl.formatMessage(am.saved))
            await queryClient.invalidateQueries()
          })
          .catch((error: unknown) => {
            toast.show(accessProblemMessage(intl, error), 'error')
          })
          .finally(onClose)
      }}
    />
  )
}

function ClearanceForm({ userId, current }: { userId: string; current: string }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const levels = useQuery(listClassificationLevelsOptions())
  const [level, setLevel] = useState(current)
  const [busy, setBusy] = useState(false)
  const save = async () => {
    setBusy(true)
    try {
      await withStepUp(() => setClearance({ path: { id: userId }, body: { levelId: level }, throwOnError: true }))
      toast.show(intl.formatMessage(am.saved))
      await queryClient.invalidateQueries()
    } catch (error) {
      toast.show(accessProblemMessage(intl, error), 'error')
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="mt-6 rounded-md border p-4">
      <h2 className="text-base font-semibold">{intl.formatMessage(am.clearance)}</h2>
      <p className="mb-3 text-sm text-muted-foreground">{intl.formatMessage(am.clearanceLead)}</p>
      <div className="flex flex-wrap items-end gap-3">
        <Field label={intl.formatMessage(am.clearance)} className="min-w-60">
          {(p) => (
            <Select
              {...p}
              value={level}
              onChange={(e) => {
                setLevel(e.target.value)
              }}
            >
              {(levels.data ?? []).map((l) => (
                <option key={l.id} value={l.id}>
                  {l.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Button disabled={busy || level === current} onClick={() => void save()}>
          {intl.formatMessage(am.saveClearance)}
        </Button>
      </div>
    </div>
  )
}
