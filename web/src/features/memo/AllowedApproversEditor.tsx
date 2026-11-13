import { useQuery } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useState } from 'react'
import { defineMessages, useIntl } from 'react-intl'
import { listRolesOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { AllowedApprovers } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Select } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'

const m = defineMessages({
  title: { id: 'memoTypes.allowed', defaultMessage: 'Who may approve' },
  anyone: { id: 'memoTypes.allowed.anyone', defaultMessage: 'Anyone. Add people, positions or roles to limit it.' },
  kind: { id: 'memoTypes.allowed.kind', defaultMessage: 'Add a' },
  person: { id: 'memoTypes.allowed.person', defaultMessage: 'Person' },
  position: { id: 'memoTypes.allowed.position', defaultMessage: 'Position' },
  role: { id: 'memoTypes.allowed.role', defaultMessage: 'Role' },
  add: { id: 'memoTypes.allowed.add', defaultMessage: 'Add' },
})

const mv = defineMessages<{ remove: { name: string } }>({
  remove: { id: 'memoTypes.allowed.remove', defaultMessage: 'Remove {name}' },
})

type Kind = 'people' | 'positions' | 'roles'

/**
 * A memo type's allowed approvers (design E04 §4.7): named people, the holders of positions, and the holders of roles;
 * nothing listed means anyone. [names] gives the names of people and positions already listed.
 */
export function AllowedApproversEditor({
  value,
  names,
  onChange,
}: {
  value: AllowedApprovers
  names: Record<string, string>
  onChange: (value: AllowedApprovers, names: Record<string, string>) => void
}) {
  const intl = useIntl()
  const roles = useQuery(listRolesOptions())
  const [kind, setKind] = useState<Kind>('people')
  const [picked, setPicked] = useState<Picked>()
  const [role, setRole] = useState('')
  const entries = (['people', 'positions', 'roles'] as const).flatMap((k) =>
    (value[k] ?? []).map((id) => ({
      kind: k,
      id,
      name: k === 'roles' ? ((roles.data ?? []).find((r) => r.id === id)?.name ?? id) : (names[id] ?? id),
    })),
  )

  const add = () => {
    const id = kind === 'roles' ? role : picked?.id
    if (!id || (value[kind] ?? []).includes(id)) return
    onChange(
      { ...value, [kind]: [...(value[kind] ?? []), id] },
      picked ? { ...names, [picked.id]: picked.label } : names,
    )
    setPicked(undefined)
    setRole('')
  }

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium">{intl.formatMessage(m.title)}</legend>
      {entries.length === 0 ? (
        <p className="text-xs text-muted-foreground">{intl.formatMessage(m.anyone)}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {entries.map((e) => (
            <li key={`${e.kind}:${e.id}`} className="flex items-center gap-1 rounded-md border px-2 py-1 text-sm">
              {e.name}
              <Button
                variant="ghost"
                size="icon"
                aria-label={intl.formatMessage(mv.remove, { name: e.name })}
                onClick={() => {
                  onChange({ ...value, [e.kind]: (value[e.kind] ?? []).filter((x) => x !== e.id) }, names)
                }}
              >
                <X aria-hidden="true" />
              </Button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-end gap-2">
        <Field label={intl.formatMessage(m.kind)}>
          {(p) => (
            <Select
              {...p}
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as Kind)
                setPicked(undefined)
              }}
            >
              <option value="people">{intl.formatMessage(m.person)}</option>
              <option value="positions">{intl.formatMessage(m.position)}</option>
              <option value="roles">{intl.formatMessage(m.role)}</option>
            </Select>
          )}
        </Field>
        {kind === 'roles' ? (
          <Field label={intl.formatMessage(m.role)} className="min-w-48 flex-1">
            {(p) => (
              <Select
                {...p}
                value={role}
                onChange={(e) => {
                  setRole(e.target.value)
                }}
              >
                <option value="" />
                {(roles.data ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        ) : (
          <Field label={intl.formatMessage(kind === 'people' ? m.person : m.position)} className="min-w-48 flex-1">
            {(p) => (
              <PeoplePicker
                key={kind}
                id={p.id}
                kind={kind === 'people' ? 'user' : 'position'}
                value={picked}
                onChange={setPicked}
              />
            )}
          </Field>
        )}
        <Button variant="outline" disabled={kind === 'roles' ? !role : !picked} onClick={add}>
          {intl.formatMessage(m.add)}
        </Button>
      </div>
    </fieldset>
  )
}
