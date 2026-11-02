import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Plus } from 'lucide-react'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import {
  createClassificationLevel,
  deleteClassificationLevel,
  renameClassificationLevel,
  reorderClassificationLevels,
} from '@/api/generated/sdk.gen'
import type { ClassificationLevel } from '@/api/generated/types.gen'
import { DataTable, type DataTableColumn } from '@/components/page/DataTable'
import { PageHeader } from '@/components/page/PageHeader'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { useWithStepUp } from '@/features/auth/step-up-context'
import { FormDialog } from '@/features/org/OrgDialogs'
import { accessProblemMessage, am, amv } from './access-messages'

/** `/admin/classifications` (design E04 §13): ordered levels; add, rename, reorder, and remove unused ones. */
export function ClassificationsPage() {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const levels = useQuery(listClassificationLevelsOptions())
  const [editing, setEditing] = useState<ClassificationLevel | 'new'>()
  const [removing, setRemoving] = useState<ClassificationLevel>()
  const rows = levels.data ?? []

  const move = async (index: number, by: number) => {
    const order = rows.map((l) => l.id)
    const [moved] = order.splice(index, 1)
    if (!moved) return
    order.splice(index + by, 0, moved)
    try {
      await withStepUp(() => reorderClassificationLevels({ body: { levelIds: order }, throwOnError: true }))
      toast.show(intl.formatMessage(am.saved))
    } catch (error) {
      toast.show(accessProblemMessage(intl, error), 'error')
    } finally {
      await queryClient.invalidateQueries()
    }
  }

  const columns: DataTableColumn<ClassificationLevel>[] = [
    { id: 'rank', header: intl.formatMessage(am.colRank), cell: ({ row }) => row.original.rank + 1 },
    {
      id: 'name',
      header: intl.formatMessage(am.colName),
      cell: ({ row }) => (
        <span>
          <span className="font-medium">{row.original.name}</span>
          {row.original.description ? (
            <span className="block text-xs text-muted-foreground">{row.original.description}</span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{intl.formatMessage(am.colActions)}</span>,
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-1">
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(amv.moveUpFor, { name: row.original.name })}
            disabled={row.index === rows.length - 1}
            onClick={() => void move(row.index, 1)}
          >
            <ArrowUp aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(amv.moveDownFor, { name: row.original.name })}
            disabled={row.index === 0}
            onClick={() => void move(row.index, -1)}
          >
            <ArrowDown aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setEditing(row.original)
            }}
          >
            {intl.formatMessage(am.renameLevel)}
          </Button>
          <Button
            variant="ghost"
            onClick={() => {
              setRemoving(row.original)
            }}
          >
            {intl.formatMessage(am.removeLevel)}
          </Button>
        </span>
      ),
    },
  ]

  return (
    <section aria-labelledby="levels-title">
      <PageHeader
        title={<span id="levels-title">{intl.formatMessage(am.levelsTitle)}</span>}
        description={intl.formatMessage(am.levelsLead)}
        actions={
          <Button
            onClick={() => {
              setEditing('new')
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(am.newLevel)}
          </Button>
        }
      />
      <DataTable
        caption={intl.formatMessage(am.levelsCaption)}
        columns={columns}
        rows={rows}
        getRowId={(l) => l.id}
        state={levels.isPending ? 'loading' : levels.isError ? 'error' : 'ready'}
        error={{ onRetry: () => void levels.refetch() }}
        empty={intl.formatMessage(am.noLevels)}
      />
      {editing ? (
        <LevelDialog
          level={editing === 'new' ? undefined : editing}
          onClose={() => {
            setEditing(undefined)
          }}
        />
      ) : null}
      {removing ? (
        <RemoveLevelDialog
          level={removing}
          onClose={() => {
            setRemoving(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function LevelDialog({ level, onClose }: { level?: ClassificationLevel; onClose: () => void }) {
  const intl = useIntl()
  const queryClient = useQueryClient()
  const withStepUp = useWithStepUp()
  const [name, setName] = useState(level?.name ?? '')
  const [description, setDescription] = useState(level?.description ?? '')
  const [key] = useState(() => crypto.randomUUID())
  const save = async () => {
    const body = { name: name.trim(), ...(description.trim() ? { description: description.trim() } : {}) }
    await withStepUp(() =>
      level
        ? renameClassificationLevel({
            path: { id: level.id },
            headers: { 'If-Match': `"${String(level.version)}"` },
            body,
            throwOnError: true,
          })
        : createClassificationLevel({ headers: { 'Idempotency-Key': key }, body, throwOnError: true }),
    )
    await queryClient.invalidateQueries()
  }
  return (
    <FormDialog
      title={intl.formatMessage(level ? am.renameLevel : am.newLevel)}
      submitLabel={intl.formatMessage(am.save)}
      valid={name.trim().length > 0}
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
    </FormDialog>
  )
}

function RemoveLevelDialog({ level, onClose }: { level: ClassificationLevel; onClose: () => void }) {
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
      title={intl.formatMessage(am.removeLevelTitle)}
      description={intl.formatMessage(am.removeLevelLead)}
      confirmLabel={intl.formatMessage(am.removeLevel)}
      cancelLabel={intl.formatMessage(am.keep)}
      destructive
      onConfirm={() => {
        confirmed.current = true
        withStepUp(() => deleteClassificationLevel({ path: { id: level.id }, throwOnError: true }))
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
