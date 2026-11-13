import { useQuery } from '@tanstack/react-query'
import { defineMessages, useIntl } from 'react-intl'
import { getBuilderCatalogueOptions, listOrgUnitsOptions } from '@/api/generated/@tanstack/react-query.gen'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { PeoplePicker } from '@/features/org/PeoplePicker'
import type { AssigneeJson, AssigneeKind } from './definition'
import type { Labels } from './describe'

const am = defineMessages({
  kind: { id: 'builder.assignee.kind', defaultMessage: 'Goes to' },
  kindPerson: { id: 'builder.assignee.kind.person', defaultMessage: 'A person' },
  kindPosition: { id: 'builder.assignee.kind.position', defaultMessage: 'The holder of a position' },
  kindSupervisor: { id: 'builder.assignee.kind.supervisor', defaultMessage: 'The initiator’s supervisor' },
  kindUnitHead: { id: 'builder.assignee.kind.unitHead', defaultMessage: 'The head of a unit' },
  kindRole: { id: 'builder.assignee.kind.role', defaultMessage: 'People with a role' },
  kindQueue: { id: 'builder.assignee.kind.queue', defaultMessage: 'A queue (whoever takes it first)' },
  kindInitiator: { id: 'builder.assignee.kind.initiator', defaultMessage: 'The initiator' },
  person: { id: 'builder.assignee.field.person', defaultMessage: 'Person' },
  position: { id: 'builder.assignee.field.position', defaultMessage: 'Position' },
  levels: { id: 'builder.assignee.field.levels', defaultMessage: 'Levels up the reporting line' },
  unit: { id: 'builder.assignee.field.unit', defaultMessage: 'Unit' },
  initiatorsUnit: { id: 'builder.assignee.field.initiatorsUnit', defaultMessage: 'The initiator’s unit' },
  up: { id: 'builder.assignee.field.up', defaultMessage: 'Units above it (0 for the unit itself)' },
  role: { id: 'builder.assignee.field.role', defaultMessage: 'Role' },
  queue: { id: 'builder.assignee.field.queue', defaultMessage: 'Queue' },
  choose: { id: 'builder.assignee.choose', defaultMessage: 'Choose…' },
})

const KINDS: AssigneeKind[] = ['person', 'position', 'supervisor', 'unitHead', 'role', 'queue', 'initiator']

const kindLabels = {
  person: am.kindPerson,
  position: am.kindPosition,
  supervisor: am.kindSupervisor,
  unitHead: am.kindUnitHead,
  role: am.kindRole,
  queue: am.kindQueue,
  initiator: am.kindInitiator,
} as const

/** What a newly chosen kind starts from. */
function fresh(kind: AssigneeKind): AssigneeJson {
  switch (kind) {
    case 'supervisor':
      return { kind, levels: 1 }
    case 'unitHead':
      return { kind, unit: 'initiator', up: 0 }
    case 'role':
      return { kind, unit: 'initiator' }
    default:
      return { kind }
  }
}

function range(from: number, to: number): number[] {
  return Array.from({ length: to - from + 1 }, (_, i) => from + i)
}

/**
 * Who a step goes to (design E06 §3, E04 §2): the kind, then what that kind needs. People and positions come from the
 * directory search; roles and queues from the builder catalogue; units from the directory. Names picked here are
 * reported with [onLabel] so the views can show them before the draft is saved.
 */
export function AssigneeEditor({
  value,
  onChange,
  labels,
  onLabel,
  allowInitiator = false,
  legend,
}: {
  value: AssigneeJson | undefined
  onChange: (assignee: AssigneeJson) => void
  labels: Labels
  onLabel: (id: string, label: string) => void
  allowInitiator?: boolean
  legend?: string
}) {
  const intl = useIntl()
  const catalogue = useQuery(getBuilderCatalogueOptions())
  const units = useQuery(listOrgUnitsOptions())
  const kinds = KINDS.filter((k) => allowInitiator || k !== 'initiator')
  const a = value ?? { kind: 'person' as const }
  const set = (patch: Partial<AssigneeJson>) => {
    onChange({ ...a, ...patch })
  }
  const unitSelect = (
    <Field label={intl.formatMessage(am.unit)}>
      {(p) => (
        <Select
          {...p}
          value={a.unit ?? 'initiator'}
          onChange={(e) => {
            const unit = units.data?.find((u) => u.id === e.target.value)
            if (unit) onLabel(unit.id, unit.name)
            set({ unit: e.target.value })
          }}
        >
          <option value="initiator">{intl.formatMessage(am.initiatorsUnit)}</option>
          {(units.data ?? []).map((u) => (
            <option key={u.id} value={u.id}>
              {u.name}
            </option>
          ))}
        </Select>
      )}
    </Field>
  )
  return (
    <fieldset className="flex flex-col gap-2 rounded-md border p-2">
      {legend ? <legend className="px-1 text-sm font-medium">{legend}</legend> : null}
      <Field label={intl.formatMessage(am.kind)}>
        {(p) => (
          <Select
            {...p}
            value={value ? a.kind : ''}
            onChange={(e) => {
              onChange(fresh(e.target.value as AssigneeKind))
            }}
          >
            {value ? null : <option value="">{intl.formatMessage(am.choose)}</option>}
            {kinds.map((k) => (
              <option key={k} value={k}>
                {intl.formatMessage(kindLabels[k])}
              </option>
            ))}
          </Select>
        )}
      </Field>
      {value && (a.kind === 'person' || a.kind === 'position') ? (
        <Field label={intl.formatMessage(a.kind === 'person' ? am.person : am.position)}>
          {(p) => {
            const id = a.kind === 'person' ? a.userId : a.positionId
            return (
              <PeoplePicker
                id={p.id}
                kind={a.kind === 'person' ? 'user' : 'position'}
                {...(id ? { value: { id, label: labels[id] ?? id } } : {})}
                {...(p['aria-describedby'] ? { describedBy: p['aria-describedby'] } : {})}
                onChange={(picked) => {
                  if (!picked) return
                  onLabel(picked.id, picked.label)
                  set(a.kind === 'person' ? { userId: picked.id } : { positionId: picked.id })
                }}
              />
            )
          }}
        </Field>
      ) : null}
      {value && a.kind === 'supervisor' ? (
        <Field label={intl.formatMessage(am.levels)}>
          {(p) => (
            <Select
              {...p}
              value={String(a.levels ?? 1)}
              onChange={(e) => {
                set({ levels: Number(e.target.value) })
              }}
            >
              {range(1, 10).map((n) => (
                <option key={n} value={n}>
                  {intl.formatNumber(n)}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {value && a.kind === 'unitHead' ? (
        <>
          {unitSelect}
          <Field label={intl.formatMessage(am.up)}>
            {(p) => (
              <Select
                {...p}
                value={String(a.up ?? 0)}
                onChange={(e) => {
                  set({ up: Number(e.target.value) })
                }}
              >
                {range(0, 10).map((n) => (
                  <option key={n} value={n}>
                    {intl.formatNumber(n)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </>
      ) : null}
      {value && a.kind === 'role' ? (
        <>
          <Field label={intl.formatMessage(am.role)}>
            {(p) => (
              <Select
                {...p}
                value={a.roleId ?? ''}
                onChange={(e) => {
                  const role = catalogue.data?.roles.find((r) => r.id === e.target.value)
                  if (role) onLabel(role.id, role.name)
                  set({ roleId: e.target.value })
                }}
              >
                <option value="">{intl.formatMessage(am.choose)}</option>
                {(catalogue.data?.roles ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          {unitSelect}
        </>
      ) : null}
      {value && a.kind === 'queue' ? (
        <Field label={intl.formatMessage(am.queue)}>
          {(p) => (
            <Select
              {...p}
              value={a.queue ?? ''}
              onChange={(e) => {
                set({ queue: e.target.value })
              }}
            >
              <option value="">{intl.formatMessage(am.choose)}</option>
              {(catalogue.data?.queues ?? []).map((q) => (
                <option key={q.key} value={q.key}>
                  {q.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
    </fieldset>
  )
}
