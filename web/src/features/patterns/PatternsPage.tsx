import { useForm } from '@tanstack/react-form'
import type { SortingState } from '@tanstack/react-table'
import { useNavigate, useSearch } from '@tanstack/react-router'
import { Download, Plus } from 'lucide-react'
import { useMemo, useRef, useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { FormActions, FormGrid, UnsavedBanner, UnsavedChangesGuard } from '@/components/page/FormPage'
import { applyFieldErrors } from '@/components/page/problem-details'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { TreePanel, type TreeNode } from '@/components/page/TreePanel'
import { Badge, type Tone } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog, Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { useToast } from '@/components/ui/toast-context'

const m = defineMessages({
  title: { id: 'patterns.title', defaultMessage: 'Patterns' },
  description: {
    id: 'patterns.description',
    defaultMessage: 'Every page template and component with synthetic data. Development and test builds only.',
  },
  list: { id: 'patterns.list', defaultMessage: 'List page' },
  detail: { id: 'patterns.detail', defaultMessage: 'Detail page and tabs' },
  form: { id: 'patterns.form', defaultMessage: 'Form page' },
  tree: { id: 'patterns.tree', defaultMessage: 'Tree' },
  dialogs: { id: 'patterns.dialogs', defaultMessage: 'Dialogs' },
  states: { id: 'patterns.states', defaultMessage: 'States' },
  users: { id: 'patterns.users', defaultMessage: 'Users' },
  invite: { id: 'patterns.invite', defaultMessage: 'Invite user' },
  export: { id: 'patterns.export', defaultMessage: 'Export' },
  colName: { id: 'patterns.col.name', defaultMessage: 'Name' },
  colRole: { id: 'patterns.col.role', defaultMessage: 'Role' },
  colStatus: { id: 'patterns.col.status', defaultMessage: 'Status' },
  filter: { id: 'patterns.filter', defaultMessage: 'Filter' },
  filterHint: { id: 'patterns.filter.hint', defaultMessage: 'Name or email' },
  details: { id: 'patterns.tab.details', defaultMessage: 'Details' },
  history: { id: 'patterns.tab.history', defaultMessage: 'History' },
  unitName: { id: 'patterns.unit.name', defaultMessage: 'Name' },
  unitCode: { id: 'patterns.unit.code', defaultMessage: 'Code' },
  unitCodeHint: { id: 'patterns.unit.code.hint', defaultMessage: 'Used in reference numbers. 2–10 letters.' },
  unitParent: { id: 'patterns.unit.parent', defaultMessage: 'Parent unit' },
  unitDescription: { id: 'patterns.unit.description', defaultMessage: 'Description' },
  optional: { id: 'patterns.optional', defaultMessage: '(optional)' },
  required: { id: 'patterns.required', defaultMessage: 'Enter a value.' },
  save: { id: 'patterns.save', defaultMessage: 'Save changes' },
  discard: { id: 'patterns.discard', defaultMessage: 'Discard changes' },
  saved: { id: 'patterns.saved', defaultMessage: 'Changes saved' },
  simulateServer: { id: 'patterns.simulate', defaultMessage: 'Code "TAKEN" simulates a server validation error.' },
  codeTaken: { id: 'patterns.code.taken', defaultMessage: 'This code is already used by another unit.' },
  units: { id: 'patterns.units', defaultMessage: 'Units' },
  openDialog: { id: 'patterns.dialog.open', defaultMessage: 'Open dialog' },
  dialogTitle: { id: 'patterns.dialog.title', defaultMessage: 'Rename unit' },
  dialogDescription: {
    id: 'patterns.dialog.description',
    defaultMessage: 'The new name appears everywhere the unit is shown.',
  },
  cancel: { id: 'patterns.cancel', defaultMessage: 'Cancel' },
  rename: { id: 'patterns.rename', defaultMessage: 'Rename' },
  openConfirm: { id: 'patterns.confirm.open', defaultMessage: 'Deactivate user' },
  confirmTitle: { id: 'patterns.confirm.title', defaultMessage: 'Deactivate this user?' },
  confirmDescription: {
    id: 'patterns.confirm.description',
    defaultMessage: 'They will be signed out and cannot sign in until reactivated.',
  },
  deactivate: { id: 'patterns.deactivate', defaultMessage: 'Deactivate' },
  emptyTitle: { id: 'patterns.empty.title', defaultMessage: 'No users match these filters' },
  errorTitle: { id: 'patterns.error.title', defaultMessage: 'The list could not be loaded' },
})

// Messages with placeholders are typed (react-intl 12).
const pm = defineMessages<{ showing: { from: number; to: number }; selected: { name: string } }>({
  showing: { id: 'patterns.showing', defaultMessage: 'Rows {from}–{to}' },
  selected: { id: 'patterns.selected', defaultMessage: 'Selected: {name}' },
})

const statusTone: Record<string, Tone> = { Active: 'ok', Invited: 'warn', Locked: 'danger', Deactivated: 'neutral' }

interface DemoUser {
  id: string
  name: string
  email: string
  role: string
  status: string
}

const SURNAMES = ['Rahman', 'Okafor', 'Martins', 'Watanabe', 'Haddad', 'Herrera', 'Novak', 'Lindqvist']
const GIVEN = ['Amina', 'Daniel', 'Sofia', 'Kenji', 'Leila', 'Tomás', 'Ivana', 'Erik']
const ROLES = ['Tenant admin', 'Org admin', 'Auditor', 'User']
const STATUSES = ['Active', 'Invited', 'Locked', 'Deactivated']

/** 23 synthetic users (deterministic). */
const USERS: DemoUser[] = Array.from({ length: 23 }, (_, i) => {
  const given = GIVEN[i % GIVEN.length] ?? 'Ana'
  const surname = SURNAMES[(i * 3) % SURNAMES.length] ?? 'Doe'
  return {
    id: `u${String(i + 1)}`,
    name: `${given} ${surname}`,
    email: `${given.toLowerCase()}.${surname.toLowerCase()}@demo.example`.normalize('NFD').replace(/[̀-ͯ]/g, ''),
    role: ROLES[i % ROLES.length] ?? 'User',
    status: STATUSES[(i * 7) % STATUSES.length] ?? 'Active',
  }
})

const PAGE = 8

/** Synthetic org units (data, not UI text). */
const PARENTS = [
  { value: 'head-office', label: 'Head Office' },
  { value: 'branches', label: 'Branches' },
]

function ListSection() {
  const intl = useIntl()
  const [sorting, setSorting] = useState<SortingState>([])
  const [cursor, setCursor] = useState(0)
  const [filter, setFilter] = useState('')
  const rows = useMemo(() => {
    const q = filter.trim().toLowerCase()
    const filtered = USERS.filter((u) => !q || u.name.toLowerCase().includes(q) || u.email.includes(q))
    const sort = sorting[0]
    if (!sort) return filtered
    const key = sort.id as keyof DemoUser
    return [...filtered].sort((a, b) => a[key].localeCompare(b[key]) * (sort.desc ? -1 : 1))
  }, [filter, sorting])
  const page = rows.slice(cursor, cursor + PAGE)
  const columns: DataTableColumn<DemoUser>[] = [
    {
      id: 'name',
      accessorKey: 'name',
      header: intl.formatMessage(m.colName),
      cell: ({ row }) => (
        <div>
          <div className="font-medium">{row.original.name}</div>
          <div className="text-xs text-muted-foreground">{row.original.email}</div>
        </div>
      ),
    },
    { id: 'role', accessorKey: 'role', header: intl.formatMessage(m.colRole) },
    {
      id: 'status',
      accessorKey: 'status',
      header: intl.formatMessage(m.colStatus),
      cell: ({ row }) => <Badge tone={statusTone[row.original.status] ?? 'neutral'}>{row.original.status}</Badge>,
    },
  ]
  return (
    <section aria-labelledby="patterns-list" className="flex flex-col gap-4">
      <PageHeader
        title={<span id="patterns-list">{intl.formatMessage(m.users)}</span>}
        actions={
          <>
            <Button variant="outline">
              <Download aria-hidden="true" />
              {intl.formatMessage(m.export)}
            </Button>
            <Button>
              <Plus aria-hidden="true" />
              {intl.formatMessage(m.invite)}
            </Button>
          </>
        }
        className="mb-0"
      />
      <div className="max-w-sm">
        <Field label={intl.formatMessage(m.filter)} hint={intl.formatMessage(m.filterHint)}>
          {(p) => (
            <Input
              {...p}
              type="search"
              value={filter}
              onChange={(e) => {
                setFilter(e.target.value)
                setCursor(0)
              }}
            />
          )}
        </Field>
      </div>
      <DataTable
        caption={intl.formatMessage(m.users)}
        columns={columns}
        rows={page}
        getRowId={(u) => u.id}
        rowLabel={(u) => u.name}
        sorting={sorting}
        onSortingChange={(s) => {
          setSorting(s)
          setCursor(0)
        }}
        empty={<EmptyState title={intl.formatMessage(m.emptyTitle)} />}
        paging={{
          hasPrevious: cursor > 0,
          hasNext: cursor + PAGE < rows.length,
          onPrevious: () => {
            setCursor(Math.max(0, cursor - PAGE))
          },
          onNext: () => {
            setCursor(cursor + PAGE)
          },
          summary:
            page.length > 0
              ? intl.formatMessage(pm.showing, { from: cursor + 1, to: cursor + page.length })
              : undefined,
        }}
      />
    </section>
  )
}

function DetailSection() {
  const intl = useIntl()
  const search: { tab?: string } = useSearch({ strict: false })
  const navigate = useNavigate()
  const tab = search.tab === 'history' ? 'history' : 'details'
  return (
    <section aria-labelledby="patterns-detail" className="flex flex-col gap-3">
      <h2 id="patterns-detail" className="text-lg font-semibold">
        {intl.formatMessage(m.detail)}
      </h2>
      <Tabs value={tab} onValueChange={(value) => void navigate({ to: '.', search: { tab: value }, replace: true })}>
        <TabsList>
          <TabsTrigger value="details">{intl.formatMessage(m.details)}</TabsTrigger>
          <TabsTrigger value="history">{intl.formatMessage(m.history)}</TabsTrigger>
        </TabsList>
        <TabsContent value="details" className="py-3 text-sm">
          {intl.formatMessage(m.unitCodeHint)}
        </TabsContent>
        <TabsContent value="history" className="py-3 text-sm">
          {intl.formatMessage(m.simulateServer)}
        </TabsContent>
      </Tabs>
    </section>
  )
}

const UNIT_FIELDS = ['name', 'code', 'parent', 'description'] as const

function FormSection() {
  const intl = useIntl()
  const toast = useToast()
  const container = useRef<HTMLFormElement>(null)
  const form = useForm({
    defaultValues: { name: 'Credit Risk', code: 'CRD', parent: 'head-office', description: '' },
    onSubmit: async ({ value, formApi }) => {
      await Promise.resolve()
      if (value.code === 'TAKEN') {
        // Simulated server response: RFC 9457 Problem Details with field errors (E01 design §3).
        applyFieldErrors(
          {
            title: 'Validation failed',
            status: 400,
            errors: [{ field: 'code', message: intl.formatMessage(m.codeTaken) }],
          },
          UNIT_FIELDS,
          (field, message) => {
            formApi.setFieldMeta(field as (typeof UNIT_FIELDS)[number], (meta) => ({
              ...meta,
              errorMap: { ...meta.errorMap, onServer: message },
            }))
          },
          container.current,
        )
        return
      }
      formApi.reset(value)
      toast.show(intl.formatMessage(m.saved))
    },
  })
  const required = ({ value }: { value: string }) => (value.trim() ? undefined : intl.formatMessage(m.required))
  return (
    <section aria-labelledby="patterns-form" className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <h2 id="patterns-form" className="text-lg font-semibold">
          {intl.formatMessage(m.form)}
        </h2>
        <form.Subscribe selector={(s) => s.isDirty}>{(dirty) => <UnsavedBanner visible={dirty} />}</form.Subscribe>
      </div>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(m.simulateServer)}</p>
      <form
        ref={container}
        noValidate
        onSubmit={(e) => {
          e.preventDefault()
          void form.handleSubmit()
        }}
      >
        <FormGrid>
          <form.Field name="name" validators={{ onChange: required, onSubmit: required }}>
            {(field) => (
              <Field label={intl.formatMessage(m.unitName)} required error={field.state.meta.errors[0]?.toString()}>
                {(p) => (
                  <Input
                    {...p}
                    name={field.name}
                    value={field.state.value}
                    onBlur={field.handleBlur}
                    onChange={(e) => {
                      field.handleChange(e.target.value)
                    }}
                  />
                )}
              </Field>
            )}
          </form.Field>
          <form.Field name="code" validators={{ onChange: required, onSubmit: required }}>
            {(field) => (
              <Field
                label={intl.formatMessage(m.unitCode)}
                hint={intl.formatMessage(m.unitCodeHint)}
                required
                error={field.state.meta.errors[0]?.toString()}
              >
                {(p) => (
                  <Input
                    {...p}
                    name={field.name}
                    value={field.state.value}
                    onBlur={field.handleBlur}
                    onChange={(e) => {
                      field.handleChange(e.target.value)
                    }}
                  />
                )}
              </Field>
            )}
          </form.Field>
          <form.Field name="parent">
            {(field) => (
              <Field label={intl.formatMessage(m.unitParent)}>
                {(p) => (
                  <Select
                    {...p}
                    name={field.name}
                    value={field.state.value}
                    onChange={(e) => {
                      field.handleChange(e.target.value)
                    }}
                  >
                    {PARENTS.map((p) => (
                      <option key={p.value} value={p.value}>
                        {p.label}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
            )}
          </form.Field>
          <form.Field name="description">
            {(field) => (
              <Field
                label={intl.formatMessage(m.unitDescription)}
                optionalLabel={intl.formatMessage(m.optional)}
                className="md:col-span-2"
              >
                {(p) => (
                  <Textarea
                    {...p}
                    name={field.name}
                    rows={3}
                    value={field.state.value}
                    onChange={(e) => {
                      field.handleChange(e.target.value)
                    }}
                  />
                )}
              </Field>
            )}
          </form.Field>
        </FormGrid>
        <form.Subscribe selector={(s) => [s.isDirty, s.isSubmitting] as const}>
          {([dirty, submitting]) => (
            <>
              <UnsavedChangesGuard when={dirty && !submitting} />
              <FormActions>
                <Button
                  variant="outline"
                  disabled={!dirty}
                  onClick={() => {
                    form.reset()
                  }}
                >
                  {intl.formatMessage(m.discard)}
                </Button>
                <Button type="submit" disabled={submitting}>
                  {intl.formatMessage(m.save)}
                </Button>
              </FormActions>
            </>
          )}
        </form.Subscribe>
      </form>
    </section>
  )
}

const TREE: TreeNode[] = [
  {
    id: 'bank',
    label: 'Demo Bank Ltd',
    children: [
      {
        id: 'head-office',
        label: 'Head Office',
        children: [
          { id: 'finance', label: 'Finance' },
          {
            id: 'credit',
            label: 'Credit Risk',
            children: [
              { id: 'retail', label: 'Retail Credit' },
              { id: 'corporate', label: 'Corporate Credit' },
            ],
          },
          { id: 'operations', label: 'Operations' },
        ],
      },
      { id: 'branches', label: 'Branches', children: [{ id: 'north', label: 'North Region' }] },
    ],
  },
]

function TreeSection() {
  const intl = useIntl()
  const [selected, setSelected] = useState('credit')
  const name = useMemo(() => {
    const find = (nodes: TreeNode[]): string | undefined =>
      nodes.map((n) => (n.id === selected ? n.label : find(n.children ?? []))).find(Boolean)
    return find(TREE) ?? ''
  }, [selected])
  return (
    <section aria-labelledby="patterns-tree" className="flex flex-col gap-3">
      <h2 id="patterns-tree" className="text-lg font-semibold">
        {intl.formatMessage(m.tree)}
      </h2>
      <div className="grid gap-4 md:grid-cols-[18rem_1fr]">
        <div className="rounded-lg border bg-card p-2">
          <TreePanel
            label={intl.formatMessage(m.units)}
            nodes={TREE}
            selectedId={selected}
            onSelect={setSelected}
            defaultExpanded={['bank', 'head-office']}
          />
        </div>
        <p className="text-sm">{intl.formatMessage(pm.selected, { name })}</p>
      </div>
    </section>
  )
}

function DialogSection() {
  const intl = useIntl()
  const toast = useToast()
  const [dialogOpen, setDialogOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
  return (
    <section aria-labelledby="patterns-dialogs" className="flex flex-col gap-3">
      <h2 id="patterns-dialogs" className="text-lg font-semibold">
        {intl.formatMessage(m.dialogs)}
      </h2>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          onClick={() => {
            setDialogOpen(true)
          }}
        >
          {intl.formatMessage(m.openDialog)}
        </Button>
        <Button
          variant="destructive"
          onClick={() => {
            setConfirmOpen(true)
          }}
        >
          {intl.formatMessage(m.openConfirm)}
        </Button>
      </div>
      <Dialog
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        title={intl.formatMessage(m.dialogTitle)}
        description={intl.formatMessage(m.dialogDescription)}
        footer={
          <>
            <Button
              variant="outline"
              onClick={() => {
                setDialogOpen(false)
              }}
            >
              {intl.formatMessage(m.cancel)}
            </Button>
            <Button
              onClick={() => {
                setDialogOpen(false)
              }}
            >
              {intl.formatMessage(m.rename)}
            </Button>
          </>
        }
      >
        <Field label={intl.formatMessage(m.unitName)}>{(p) => <Input {...p} defaultValue="Credit Risk" />}</Field>
      </Dialog>
      <AlertDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={intl.formatMessage(m.confirmTitle)}
        description={intl.formatMessage(m.confirmDescription)}
        confirmLabel={intl.formatMessage(m.deactivate)}
        cancelLabel={intl.formatMessage(m.cancel)}
        destructive
        onConfirm={() => {
          toast.show(intl.formatMessage(m.saved))
        }}
      />
    </section>
  )
}

function StatesSection() {
  const intl = useIntl()
  return (
    <section aria-labelledby="patterns-states" className="flex flex-col gap-3">
      <h2 id="patterns-states" className="text-lg font-semibold">
        {intl.formatMessage(m.states)}
      </h2>
      <div className="grid gap-4 md:grid-cols-3">
        <div className="rounded-lg border bg-card">
          <EmptyState title={intl.formatMessage(m.emptyTitle)} />
        </div>
        <div className="rounded-lg border bg-card">
          <LoadingState rows={3} />
        </div>
        <div className="rounded-lg border bg-card">
          <ErrorState
            title={intl.formatMessage(m.errorTitle)}
            correlationId="5f0c2a2e-8d7b-4a6e-9c1f-2b3d4e5f6a7b"
            onRetry={() => undefined}
          />
        </div>
      </div>
    </section>
  )
}

/** Patterns gallery (E01 design §5): target of component-level e2e, axe and visual tests until feature screens exist. */
export function PatternsPage() {
  const intl = useIntl()
  return (
    <div className="flex flex-col gap-10">
      <PageHeader
        title={intl.formatMessage(m.title)}
        description={intl.formatMessage(m.description)}
        className="mb-0"
      />
      <ListSection />
      <DetailSection />
      <FormSection />
      <TreeSection />
      <DialogSection />
      <StatesSection />
    </div>
  )
}
