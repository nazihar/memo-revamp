import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from '@tanstack/react-router'
import { Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  getOrgUnitOptions,
  listLegalEntitiesOptions,
  listOrgUnitsOptions,
  listUnitPositionsOptions,
} from '@/api/generated/@tanstack/react-query.gen'
import {
  closeOrgUnit,
  createLegalEntity,
  createOrgUnit,
  createPosition,
  moveOrgUnit,
  updateOrgUnit,
} from '@/api/generated/sdk.gen'
import type { OrgPositionWithHolder, OrgUnit, OrgUnitDetail } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { TreePanel, type TreeNode } from '@/components/page/TreePanel'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/form-controls'
import { Breadcrumbs } from '@/components/ui/misc'
import { holds, PERMISSION, useMyAccess } from '@/features/access/access'
import { HistoryList } from './HistoryList'
import { DateDialog, MoveUnitDialog, PositionFormDialog, UnitFormDialog } from './OrgDialogs'
import { om, omv } from './org-messages'

type Pending = 'addUnit' | 'addRoot' | 'addEntity' | 'rename' | 'move' | 'close' | 'addPosition'

function buildTree(units: OrgUnit[]): TreeNode[] {
  const children = new Map<string | null, OrgUnit[]>()
  for (const u of units) children.set(u.parentId ?? null, [...(children.get(u.parentId ?? null) ?? []), u])
  const known = new Set(units.map((u) => u.id))
  const node = (u: OrgUnit): TreeNode => ({ id: u.id, label: u.name, children: (children.get(u.id) ?? []).map(node) })
  // Roots: units without a parent, or whose parent is not in the list (closed, or outside the requested date).
  return units.filter((u) => !u.parentId || !known.has(u.parentId)).map(node)
}

/** `/org` (design E04 §13): the units tree, and for the selected unit its path, positions, holders and actions. */
export function OrgPage({ selected }: { selected?: string }) {
  const intl = useIntl()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const units = useQuery(listOrgUnitsOptions())
  const entities = useQuery(listLegalEntitiesOptions())
  const access = useMyAccess()
  const [filter, setFilter] = useState('')
  const [pending, setPending] = useState<Pending>()
  const tree = useMemo(() => buildTree(units.data ?? []), [units.data])
  // The roots and every ancestor of the selected unit start open, so the selection is always in view.
  const expanded = useMemo(() => {
    const byId = new Map((units.data ?? []).map((u) => [u.id, u]))
    const open = tree.map((n) => n.id)
    for (let u = selected ? byId.get(selected) : undefined; u?.parentId; u = byId.get(u.parentId)) open.push(u.parentId)
    return open
  }, [tree, units.data, selected])
  const tenantWideManage = access.data?.permissions.some((p) => p.key === PERMISSION.orgManage && p.tenantWide) ?? false
  const select = (id: string) => void navigate({ to: '/org', search: { unit: id } })
  const refresh = () => queryClient.invalidateQueries()

  const matches = filter.trim()
    ? (units.data ?? []).filter((u) =>
        `${u.name} ${u.code}`.toLocaleLowerCase().includes(filter.trim().toLocaleLowerCase()),
      )
    : undefined
  const entity = entities.data?.[0]

  const headerActions = (
    <>
      {!entity && holds(access.data, PERMISSION.legalEntitiesManage) ? (
        <Button
          onClick={() => {
            setPending('addEntity')
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(om.addEntity)}
        </Button>
      ) : null}
      {entity && tenantWideManage ? (
        <Button
          variant="outline"
          onClick={() => {
            setPending('addRoot')
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(om.addRoot)}
        </Button>
      ) : null}
    </>
  )

  return (
    <section aria-labelledby="org-title">
      <PageHeader
        title={<span id="org-title">{intl.formatMessage(om.title)}</span>}
        description={intl.formatMessage(om.lead)}
        actions={headerActions}
      />
      {units.isPending ? (
        <LoadingState />
      ) : units.isError ? (
        <ErrorState onRetry={() => void units.refetch()} />
      ) : tree.length === 0 ? (
        <EmptyState title={intl.formatMessage(om.empty)} description={intl.formatMessage(om.emptyLead)} />
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(16rem,22rem)_1fr]">
          <aside className="rounded-md border p-3">
            <Input
              type="search"
              aria-label={intl.formatMessage(om.filter)}
              placeholder={intl.formatMessage(om.filter)}
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value)
              }}
              className="mb-3"
            />
            {matches ? (
              matches.length === 0 ? (
                <p className="px-2 text-sm text-muted-foreground">{intl.formatMessage(om.noMatch)}</p>
              ) : (
                <ul className="flex flex-col gap-0.5">
                  {matches.map((u) => (
                    <li key={u.id}>
                      <button
                        type="button"
                        onClick={() => {
                          select(u.id)
                        }}
                        className="w-full rounded-md px-2 py-1.5 text-start text-sm hover:bg-accent/60"
                      >
                        {intl.formatMessage(omv.unitWithCode, { name: u.name, code: u.code })}
                      </button>
                    </li>
                  ))}
                </ul>
              )
            ) : (
              <TreePanel
                key={selected}
                label={intl.formatMessage(om.treeLabel)}
                nodes={tree}
                selectedId={selected}
                onSelect={select}
                defaultExpanded={expanded}
              />
            )}
          </aside>
          <div className="min-w-0">
            {selected ? (
              <UnitPanel id={selected} onAction={setPending} />
            ) : (
              <p className="text-sm text-muted-foreground">{intl.formatMessage(om.pickUnit)}</p>
            )}
          </div>
        </div>
      )}
      {pending ? (
        <UnitActions
          pending={pending}
          selected={selected}
          units={units.data ?? []}
          entityId={entity?.id}
          onClose={() => {
            setPending(undefined)
          }}
          onDone={refresh}
          onCreated={select}
        />
      ) : null}
    </section>
  )
}

function UnitPanel({ id, onAction }: { id: string; onAction: (action: Pending) => void }) {
  const intl = useIntl()
  const detail = useQuery(getOrgUnitOptions({ path: { id } }))
  const positions = useQuery(listUnitPositionsOptions({ path: { id } }))
  if (detail.isPending) return <LoadingState rows={3} />
  if (detail.isError) return <ErrorState onRetry={() => void detail.refetch()} />
  const { unit, path, canManage } = detail.data

  const columns: DataTableColumn<OrgPositionWithHolder>[] = [
    {
      id: 'position',
      header: intl.formatMessage(om.colPosition),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <Link
            to="/org/positions/$positionId"
            params={{ positionId: row.original.position.id }}
            className="font-medium hover:underline"
          >
            {row.original.position.title}
          </Link>
          {row.original.position.head ? <Badge tone="neutral">{intl.formatMessage(om.head)}</Badge> : null}
        </span>
      ),
    },
    {
      id: 'holder',
      header: intl.formatMessage(om.colHolder),
      cell: ({ row }) =>
        row.original.holder ? (
          row.original.holder.displayName
        ) : (
          <Badge tone="warn">{intl.formatMessage(om.vacant)}</Badge>
        ),
    },
    {
      id: 'since',
      header: intl.formatMessage(om.colSince),
      cell: ({ row }) =>
        row.original.holderSince ? intl.formatDate(row.original.holderSince, { dateStyle: 'medium' }) : '–',
    },
  ]

  return (
    <article aria-labelledby="unit-title">
      <Breadcrumbs
        className="mb-2"
        label={intl.formatMessage(om.unitPath)}
        items={[...path.map((p) => ({ label: p.name, href: `/org?unit=${p.id}` })), { label: unit.name }]}
      />
      <UnitHeader detail={detail.data} canManage={canManage} onAction={onAction} />
      <h3 className="mb-2 mt-6 text-base font-semibold">{intl.formatMessage(om.positions)}</h3>
      <DataTable
        caption={intl.formatMessage(om.positionsCaption)}
        columns={columns}
        rows={positions.data ?? []}
        getRowId={(p) => p.position.id}
        state={positions.isPending ? 'loading' : positions.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void positions.refetch() }}
        empty={intl.formatMessage(om.noPositions)}
      />
      <HistoryList type="unit" id={unit.id} />
    </article>
  )
}

function UnitHeader({
  detail,
  canManage,
  onAction,
}: {
  detail: OrgUnitDetail
  canManage: boolean
  onAction: (action: Pending) => void
}) {
  const intl = useIntl()
  const { unit, path } = detail
  const action = (key: Pending, label: string, variant: 'outline' | 'default' = 'outline') => (
    <Button
      variant={variant}
      onClick={() => {
        onAction(key)
      }}
    >
      {label}
    </Button>
  )
  return (
    <div className="flex flex-wrap items-end gap-3">
      <div className="min-w-0 flex-[1_1_16rem]">
        <h2 id="unit-title" className="flex flex-wrap items-center gap-2 text-lg font-semibold">
          {unit.name}
          <Badge tone="neutral">{unit.code}</Badge>
        </h2>
        <p className="text-sm text-muted-foreground">
          {unit.validTo
            ? intl.formatMessage(omv.closedOn, { date: intl.formatDate(unit.validTo, { dateStyle: 'medium' }) })
            : intl.formatMessage(omv.since, { date: intl.formatDate(unit.validFrom, { dateStyle: 'medium' }) })}
        </p>
      </div>
      {canManage && !unit.validTo ? (
        <div className="flex flex-wrap gap-2">
          {action('addPosition', intl.formatMessage(om.addPosition), 'default')}
          {action('addUnit', intl.formatMessage(om.addUnit))}
          {action('rename', intl.formatMessage(om.rename))}
          {path.length > 0 ? action('move', intl.formatMessage(om.move)) : null}
          {action('close', intl.formatMessage(om.close))}
        </div>
      ) : null}
    </div>
  )
}

function UnitActions({
  pending,
  selected,
  units,
  entityId,
  onClose,
  onDone,
  onCreated,
}: {
  pending: Pending
  selected?: string
  units: OrgUnit[]
  entityId?: string
  onClose: () => void
  onDone: () => Promise<unknown>
  onCreated: (id: string) => void
}) {
  const intl = useIntl()
  const unit = units.find((u) => u.id === selected)
  const ifMatch = unit ? { 'If-Match': `"${String(unit.version)}"` } : undefined
  // One key per dialog: a double click or a retry after a lost response creates the object once.
  const [key] = useState(() => ({ 'Idempotency-Key': crypto.randomUUID() }))
  const after = async <T,>(call: Promise<T>) => {
    const result = await call
    await onDone()
    return result
  }

  switch (pending) {
    case 'addEntity':
      return (
        <UnitFormDialog
          title={intl.formatMessage(om.addEntity)}
          withCode={false}
          onSave={({ name }) => after(createLegalEntity({ body: { name }, headers: key, throwOnError: true }))}
          onClose={onClose}
        />
      )
    case 'addRoot':
    case 'addUnit': {
      const parent = pending === 'addUnit' ? unit : undefined
      const legalEntityId = parent?.legalEntityId ?? entityId
      if (!legalEntityId) return null
      return (
        <UnitFormDialog
          title={
            parent ? intl.formatMessage(omv.newUnitUnder, { parent: parent.name }) : intl.formatMessage(om.newTopUnit)
          }
          onSave={async ({ code, name }) => {
            const { data } = await after(
              createOrgUnit({
                body: { legalEntityId, ...(parent ? { parentId: parent.id } : {}), code, name },
                headers: key,
                throwOnError: true,
              }),
            )
            onCreated(data.id)
          }}
          onClose={onClose}
        />
      )
    }
    case 'rename':
      if (!unit || !ifMatch) return null
      return (
        <UnitFormDialog
          title={intl.formatMessage(om.rename)}
          initial={{ code: unit.code, name: unit.name }}
          onSave={(body) => after(updateOrgUnit({ path: { id: unit.id }, headers: ifMatch, body, throwOnError: true }))}
          onClose={onClose}
        />
      )
    case 'move':
      if (!unit || !ifMatch) return null
      return (
        <MoveUnitDialog
          unit={unit}
          units={units}
          onSave={(parentId) =>
            after(moveOrgUnit({ path: { id: unit.id }, headers: ifMatch, body: { parentId }, throwOnError: true }))
          }
          onClose={onClose}
        />
      )
    case 'close':
      if (!unit || !ifMatch) return null
      return (
        <DateDialog
          title={intl.formatMessage(omv.closeTitle, { name: unit.name })}
          description={intl.formatMessage(om.closeLead)}
          label={intl.formatMessage(om.closeDate)}
          submitLabel={intl.formatMessage(om.close)}
          onSave={(validTo) =>
            after(closeOrgUnit({ path: { id: unit.id }, headers: ifMatch, body: { validTo }, throwOnError: true }))
          }
          onClose={onClose}
        />
      )
    case 'addPosition':
      if (!unit) return null
      return (
        <PositionFormDialog
          title={intl.formatMessage(omv.newPosition, { unit: unit.name })}
          onSave={(body) =>
            after(createPosition({ body: { ...body, unitId: unit.id }, headers: key, throwOnError: true }))
          }
          onClose={onClose}
        />
      )
  }
}
