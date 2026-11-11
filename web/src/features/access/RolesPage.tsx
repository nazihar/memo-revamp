import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { listPermissionsOptions, listRolesOptions } from '@/api/generated/@tanstack/react-query.gen'
import { createRole, deleteRole, updateRole } from '@/api/generated/sdk.gen'
import type { Role } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Checkbox, Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FormDialog } from '@/features/org/OrgDialogs'
import { accessProblemMessage, am, catalogueLabel } from './access-messages'
import { newIdempotencyKey } from '@/lib/idempotency'

/** `/admin/roles` (design E04 §13): built-in and custom roles; custom roles are created, changed and removed here. */
export function RolesPage() {
  const intl = useIntl()
  const roles = useQuery(listRolesOptions())
  const [editing, setEditing] = useState<Role | 'new'>()
  const [removing, setRemoving] = useState<Role>()

  const columns: DataTableColumn<Role>[] = [
    {
      id: 'name',
      header: intl.formatMessage(am.colName),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2 font-medium">
          {row.original.builtIn ? catalogueLabel(intl, row.original.builtIn, row.original.name) : row.original.name}
          {row.original.builtIn ? <Badge tone="neutral">{intl.formatMessage(am.builtIn)}</Badge> : null}
        </span>
      ),
    },
    {
      id: 'permissions',
      header: intl.formatMessage(am.colPermissions),
      cell: ({ row }) => row.original.permissions.map((p) => catalogueLabel(intl, p)).join(', '),
    },
    { id: 'grants', header: intl.formatMessage(am.colHolders), cell: ({ row }) => row.original.grants },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(am.colActions)}</span>,
      cell: ({ row }) =>
        row.original.builtIn ? null : (
          <span className="flex justify-end gap-1">
            <Button
              variant="ghost"
              onClick={() => {
                setEditing(row.original)
              }}
            >
              {intl.formatMessage(am.editRole)}
            </Button>
            <Button
              variant="ghost"
              onClick={() => {
                setRemoving(row.original)
              }}
            >
              {intl.formatMessage(am.deleteRole)}
            </Button>
          </span>
        ),
    },
  ]

  return (
    <section aria-labelledby="roles-title">
      <PageHeader
        title={<span id="roles-title">{intl.formatMessage(am.rolesTitle)}</span>}
        description={intl.formatMessage(am.rolesLead)}
        actions={
          <Button
            onClick={() => {
              setEditing('new')
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(am.newRole)}
          </Button>
        }
      />
      <DataTable
        caption={intl.formatMessage(am.allRolesCaption)}
        columns={columns}
        rows={roles.data ?? []}
        getRowId={(r) => r.id}
        state={roles.isPending ? 'loading' : roles.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void roles.refetch() }}
        empty={intl.formatMessage(am.noRoles)}
      />
      {editing ? (
        <RoleDialog
          role={editing === 'new' ? undefined : editing}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      ) : null}
      {removing ? (
        <RemoveRoleDialog
          role={removing}
          onClose={() => {
            setRemoving(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function RoleDialog({ role, onClose }: { role?: Role; onClose: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const catalogue = useQuery(listPermissionsOptions())
  const [name, setName] = useState(role?.name ?? '')
  const [description, setDescription] = useState(role?.description ?? '')
  const [chosen, setChosen] = useState(new Set(role?.permissions ?? []))
  const [key] = useState(() => newIdempotencyKey())
  const save = async () => {
    const body = {
      name: name.trim(),
      ...(description.trim() ? { description: description.trim() } : {}),
      permissions: [...chosen],
    }
    await withStepUp(() =>
      role
        ? updateRole({
            path: { id: role.id },
            headers: { 'If-Match': `"${String(role.version)}"` },
            body,
            throwOnError: true,
          })
        : createRole({ headers: { 'Idempotency-Key': key }, body, throwOnError: true }),
    )
    await queryClient.invalidateQueries()
  }
  return (
    <FormDialog
      title={intl.formatMessage(role ? am.editRole : am.newRole)}
      submitLabel={intl.formatMessage(am.save)}
      valid={name.trim().length > 0 && chosen.size > 0}
      problemMessage={(error) => accessProblemMessage(intl, error)}
      onSubmit={save}
      onClose={onClose}
    >
      <Field label={intl.formatMessage(am.name)} required>
        {(p) => (
          <Input
            {...p}
            value={name}
            autoComplete="off"
            onChange={(e) => {
              setName(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={intl.formatMessage(am.description)}>
        {(p) => (
          <Input
            {...p}
            value={description}
            autoComplete="off"
            onChange={(e) => {
              setDescription(e.target.value)
            }}
          />
        )}
      </Field>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(am.permissions)}</legend>
        {(catalogue.data ?? []).map((p) => (
          <label key={p.key} className="flex items-start gap-2 text-sm">
            <Checkbox
              checked={chosen.has(p.key)}
              onChange={(e) => {
                const next = new Set(chosen)
                if (e.target.checked) next.add(p.key)
                else next.delete(p.key)
                setChosen(next)
              }}
            />
            <span>
              {catalogueLabel(intl, p.key)}
              {p.privileged ? (
                <span className="block text-xs text-muted-foreground">{intl.formatMessage(am.privileged)}</span>
              ) : null}
              {p.scopable ? (
                <span className="block text-xs text-muted-foreground">{intl.formatMessage(am.scopable)}</span>
              ) : null}
            </span>
          </label>
        ))}
      </fieldset>
    </FormDialog>
  )
}

function RemoveRoleDialog({ role, onClose }: { role: Role; onClose: () => void }) {
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
      title={intl.formatMessage(am.deleteRoleTitle)}
      description={intl.formatMessage(am.deleteRoleLead)}
      confirmLabel={intl.formatMessage(am.deleteRole)}
      cancelLabel={intl.formatMessage(am.keep)}
      destructive
      onConfirm={() => {
        confirmed.current = true
        withStepUp(() =>
          deleteRole({
            path: { id: role.id },
            headers: { 'If-Match': `"${String(role.version)}"` },
            throwOnError: true,
          }),
        )
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
