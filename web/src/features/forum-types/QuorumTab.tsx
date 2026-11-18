import { Check, Plus, Trash2, X } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { previewQuorum } from '@/api/generated/sdk.gen'
import type { QuorumResult } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Select } from '@/components/ui/form-controls'
import { ft, ftv } from './messages'
import { NumberInput } from './NumberInput'
import { quorumNotes, quorumSentence, rolesText, whoText } from './quorum-words'
import {
  MAX_COMPOSITION,
  MAX_REQUIRED,
  REQUIRED_ROLES,
  ruleRequest,
  sampleMembers,
  type Attribute,
  type Role,
  type Rule,
  type ThresholdKind,
} from './settings'

/**
 * The Quorum tab (flow J4, ADR-0057): the rule built from plain choices on one side; on the other, the rule read back as
 * a sentence, a worked example and a tester. The example and the tester ask the server (`POST /forums/quorum-preview`),
 * so the numbers are the ones meetings will use. Read-only unless the version is a draft ([onChange] set).
 */
export function QuorumTab({
  rule,
  attributes,
  onChange,
  errors,
}: {
  rule: Rule
  attributes: Attribute[]
  onChange?: (rule: Rule) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const labels = Object.fromEntries(attributes.map((a) => [a.key, a.label]))
  const [preview, setPreview] = useState<{ result?: QuorumResult; problems: Record<string, string> }>({
    problems: {},
  })
  const [tester, setTester] = useState<TesterState>(() => ({
    inOffice: 7,
    present: 4,
    rolesPresent: rule.required.map(() => true),
    attributes: Object.fromEntries(rule.composition.map((c) => [c.attribute, 2])),
  }))

  const counts = Object.fromEntries(
    rule.composition.map((c) => [c.attribute, tester.attributes[c.attribute] ?? 2] as const),
  )
  const body = {
    rule: ruleRequest(rule),
    attributes: attributes.map((a) => a.key),
    members: sampleMembers(
      rule,
      tester.inOffice ?? 0,
      tester.present ?? 0,
      rule.required.map((_, i) => tester.rolesPresent[i] ?? true),
      counts,
    ),
  }
  const key = JSON.stringify(body)
  useEffect(() => {
    const timer = setTimeout(() => {
      previewQuorum({ body, throwOnError: true })
        .then((r) => {
          setPreview({ result: r.data, problems: {} })
        })
        .catch((problem: unknown) => {
          const found = isProblem(problem) ? (problem.errors ?? []) : []
          setPreview({ problems: Object.fromEntries(found.map((e) => [e.field, e.message])) })
        })
    }, 300)
    return () => {
      clearTimeout(timer)
    }
    // The body string stands for the request, so an equal rule and sample do not ask again.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const shown = { ...preview.problems, ...errors }
  return (
    <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,26rem)]">
      <Builder rule={rule} attributes={attributes} onChange={onChange} errors={shown} />
      <aside
        aria-labelledby="quorum-words"
        className="flex flex-col gap-4 self-start rounded-md border bg-muted/30 p-4 lg:sticky lg:top-4"
      >
        <section className="flex flex-col gap-1">
          <h3 id="quorum-words" className="text-sm font-semibold">
            {intl.formatMessage(ft.inWords)}
          </h3>
          <p className="text-base leading-relaxed" aria-live="polite">
            {quorumSentence(intl, rule, labels)}
          </p>
          <ul className="list-disc ps-5 text-sm text-muted-foreground">
            {quorumNotes(intl, rule).map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </section>
        <section aria-labelledby="quorum-example" className="flex flex-col gap-1 border-t pt-3">
          <h3 id="quorum-example" className="text-sm font-semibold">
            {intl.formatMessage(ft.example)}
          </h3>
          <p className="text-sm">
            {preview.result
              ? rule.basis === 'SEATS'
                ? intl.formatMessage(ftv.exampleSeats, {
                    basis: preview.result.basis ?? 0,
                    needed: preview.result.needed ?? 0,
                  })
                : intl.formatMessage(ftv.exampleInOffice, {
                    basis: preview.result.basis ?? 0,
                    who: whoText(intl, rule),
                    needed: preview.result.needed ?? 0,
                  })
              : intl.formatMessage(ft.previewUnavailable)}
          </p>
        </section>
        <Tester
          rule={rule}
          labels={labels}
          tester={tester}
          onChange={(next) => {
            setTester({ ...tester, ...next })
          }}
          result={preview.result}
        />
      </aside>
    </div>
  )
}

interface TesterState {
  inOffice: number | null
  present: number | null
  rolesPresent: boolean[]
  attributes: Record<string, number | null>
}

function Tester({
  rule,
  labels,
  tester,
  onChange,
  result,
}: {
  rule: Rule
  labels: Record<string, string>
  tester: TesterState
  onChange: (next: Partial<TesterState>) => void
  result?: QuorumResult
}) {
  const intl = useIntl()
  const attributes = [...new Set(rule.composition.map((c) => c.attribute))]
  return (
    <section aria-labelledby="quorum-try" className="flex flex-col gap-3 border-t pt-3">
      <div>
        <h3 id="quorum-try" className="text-sm font-semibold">
          {intl.formatMessage(ft.tryIt)}
        </h3>
        <p className="text-xs text-muted-foreground">{intl.formatMessage(ft.tryItLead)}</p>
      </div>
      <div className="flex flex-wrap gap-3">
        <Field label={intl.formatMessage(rule.counts === 'VOTING' ? ft.testerInOfficeVoting : ft.testerInOfficeAll)}>
          {(p) => (
            <NumberInput
              {...p}
              min={0}
              max={100}
              value={tester.inOffice}
              onChange={(inOffice) => {
                onChange({ inOffice })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(ft.testerPresent)}>
          {(p) => (
            <NumberInput
              {...p}
              min={0}
              max={100}
              value={tester.present}
              onChange={(present) => {
                onChange({ present })
              }}
            />
          )}
        </Field>
      </div>
      {rule.required.map((roles, i) =>
        roles.length ? (
          <label key={roles.join('-')} className="flex min-h-10 items-center gap-3 text-sm">
            <Checkbox
              className="size-5"
              checked={tester.rolesPresent[i] ?? true}
              onChange={(e) => {
                const rolesPresent = rule.required.map((_, j) => tester.rolesPresent[j] ?? true)
                rolesPresent[i] = e.target.checked
                onChange({ rolesPresent })
              }}
            />
            {intl.formatMessage(ftv.rolePresent, { roles: capitalise(rolesText(intl, roles), intl.locale) })}
          </label>
        ) : null,
      )}
      {attributes.map((attribute) => (
        <Field
          key={attribute}
          label={intl.formatMessage(ftv.attributePresent, { label: labels[attribute] ?? attribute })}
        >
          {(p) => (
            <NumberInput
              {...p}
              min={0}
              max={100}
              value={tester.attributes[attribute] ?? 2}
              onChange={(count) => {
                onChange({ attributes: { ...tester.attributes, [attribute]: count } })
              }}
            />
          )}
        </Field>
      ))}
      <div role="status" aria-live="polite">
        {result ? <Verdict result={result} labels={labels} /> : null}
      </div>
    </section>
  )
}

function Verdict({ result, labels }: { result: QuorumResult; labels: Record<string, string> }) {
  const intl = useIntl()
  if (result.quorate) {
    return (
      <p className="flex items-start gap-2 rounded-md bg-status-ok-bg p-2 text-sm font-medium text-status-ok-fg">
        <Check aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span>
          {intl.formatMessage(ftv.quorateDetail, { present: result.present ?? 0, needed: result.needed ?? 0 })}
        </span>
      </p>
    )
  }
  return (
    <div className="flex items-start gap-2 rounded-md bg-status-danger-bg p-2 text-sm text-status-danger-fg">
      <X aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
      <div>
        <p className="font-medium">{intl.formatMessage(ft.notQuorate)}</p>
        <ul className="list-disc ps-5">
          {(result.unmet ?? []).map((u, i) => (
            <li key={`${u.kind ?? ''}-${String(i)}`}>
              {u.kind === 'ROLE'
                ? intl.formatMessage(ftv.unmetRole, { roles: rolesText(intl, u.roles ?? []) })
                : u.kind === 'COMPOSITION'
                  ? intl.formatMessage(ftv.unmetComposition, {
                      needed: u.needed ?? 0,
                      label: (labels[u.attribute ?? ''] ?? u.attribute ?? '').toLocaleLowerCase(intl.locale),
                      actual: u.actual ?? 0,
                    })
                  : intl.formatMessage(ftv.unmetCount, { needed: u.needed ?? 0, actual: u.actual ?? 0 })}
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}

function capitalise(text: string, locale: string): string {
  return text.slice(0, 1).toLocaleUpperCase(locale) + text.slice(1)
}

const roleLabels = {
  CHAIR: ft.CHAIR,
  VICE_CHAIR: ft.VICE_CHAIR,
  SECRETARY: ft.SECRETARY,
  MEMBER: ft.MEMBER,
} as const

function roleLabel(role: Role) {
  return role in roleLabels ? roleLabels[role as keyof typeof roleLabels] : ft.MEMBER
}

/** One row of the builder: a label column and the controls (stacked on a phone). */
function Row({
  legend,
  hint,
  error,
  children,
}: {
  legend: string
  hint?: string
  error?: string
  children: ReactNode
}) {
  return (
    <fieldset className="grid gap-2 border-b pb-4 sm:grid-cols-[10rem_minmax(0,1fr)]">
      <legend className="float-start pt-2 text-sm font-medium sm:w-40">{legend}</legend>
      <div className="flex flex-col gap-2 sm:col-start-2">
        {children}
        {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
        {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
      </div>
    </fieldset>
  )
}

function Radio({
  name,
  checked,
  onSelect,
  children,
}: {
  name: string
  checked: boolean
  onSelect: () => void
  children: ReactNode
}) {
  return (
    <label className="flex min-h-10 flex-wrap items-center gap-3 text-sm">
      <input type="radio" name={name} className="size-5 accent-primary" checked={checked} onChange={onSelect} />
      {children}
    </label>
  )
}

function Builder({
  rule,
  attributes,
  onChange,
  errors,
}: {
  rule: Rule
  attributes: Attribute[]
  onChange?: (rule: Rule) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const set = (next: Partial<Rule>) => onChange?.({ ...rule, ...next })
  const t = rule.threshold
  const threshold = (kind: ThresholdKind) => {
    set({
      threshold: {
        kind,
        numerator: kind === 'FRACTION' ? (t.numerator ?? 2) : null,
        denominator: kind === 'FRACTION' ? (t.denominator ?? 3) : null,
        count: kind === 'COUNT' ? (t.count ?? 3) : null,
      },
    })
  }
  const setRequired = (required: Role[][]) => {
    set({ required })
  }
  const setComposition = (composition: Rule['composition']) => {
    set({ composition })
  }
  const keys = attributes.map((a) => a.key)

  return (
    <fieldset disabled={!onChange} className="flex min-w-0 flex-col gap-4">
      <legend className="sr-only">{intl.formatMessage(ft.quorumBuilder)}</legend>
      <Row legend={intl.formatMessage(ft.whoCounts)} hint={intl.formatMessage(ft.countsHint)}>
        <Radio
          name="quorum-counts"
          checked={rule.counts === 'VOTING'}
          onSelect={() => {
            set({ counts: 'VOTING' })
          }}
        >
          {intl.formatMessage(ft.countsVoting)}
        </Radio>
        <Radio
          name="quorum-counts"
          checked={rule.counts === 'ALL'}
          onSelect={() => {
            set({ counts: 'ALL' })
          }}
        >
          {intl.formatMessage(ft.countsAll)}
        </Radio>
      </Row>
      <Row legend={intl.formatMessage(ft.outOf)} error={errors['quorum.seats']}>
        <Radio
          name="quorum-basis"
          checked={rule.basis === 'IN_OFFICE'}
          onSelect={() => {
            set({ basis: 'IN_OFFICE', seats: null })
          }}
        >
          {intl.formatMessage(ft.basisInOffice)}
        </Radio>
        <div className="flex flex-wrap items-center gap-3">
          <Radio
            name="quorum-basis"
            checked={rule.basis === 'SEATS'}
            onSelect={() => {
              set({ basis: 'SEATS', seats: rule.seats ?? 9 })
            }}
          >
            {intl.formatMessage(ft.basisSeats)}
          </Radio>
          {rule.basis === 'SEATS' ? (
            <NumberInput
              aria-label={intl.formatMessage(ft.seats)}
              aria-invalid={errors['quorum.seats'] ? true : undefined}
              min={1}
              max={100}
              value={rule.seats}
              onChange={(seats) => {
                set({ seats })
              }}
            />
          ) : null}
        </div>
      </Row>
      <Row
        legend={intl.formatMessage(ft.howMany)}
        error={errors['quorum.threshold'] ?? errors['quorum.threshold.count']}
      >
        <Radio
          name="quorum-threshold"
          checked={t.kind === 'MAJORITY'}
          onSelect={() => {
            threshold('MAJORITY')
          }}
        >
          {intl.formatMessage(ft.thresholdMajority)}
        </Radio>
        <div className="flex flex-wrap items-center gap-3">
          <Radio
            name="quorum-threshold"
            checked={t.kind === 'FRACTION'}
            onSelect={() => {
              threshold('FRACTION')
            }}
          >
            {intl.formatMessage(ft.thresholdFraction)}
          </Radio>
          {t.kind === 'FRACTION' ? (
            <span className="flex flex-wrap items-center gap-2 text-sm">
              <NumberInput
                className="w-20"
                aria-label={intl.formatMessage(ft.numerator)}
                min={1}
                max={10}
                value={t.numerator}
                onChange={(numerator) => {
                  set({ threshold: { ...t, numerator } })
                }}
              />
              <span aria-hidden="true">{intl.formatMessage(ft.fractionBar)}</span>
              <NumberInput
                className="w-20"
                aria-label={intl.formatMessage(ft.denominator)}
                min={1}
                max={10}
                value={t.denominator}
                onChange={(denominator) => {
                  set({ threshold: { ...t, denominator } })
                }}
              />
              {intl.formatMessage(ft.roundedUp)}
            </span>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <Radio
            name="quorum-threshold"
            checked={t.kind === 'COUNT'}
            onSelect={() => {
              threshold('COUNT')
            }}
          >
            {intl.formatMessage(ft.thresholdCount)}
          </Radio>
          {t.kind === 'COUNT' ? (
            <NumberInput
              aria-label={intl.formatMessage(ft.fixedNumber)}
              min={1}
              max={100}
              value={t.count}
              onChange={(count) => {
                set({ threshold: { ...t, count } })
              }}
            />
          ) : null}
        </div>
      </Row>
      <div className="grid gap-2 border-b pb-4 sm:grid-cols-[10rem_minmax(0,1fr)]">
        <Field label={intl.formatMessage(ft.minimum)} error={errors['quorum.minimum']} className="sm:col-span-2">
          {(p) => (
            <NumberInput
              {...p}
              min={1}
              max={100}
              value={rule.minimum}
              onChange={(minimum) => {
                set({ minimum })
              }}
            />
          )}
        </Field>
      </div>
      <Row legend={intl.formatMessage(ft.mustInclude)} error={errors['quorum.required']}>
        {rule.required.length === 0 ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(ft.noRequirement)}</p>
        ) : null}
        {rule.required.map((roles, i) => {
          const n = i + 1
          const left = REQUIRED_ROLES.filter((r) => !roles.includes(r))
          const error = errors[`quorum.required[${String(i)}]`]
          return (
            <div
              key={`${String(i)}-${roles.join('-')}`}
              role="group"
              aria-label={intl.formatMessage(ftv.requirement, { n })}
              className="flex flex-col gap-1"
            >
              <div className="flex flex-wrap items-center gap-2">
                {roles.map((role) => {
                  const name = intl.formatMessage(roleLabel(role))
                  return (
                    <Button
                      key={role}
                      variant="outline"
                      aria-label={intl.formatMessage(ftv.removeNamed, { name })}
                      onClick={() => {
                        setRequired(rule.required.map((r, j) => (j === i ? r.filter((x) => x !== role) : r)))
                      }}
                    >
                      {name}
                      <X aria-hidden="true" />
                    </Button>
                  )
                })}
                {left.length ? (
                  <Select
                    className="w-auto"
                    aria-label={intl.formatMessage(ftv.requirementAddRole, { n })}
                    value=""
                    onChange={(e) => {
                      const value = e.target.value
                      if (value) setRequired(rule.required.map((r, j) => (j === i ? [...r, value as Role] : r)))
                    }}
                  >
                    <option value="">{intl.formatMessage(ft.addRole)}</option>
                    {left.map((r) => (
                      <option key={r} value={r}>
                        {intl.formatMessage(roleLabel(r))}
                      </option>
                    ))}
                  </Select>
                ) : null}
                <span className="text-sm text-muted-foreground">{intl.formatMessage(ft.anyOne)}</span>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(ftv.requirementRemove, { n })}
                  onClick={() => {
                    setRequired(rule.required.filter((_, j) => j !== i))
                  }}
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              </div>
              {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
            </div>
          )
        })}
        {onChange && rule.required.length < MAX_REQUIRED ? (
          <div>
            <Button
              variant="outline"
              onClick={() => {
                const used = rule.required.flat()
                const first = REQUIRED_ROLES.find((r) => !used.includes(r)) ?? 'CHAIR'
                setRequired([...rule.required, [first]])
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(rule.required.length ? ft.addAnotherRequirement : ft.addRequirement)}
            </Button>
          </div>
        ) : null}
      </Row>
      <Row legend={intl.formatMessage(ft.ofThosePresent)} error={errors['quorum.composition']}>
        {rule.composition.length === 0 ? (
          <p className="text-sm text-muted-foreground">{intl.formatMessage(ft.noCondition)}</p>
        ) : null}
        {rule.composition.map((c, i) => {
          const n = i + 1
          const at = `quorum.composition[${String(i)}]`
          const error = errors[`${at}.attribute`] ?? errors[`${at}.atLeast`] ?? errors[at]
          const change = (next: Partial<Rule['composition'][number]>) => {
            setComposition(rule.composition.map((x, j) => (j === i ? { ...x, ...next } : x)))
          }
          return (
            <div key={String(i)} className="flex flex-col gap-1">
              <div className="flex flex-wrap items-center gap-2 text-sm">
                <Select
                  className="w-auto"
                  aria-label={intl.formatMessage(ftv.conditionHowMany, { n })}
                  value={c.majority ? 'majority' : 'atLeast'}
                  onChange={(e) => {
                    const majority = e.target.value === 'majority'
                    change({ majority, atLeast: majority ? null : (c.atLeast ?? 2) })
                  }}
                >
                  <option value="atLeast">{intl.formatMessage(ft.atLeast)}</option>
                  <option value="majority">{intl.formatMessage(ft.aMajority)}</option>
                </Select>
                {c.majority ? null : (
                  <NumberInput
                    className="w-20"
                    aria-label={intl.formatMessage(ftv.conditionNumber, { n })}
                    min={1}
                    max={100}
                    value={c.atLeast}
                    onChange={(atLeast) => {
                      change({ atLeast })
                    }}
                  />
                )}
                <span>{intl.formatMessage(ft.mustBe)}</span>
                <Select
                  className="w-auto"
                  aria-label={intl.formatMessage(ftv.conditionAttribute, { n })}
                  aria-invalid={errors[`${at}.attribute`] ? true : undefined}
                  value={c.attribute}
                  onChange={(e) => {
                    change({ attribute: e.target.value })
                  }}
                >
                  {keys.includes(c.attribute) ? null : <option value={c.attribute}>{c.attribute}</option>}
                  {attributes.map((a) => (
                    <option key={a.key} value={a.key}>
                      {a.label || a.key}
                    </option>
                  ))}
                </Select>
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(ftv.conditionRemove, { n })}
                  onClick={() => {
                    setComposition(rule.composition.filter((_, j) => j !== i))
                  }}
                >
                  <Trash2 aria-hidden="true" />
                </Button>
              </div>
              {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
            </div>
          )
        })}
        {onChange && rule.composition.length < MAX_COMPOSITION ? (
          <div className="flex flex-wrap items-center gap-3">
            <Button
              variant="outline"
              disabled={attributes.length === 0}
              onClick={() => {
                setComposition([...rule.composition, { attribute: keys[0] ?? '', atLeast: 2, majority: false }])
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ft.addCondition)}
            </Button>
            {attributes.length === 0 ? (
              <span className="text-xs text-muted-foreground">{intl.formatMessage(ft.needAttributes)}</span>
            ) : null}
          </div>
        ) : null}
        {(
          [
            ['alternatesCount', ft.alternatesCount],
            ['remoteCounts', ft.remoteCounts],
            ['recusedCount', ft.recusedCount],
          ] as const
        ).map(([flag, label]) => (
          <label key={flag} className="flex min-h-10 items-center gap-3 text-sm">
            <Checkbox
              className="size-5"
              checked={rule[flag]}
              onChange={(e) => {
                set({ [flag]: e.target.checked })
              }}
            />
            {intl.formatMessage(label)}
          </label>
        ))}
      </Row>
    </fieldset>
  )
}
