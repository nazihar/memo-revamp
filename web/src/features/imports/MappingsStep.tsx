import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import { listImportMappingsOptions, listImportMappingsQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { saveImportMappings } from '@/api/generated/sdk.gen'
import type {
  ImportBatchView,
  ImportMappingRequest,
  ImportMappingsView,
  ImportMappingTargetView,
  ImportMappingView,
} from '@/api/generated/types.gen'
import { isProblem, type Problem } from '@/api/http'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Checkbox, Select } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { useLocale } from '@/i18n/LocaleProvider'
import { directionOf } from '@/i18n/locales'
import { useToast } from '@/components/ui/toast-context'
import { statusLabels } from '@/features/memo/memo-messages'
import { PeoplePicker } from '@/features/org/PeoplePicker'
import { answerText } from './codes'
import { inLegacyOrder, moved, orderBreak } from './mapping-order'
import { im, imv } from './messages'
import { TableScroll } from '@/components/page/TableScroll'

type MappingKind = ImportMappingView['kind']
const KINDS: MappingKind[] = ['STATUS', 'TYPE', 'UNIT', 'CLASSIFICATION', 'PERSON']
const kindLabels = {
  STATUS: im.kindSTATUS,
  TYPE: im.kindTYPE,
  UNIT: im.kindUNIT,
  CLASSIFICATION: im.kindCLASSIFICATION,
  PERSON: im.kindPERSON,
} as const
/** What a legacy status can become (design E06 §6.2). */
const STATUS_TARGETS = ['APPROVED', 'REJECTED', 'WITHDRAWN', 'CANCELLED', 'CLOSED', 'NOT_IMPORTED'] as const

interface Draft {
  legacyValue: string
  rowCount: number
  proposed: boolean
  target: string | null
  confirmed: boolean
  legacyOrder: number | null
}

function draftOf(rows: ImportMappingView[]): Draft[] {
  return rows.map((r) => ({
    legacyValue: r.legacyValue,
    rowCount: r.rowCount,
    proposed: r.proposed,
    target: r.target ?? null,
    confirmed: r.confirmed,
    legacyOrder: r.legacyOrder ?? null,
  }))
}

/**
 * Step 3, Mappings (ux-review M1): a tab per kind, each legacy value with its row count, the proposal, the target and
 * whether it is confirmed; "Confirm all proposals"; the classification order with Up and Down buttons and the rule
 * checked before saving; people linked through the people picker. Saving sends the batch back to "needs a dry run".
 */
export function MappingsStep({ batch, onChanged }: { batch: ImportBatchView; onChanged: () => void }) {
  const intl = useIntl()
  const mappings = useQuery(listImportMappingsOptions({ path: { id: batch.id } }))
  const [tab, setTab] = useState<MappingKind>('STATUS')
  // One save at a time across the tabs: each save moves the batch version, so a second one sent before the first returns
  // would carry the old version and be refused as changed meanwhile.
  const [saving, setSaving] = useState(false)
  // Radix Tabs set their own `dir` (ltr unless told), which would turn the tables left to right in Arabic.
  const dir = directionOf(useLocale().locale)
  if (mappings.isPending) return <LoadingState />
  if (mappings.isError) return <ErrorState onRetry={() => void mappings.refetch()} />
  const view = mappings.data
  return (
    <section aria-labelledby="step-mappings" className="flex flex-col gap-4 rounded-md border p-4">
      <h2 id="step-mappings" className="text-base font-semibold">
        {intl.formatMessage(im.stepMappings)}
      </h2>
      <p className="text-sm">{intl.formatMessage(im.mappingsLead)}</p>
      {view.mappings.length === 0 ? (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(im.mappingsNone)}</p>
      ) : (
        <Tabs
          dir={dir}
          value={tab}
          onValueChange={(value) => {
            setTab(value as MappingKind)
          }}
        >
          <TabsList aria-label={intl.formatMessage(im.stepMappings)} className="flex-wrap">
            {KINDS.map((kind) => (
              <TabsTrigger key={kind} value={kind} data-testid={`mappings-tab-${kind}`}>
                {intl.formatMessage(kindLabels[kind])}
              </TabsTrigger>
            ))}
          </TabsList>
          {KINDS.map((kind) => (
            <TabsContent key={kind} value={kind} className="pt-4">
              <KindEditor
                key={`${kind}:${String(view.batchVersion)}`}
                batch={batch}
                kind={kind}
                view={view}
                saving={saving}
                onSaving={setSaving}
                onSaved={onChanged}
              />
            </TabsContent>
          ))}
        </Tabs>
      )}
    </section>
  )
}

function KindEditor({
  batch,
  kind,
  view,
  saving: busy,
  onSaving: setBusy,
  onSaved,
}: {
  batch: ImportBatchView
  kind: MappingKind
  view: ImportMappingsView
  /** A save of any kind is on its way; this one waits. */
  saving: boolean
  onSaving: (saving: boolean) => void
  onSaved: () => void
}) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const editable = batch.allowedActions.includes('MAP')
  const server = view.mappings.filter((m) => m.kind === kind)
  const ordered = kind === 'CLASSIFICATION' ? inLegacyOrder(server) : server
  const [rows, setRows] = useState<Draft[]>(() => draftOf(ordered))
  const [error, setError] = useState<string>()
  const [picked, setPicked] = useState<Record<string, string>>({})
  const targets = view.targets.filter((t) => t.kind === kind)
  const byId = new Map(targets.map((t) => [t.id, t]))
  const rankOf = (target: string) => byId.get(target)?.rank ?? undefined
  const broken = kind === 'CLASSIFICATION' ? orderBreak(rows, rankOf) : undefined
  const before = new Map(draftOf(ordered).map((d, i) => [d.legacyValue, { ...d, index: i }]))

  const changes = (next: Draft[]): ImportMappingRequest[] =>
    next.flatMap((row, i) => {
      const was = before.get(row.legacyValue)
      const order = kind === 'CLASSIFICATION' ? i + 1 : null
      const changed =
        was?.target !== row.target || was.confirmed !== row.confirmed || (order !== null && was.legacyOrder !== order)
      return changed
        ? [{ legacyValue: row.legacyValue, target: row.target, confirmed: row.confirmed, legacyOrder: order }]
        : []
    })

  const save = async (next: Draft[]) => {
    setError(undefined)
    const pairBreak = kind === 'CLASSIFICATION' ? orderBreak(next, rankOf) : undefined
    if (pairBreak) {
      setError(intl.formatMessage(imv.orderBreak, pairBreak))
      return
    }
    const body = changes(next)
    if (body.length === 0) return
    setBusy(true)
    try {
      const saved = await saveImportMappings({
        path: { id: batch.id, kind },
        headers: { 'If-Match': `"${String(view.batchVersion)}"` },
        body,
        throwOnError: true,
      })
      queryClient.setQueryData<ImportMappingsView>(listImportMappingsQueryKey({ path: { id: batch.id } }), (old) => ({
        batchVersion: saved.data.batchVersion,
        mappings: [...(old?.mappings ?? []).filter((m) => m.kind !== kind), ...saved.data.mappings],
        targets: [...(old?.targets ?? []).filter((t) => t.kind !== kind), ...saved.data.targets],
      }))
      toast.show(`${intl.formatMessage(im.saved)} ${intl.formatMessage(im.rerun)}`, 'success')
      onSaved()
    } catch (e) {
      const problem: Problem | undefined = isProblem(e) ? e : undefined
      if (problem?.status === 412) {
        setError(intl.formatMessage(im.conflict))
        await queryClient.invalidateQueries({ queryKey: listImportMappingsQueryKey({ path: { id: batch.id } }) })
        onSaved()
      } else {
        const found = problem?.errors?.[0]
        setError(
          found?.code === 'CLASSIFICATION_ORDER' && found.message
            ? found.message
            : answerText(intl, found?.code ?? problem?.reason),
        )
      }
    } finally {
      setBusy(false)
    }
  }

  /** Any change makes an earlier error stale. */
  const change = (next: (current: Draft[]) => Draft[]) => {
    setError(undefined)
    setRows(next)
  }
  const update = (index: number, patch: Partial<Draft>) => {
    change((current) => current.map((r, i) => (i === index ? { ...r, ...patch } : r)))
  }
  const proposals = rows.filter((r) => r.target !== null && !r.confirmed)

  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">{intl.formatMessage(im.mappingsNone)}</p>
  }
  return (
    <div className="flex flex-col gap-3">
      {kind === 'CLASSIFICATION' ? <p className="text-sm">{intl.formatMessage(im.orderLead)}</p> : null}
      {kind === 'PERSON' ? <p className="text-sm">{intl.formatMessage(im.peopleLead)}</p> : null}
      {editable ? (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            disabled={busy || proposals.length === 0}
            data-testid={`confirm-all-${kind}`}
            onClick={() => {
              const next = rows.map((r) => (r.target !== null ? { ...r, confirmed: true } : r))
              setRows(next)
              void save(next)
            }}
          >
            {intl.formatMessage(im.confirmAll)}
          </Button>
          <Button disabled={busy || changes(rows).length === 0} onClick={() => void save(rows)}>
            {intl.formatMessage(im.saveMappings)}
          </Button>
        </div>
      ) : null}
      {error || broken ? (
        <p role="alert" className="text-sm font-medium text-destructive" data-testid="mapping-error">
          {error ?? (broken ? intl.formatMessage(imv.orderBreak, broken) : null)}
        </p>
      ) : null}
      <TableScroll>
        <table className="w-full text-sm">
          <caption className="sr-only">{intl.formatMessage(kindLabels[kind])}</caption>
          <thead>
            <tr>
              {kind === 'CLASSIFICATION' ? (
                <th scope="col" className="py-2 pe-3 text-start font-medium">
                  {intl.formatMessage(im.colOrder)}
                </th>
              ) : null}
              <th scope="col" className="py-2 pe-3 text-start font-medium">
                {intl.formatMessage(im.colLegacy)}
              </th>
              <th scope="col" className="py-2 pe-3 text-end font-medium">
                {intl.formatMessage(im.colMemos)}
              </th>
              <th scope="col" className="py-2 pe-3 text-start font-medium">
                {intl.formatMessage(im.colOurs)}
              </th>
              <th scope="col" className="py-2 text-start font-medium">
                {intl.formatMessage(im.colConfirmed)}
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr
                key={row.legacyValue}
                className="border-t align-top"
                data-testid={`mapping-${kind}-${row.legacyValue}`}
              >
                {kind === 'CLASSIFICATION' ? (
                  <td className="py-2 pe-3">
                    <div className="flex items-center gap-1">
                      <span className="w-6 tabular-nums">{intl.formatNumber(i + 1)}</span>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled={!editable || i === 0}
                        aria-label={intl.formatMessage(imv.orderUp, { value: row.legacyValue })}
                        onClick={() => {
                          change((current) => moved(current, i, -1))
                        }}
                      >
                        <ArrowUp aria-hidden="true" />
                      </Button>
                      <Button
                        variant="ghost"
                        size="icon"
                        disabled={!editable || i === rows.length - 1}
                        aria-label={intl.formatMessage(imv.orderDown, { value: row.legacyValue })}
                        onClick={() => {
                          change((current) => moved(current, i, 1))
                        }}
                      >
                        <ArrowDown aria-hidden="true" />
                      </Button>
                    </div>
                  </td>
                ) : null}
                <td className="py-2 pe-3">
                  <LegacyValue kind={kind} value={row.legacyValue} />
                </td>
                <td className="py-2 pe-3 text-end tabular-nums">{intl.formatNumber(row.rowCount)}</td>
                <td className="py-2 pe-3">
                  {kind === 'PERSON' ? (
                    <PersonTarget
                      row={row}
                      linked={row.target ? (byId.get(row.target)?.label ?? picked[row.target]) : undefined}
                      editable={editable}
                      onChange={(target, label) => {
                        update(i, { target, proposed: false })
                        if (target && label) setPicked((p) => ({ ...p, [target]: label }))
                      }}
                    />
                  ) : (
                    <TargetSelect
                      kind={kind}
                      row={row}
                      targets={targets}
                      editable={editable}
                      onChange={(target) => {
                        update(i, { target })
                      }}
                    />
                  )}
                  {row.proposed && row.target === before.get(row.legacyValue)?.target && row.target ? (
                    <span className="mt-1 block text-xs text-muted-foreground">{intl.formatMessage(im.proposed)}</span>
                  ) : null}
                </td>
                <td className="py-2">
                  <Checkbox
                    checked={row.confirmed}
                    disabled={!editable}
                    aria-label={intl.formatMessage(imv.confirm, { value: row.legacyValue })}
                    onChange={(e) => {
                      update(i, { confirmed: e.target.checked })
                    }}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </TableScroll>
    </div>
  )
}

function LegacyValue({ kind, value }: { kind: MappingKind; value: string }) {
  const intl = useIntl()
  if (kind === 'PERSON' && value.startsWith('email:')) {
    return (
      <>{intl.formatMessage(imv.personEmail as MessageDescriptor, { value: <bdi dir="ltr">{value.slice(6)}</bdi> })}</>
    )
  }
  if (kind === 'PERSON' && value.startsWith('id:')) {
    return (
      <>{intl.formatMessage(imv.personId as MessageDescriptor, { value: <bdi dir="ltr">{value.slice(3)}</bdi> })}</>
    )
  }
  return <bdi className="font-medium">{value}</bdi>
}

function TargetSelect({
  kind,
  row,
  targets,
  editable,
  onChange,
}: {
  kind: MappingKind
  row: Draft
  targets: ImportMappingTargetView[]
  editable: boolean
  onChange: (target: string | null) => void
}) {
  const intl = useIntl()
  const options =
    kind === 'STATUS'
      ? STATUS_TARGETS.map((s) => ({
          id: s,
          label: s === 'NOT_IMPORTED' ? intl.formatMessage(im.statusNOT_IMPORTED) : intl.formatMessage(statusLabels[s]),
        }))
      : targets.map((t) => ({ id: t.id, label: t.detail ? `${t.label} (${t.detail})` : t.label }))
  return (
    <Select
      className="w-64"
      disabled={!editable}
      aria-label={intl.formatMessage(imv.target, { value: row.legacyValue })}
      value={row.target ?? ''}
      onChange={(e) => {
        onChange(e.target.value || null)
      }}
    >
      <option value="">{intl.formatMessage(im.notMapped)}</option>
      {options.map((o) => (
        <option key={o.id} value={o.id}>
          {o.label}
        </option>
      ))}
    </Select>
  )
}

function PersonTarget({
  row,
  linked,
  editable,
  onChange,
}: {
  row: Draft
  linked?: string
  editable: boolean
  onChange: (target: string | null, label?: string) => void
}) {
  const intl = useIntl()
  const id = useId()
  return (
    <div className="flex w-72 flex-col gap-1">
      <p className="text-sm">
        {row.target ? intl.formatMessage(imv.linked, { name: linked ?? row.target }) : intl.formatMessage(im.notLinked)}
      </p>
      {editable ? (
        <>
          <label htmlFor={id} className="text-xs text-muted-foreground">
            {intl.formatMessage(im.linkPerson)}
          </label>
          <PeoplePicker
            id={id}
            kind="user"
            onChange={(picked) => {
              if (picked) onChange(picked.id, picked.label)
            }}
          />
          {row.target ? (
            <Button
              variant="link"
              className="self-start"
              onClick={() => {
                onChange(null)
              }}
            >
              {intl.formatMessage(im.notLinked)}
            </Button>
          ) : null}
        </>
      ) : null}
    </div>
  )
}
