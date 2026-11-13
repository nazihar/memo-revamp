import { ArrowDown, ArrowUp, Trash2, X } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import type { DoaMember, DoaRule, DoaRuleGroup } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { dm, dmv } from './doa-messages'
import { useDoaNames, type DoaNames } from './doa-names'

const MEMO = 'memo'

type Names = DoaNames

/**
 * The rules of a DoA version (design E04 §3, ux-review flow 5): each scoped by memo type, the initiator's unit and a
 * condition, with its approval groups in order. Read-only unless [onChange] is given. Rule order matters (first fit).
 */
export function DoaRulesEditor({
  rules,
  positions,
  onChange,
}: {
  rules: DoaRule[]
  positions: Record<string, string>
  onChange?: (rules: DoaRule[]) => void
}) {
  const intl = useIntl()
  const [titles, setTitles] = useState(positions)
  const names = useDoaNames(titles)
  const edit = onChange
    ? (index: number, rule: DoaRule) => {
        onChange(rules.map((r, i) => (i === index ? rule : r)))
      }
    : undefined

  const move = (index: number, by: number) => {
    if (!onChange) return
    const next = [...rules]
    const [rule] = next.splice(index, 1)
    if (!rule) return
    next.splice(index + by, 0, rule)
    onChange(next)
  }

  return (
    <div className="flex flex-col gap-4">
      {rules.length === 0 ? <p className="text-sm text-muted-foreground">{intl.formatMessage(dm.noRules)}</p> : null}
      <ol className="flex flex-col gap-4">
        {rules.map((rule, index) => (
          <li key={index} className="rounded-md border p-4">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <h3 className="flex-1 text-sm font-semibold">
                {edit
                  ? intl.formatMessage(dmv.rule, { number: index + 1 })
                  : intl.formatMessage(dmv.ruleTitle, { number: index + 1, name: rule.name ?? '' })}
              </h3>
              {onChange ? (
                <>
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={index === 0}
                    aria-label={intl.formatMessage(dmv.moveUp, {
                      rule: intl.formatMessage(dmv.rule, { number: index + 1 }),
                    })}
                    onClick={() => {
                      move(index, -1)
                    }}
                  >
                    <ArrowUp aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    disabled={index === rules.length - 1}
                    aria-label={intl.formatMessage(dmv.moveDown, {
                      rule: intl.formatMessage(dmv.rule, { number: index + 1 }),
                    })}
                    onClick={() => {
                      move(index, 1)
                    }}
                  >
                    <ArrowDown aria-hidden="true" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="icon"
                    aria-label={intl.formatMessage(dmv.removeRule, {
                      rule: intl.formatMessage(dmv.rule, { number: index + 1 }),
                    })}
                    onClick={() => {
                      onChange(rules.filter((_, i) => i !== index))
                    }}
                  >
                    <Trash2 aria-hidden="true" />
                  </Button>
                </>
              ) : null}
            </div>
            <RuleBody
              rule={rule}
              names={names}
              onChange={
                edit
                  ? (r) => {
                      edit(index, r)
                    }
                  : undefined
              }
              onTitle={(id, title) => {
                setTitles((t) => ({ ...t, [id]: title }))
              }}
            />
          </li>
        ))}
      </ol>
      {onChange ? (
        <div>
          <Button
            variant="outline"
            onClick={() => {
              onChange([
                ...rules,
                { name: '', subjectType: MEMO, includeSubtree: true, groups: [{ needed: 1, of: [] }] },
              ])
            }}
          >
            {intl.formatMessage(dm.addRule)}
          </Button>
        </div>
      ) : null}
    </div>
  )
}

function RuleBody({
  rule,
  names,
  onChange,
  onTitle,
}: {
  rule: DoaRule
  names: Names
  onChange?: (rule: DoaRule) => void
  onTitle: (id: string, title: string) => void
}) {
  const intl = useIntl()
  const groups = rule.groups ?? []
  const typeName = names.types.find((t) => t.id === rule.memoTypeId)?.name ?? rule.memoTypeId
  const unitName = names.units.find((u) => u.id === rule.unitId)?.name ?? rule.unitId
  const setGroup = (index: number, group: DoaRuleGroup) => {
    onChange?.({ ...rule, groups: groups.map((g, i) => (i === index ? group : g)) })
  }

  if (!onChange) {
    return (
      <dl className="grid gap-x-4 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
        <dt className="text-muted-foreground">{intl.formatMessage(dm.memoType)}</dt>
        <dd>{typeName ?? intl.formatMessage(dm.anyMemoType)}</dd>
        <dt className="text-muted-foreground">{intl.formatMessage(dm.unit)}</dt>
        <dd>
          {unitName && rule.includeSubtree
            ? intl.formatMessage(dmv.unitSubtree, { unit: unitName })
            : (unitName ?? intl.formatMessage(dm.anyUnit))}
        </dd>
        {rule.condition ? (
          <>
            <dt className="text-muted-foreground">{intl.formatMessage(dm.condition)}</dt>
            <dd>
              <code dir="ltr">{rule.condition}</code>
            </dd>
          </>
        ) : null}
        <dt className="text-muted-foreground">{intl.formatMessage(dm.groups)}</dt>
        <dd>
          <ol className="list-decimal ps-5">
            {groups.map((g, i) => (
              <li key={i}>
                {intl.formatMessage(dmv.needs, {
                  needed: g.needed ?? 1,
                  names: (g.of ?? []).map((m) => memberLabel(intl, names, m)).join(', '),
                })}
              </li>
            ))}
          </ol>
        </dd>
      </dl>
    )
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="grid gap-3 sm:grid-cols-2">
        <Field label={intl.formatMessage(dm.ruleName)} required>
          {(p) => (
            <Input
              {...p}
              maxLength={200}
              value={rule.name ?? ''}
              onChange={(e) => {
                onChange({ ...rule, name: e.target.value })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(dm.memoType)}>
          {(p) => (
            <Select
              {...p}
              value={rule.memoTypeId ?? ''}
              onChange={(e) => {
                onChange({ ...rule, memoTypeId: e.target.value || null })
              }}
            >
              <option value="">{intl.formatMessage(dm.anyMemoType)}</option>
              {names.types.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Field label={intl.formatMessage(dm.unit)}>
          {(p) => (
            <Select
              {...p}
              value={rule.unitId ?? ''}
              onChange={(e) => {
                onChange({ ...rule, unitId: e.target.value || null })
              }}
            >
              <option value="">{intl.formatMessage(dm.anyUnit)}</option>
              {names.units.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.name}
                </option>
              ))}
            </Select>
          )}
        </Field>
        {rule.unitId ? (
          <label className="flex items-center gap-2 self-end text-sm">
            <Checkbox
              checked={rule.includeSubtree ?? true}
              onChange={(e) => {
                onChange({ ...rule, includeSubtree: e.target.checked })
              }}
            />
            {intl.formatMessage(dm.subtree)}
          </label>
        ) : null}
      </div>
      <Field label={intl.formatMessage(dm.condition)} hint={intl.formatMessage(dm.conditionHint)}>
        {(p) => (
          <Input
            {...p}
            dir="ltr"
            maxLength={1000}
            value={rule.condition ?? ''}
            onChange={(e) => {
              onChange({ ...rule, condition: e.target.value || null })
            }}
          />
        )}
      </Field>
      <h4 className="text-sm font-medium">{intl.formatMessage(dm.groups)}</h4>
      <ol className="flex flex-col gap-3">
        {groups.map((group, index) => (
          <li key={index} className="rounded-md border border-dashed p-3">
            <GroupEditor
              index={index}
              group={group}
              names={names}
              onChange={(g) => {
                setGroup(index, g)
              }}
              onRemove={
                groups.length > 1
                  ? () => {
                      onChange({ ...rule, groups: groups.filter((_, i) => i !== index) })
                    }
                  : undefined
              }
              onTitle={onTitle}
            />
          </li>
        ))}
      </ol>
      <div>
        <Button
          variant="outline"
          onClick={() => {
            onChange({ ...rule, groups: [...groups, { needed: 1, of: [] }] })
          }}
        >
          {intl.formatMessage(dm.addGroup)}
        </Button>
      </div>
    </div>
  )
}

function memberLabel(intl: ReturnType<typeof useIntl>, names: Names, m: DoaMember): string {
  const name = names.member(m)
  return m.kind === 'role' ? intl.formatMessage(dmv.roleIn, { role: name }) : name
}

function GroupEditor({
  index,
  group,
  names,
  onChange,
  onRemove,
  onTitle,
}: {
  index: number
  group: DoaRuleGroup
  names: Names
  onChange: (group: DoaRuleGroup) => void
  onRemove?: () => void
  onTitle: (id: string, title: string) => void
}) {
  const intl = useIntl()
  const ids = useId()
  const [kind, setKind] = useState<'position' | 'role'>('position')
  const [picked, setPicked] = useState<Picked>()
  const [role, setRole] = useState('')
  const members = group.of ?? []

  const add = () => {
    const member: DoaMember | undefined =
      kind === 'position'
        ? picked && { kind: 'position', positionId: picked.id }
        : role
          ? { kind: 'role', roleId: role, unit: 'initiator' }
          : undefined
    if (!member) return
    if (members.some((m) => m.kind === member.kind && m.positionId === member.positionId && m.roleId === member.roleId))
      return
    if (picked && kind === 'position') onTitle(picked.id, picked.label)
    onChange({ ...group, of: [...members, member] })
    setPicked(undefined)
    setRole('')
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-end gap-3">
        <span className="flex-1 text-sm font-medium">{intl.formatMessage(dmv.group, { number: index + 1 })}</span>
        <Field label={intl.formatMessage(dm.needed)}>
          {(p) => (
            <Input
              {...p}
              type="number"
              min={1}
              max={20}
              className="w-24"
              value={group.needed ?? 1}
              onChange={(e) => {
                onChange({ ...group, needed: Number(e.target.value) || 1 })
              }}
            />
          )}
        </Field>
        {onRemove ? (
          <Button variant="ghost" size="icon" aria-label={intl.formatMessage(dm.removeGroup)} onClick={onRemove}>
            <Trash2 aria-hidden="true" />
          </Button>
        ) : null}
      </div>
      {members.length === 0 ? (
        <p className="text-xs text-muted-foreground">{intl.formatMessage(dm.noMembers)}</p>
      ) : (
        <ul className="flex flex-wrap gap-2">
          {members.map((m, i) => {
            const label = memberLabel(intl, names, m)
            return (
              <li key={i} className="flex items-center gap-1 rounded-md border px-2 py-1 text-sm">
                {label}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(dmv.removeMember, { name: label })}
                  onClick={() => {
                    onChange({ ...group, of: members.filter((_, j) => j !== i) })
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            )
          })}
        </ul>
      )}
      <div className="flex flex-wrap items-end gap-2">
        <Field label={intl.formatMessage(dm.memberKind)}>
          {(p) => (
            <Select
              {...p}
              value={kind}
              onChange={(e) => {
                setKind(e.target.value as 'position' | 'role')
              }}
            >
              <option value="position">{intl.formatMessage(dm.position)}</option>
              <option value="role">{intl.formatMessage(dm.role)}</option>
            </Select>
          )}
        </Field>
        {kind === 'position' ? (
          <div className="flex min-w-56 flex-1 flex-col gap-1.5">
            <label htmlFor={`${ids}-position`} className="text-sm font-medium">
              {intl.formatMessage(dm.position)}
            </label>
            <PeoplePicker id={`${ids}-position`} kind="position" value={picked} onChange={setPicked} />
          </div>
        ) : (
          <Field label={intl.formatMessage(dm.role)} hint={intl.formatMessage(dm.roleHint)} className="min-w-56 flex-1">
            {(p) => (
              <Select
                {...p}
                value={role}
                onChange={(e) => {
                  setRole(e.target.value)
                }}
              >
                <option value="" />
                {names.roles.map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        )}
        <Button variant="outline" disabled={kind === 'position' ? !picked : !role} onClick={add}>
          {intl.formatMessage(dm.addMember)}
        </Button>
      </div>
    </div>
  )
}
