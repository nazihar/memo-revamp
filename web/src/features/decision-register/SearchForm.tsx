import { ChevronDown, Search } from 'lucide-react'
import { useId, useState, type SyntheticEvent } from 'react'
import { useIntl, type MessageDescriptor } from 'react-intl'
import type { RegisterScopeView } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { drm } from './messages'
import { type Flag, type Outcome, type RegisterSearch, type RegisterState } from './register-params'
import { committeeName, flagWords, outcomeWords, stateWords } from './words'

interface Draft {
  q: string
  committee: string
  type: string
  from: string
  to: string
  outcome: Outcome | ''
  resolutions: boolean
  flag: Flag[]
  memoType: string
  state: RegisterState | ''
}

function draftOf(search: RegisterSearch, q: string): Draft {
  return {
    q,
    committee: search.committee ?? '',
    type: search.type ?? '',
    from: search.from ?? '',
    to: search.to ?? '',
    outcome: search.outcome ?? '',
    resolutions: search.resolutions ?? false,
    flag: search.flag ?? [],
    memoType: search.memoType ?? '',
    state: search.state ?? '',
  }
}

const CONTROL = 'min-h-11'

/**
 * The register's words and filters (flow AS1) as one form: Search applies them together, so a date typed digit by digit
 * does not search on every key, and the per-minute limit is not spent on half-made filters. Each filter applied shows as
 * a chip above the results, removable on its own.
 */
export function SearchForm({
  search,
  q,
  scope,
  refusal,
  onSearch,
}: {
  search: RegisterSearch
  q: string
  scope: RegisterScopeView
  refusal: { text?: MessageDescriptor | undefined; dates?: MessageDescriptor | undefined }
  onSearch: (next: RegisterSearch, words: string) => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const id = useId()
  const [draft, setDraft] = useState(() => draftOf(search, q))
  const [datesError, setDatesError] = useState<MessageDescriptor>()
  const [more, setMore] = useState(
    () => Boolean(search.flag?.length) || Boolean(search.memoType) || Boolean(search.state),
  )
  const set = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setDraft((d) => ({ ...d, [key]: value }))
  }
  const datesMessage = datesError ?? refusal.dates

  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (draft.from && draft.to && draft.to < draft.from) {
      setDatesError(drm.toBeforeFrom)
      return
    }
    setDatesError(undefined)
    const sort = search.sort === 'number' && draft.committee !== search.committee ? undefined : search.sort
    onSearch(
      {
        committee: draft.committee || undefined,
        type: draft.type || undefined,
        from: draft.from || undefined,
        to: draft.to || undefined,
        outcome: draft.outcome || undefined,
        resolutions: draft.resolutions ? true : undefined,
        flag: draft.flag.length ? draft.flag : undefined,
        memoType: draft.memoType || undefined,
        state: draft.state || undefined,
        sort,
      },
      draft.q.trim(),
    )
  }

  const committees = [...scope.committees].sort((a, b) => a.name.localeCompare(b.name, intl.locale))
  const memoTypes = [...scope.memoTypes].sort((a, b) => a.name.localeCompare(b.name, intl.locale))

  return (
    <form
      noValidate
      role="search"
      aria-label={intl.formatMessage(drm.title)}
      onSubmit={submit}
      className="flex flex-col gap-4"
    >
      <Field
        label={intl.formatMessage(drm.queryLabel)}
        hint={intl.formatMessage(drm.queryHint, terms)}
        error={refusal.text ? intl.formatMessage(refusal.text) : undefined}
      >
        {(p) => (
          <div className="flex max-w-3xl gap-2">
            <Input
              {...p}
              type="search"
              name="q"
              value={draft.q}
              autoComplete="off"
              enterKeyHint="search"
              maxLength={400}
              className={CONTROL}
              onChange={(e) => {
                set('q', e.target.value)
              }}
            />
            <Button type="submit" className="min-h-11">
              <Search aria-hidden="true" />
              {intl.formatMessage(drm.search)}
            </Button>
          </div>
        )}
      </Field>
      <fieldset className="flex flex-col gap-3">
        <legend className="sr-only">{intl.formatMessage(drm.filters)}</legend>
        <div className="flex flex-wrap items-end gap-3">
          <Field label={intl.formatMessage(drm.committee, terms)} className="w-full sm:w-64">
            {(p) => (
              <Select
                {...p}
                name="committee"
                className={CONTROL}
                value={draft.committee}
                onChange={(e) => {
                  set('committee', e.target.value)
                }}
              >
                <option value="">{intl.formatMessage(drm.allCommittees, terms)}</option>
                {committees.map((c) => (
                  <option key={c.id} value={c.id}>
                    {committeeName(intl, scope, c.id)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          {scope.committeeTypes.length > 1 ? (
            <Field label={intl.formatMessage(drm.committeeType)} className="w-full sm:w-48">
              {(p) => (
                <Select
                  {...p}
                  name="type"
                  className={CONTROL}
                  value={draft.type}
                  onChange={(e) => {
                    set('type', e.target.value)
                  }}
                >
                  <option value="">{intl.formatMessage(drm.any)}</option>
                  {scope.committeeTypes.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </Select>
              )}
            </Field>
          ) : null}
          <Field label={intl.formatMessage(drm.from)} className="w-full sm:w-44">
            {(p) => (
              <Input
                {...p}
                type="date"
                name="from"
                className={CONTROL}
                value={draft.from}
                onChange={(e) => {
                  set('from', e.target.value)
                }}
              />
            )}
          </Field>
          <Field
            label={intl.formatMessage(drm.to)}
            className="w-full sm:w-44"
            error={datesMessage ? intl.formatMessage(datesMessage) : undefined}
          >
            {(p) => (
              <Input
                {...p}
                type="date"
                name="to"
                className={CONTROL}
                value={draft.to}
                onChange={(e) => {
                  set('to', e.target.value)
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(drm.outcome)} className="w-full sm:w-56">
            {(p) => (
              <Select
                {...p}
                name="outcome"
                className={CONTROL}
                value={draft.outcome}
                onChange={(e) => {
                  set('outcome', scope.outcomes.find((o) => o === e.target.value) ?? '')
                }}
              >
                <option value="">{intl.formatMessage(drm.any)}</option>
                {scope.outcomes.map((o) => (
                  <option key={o} value={o}>
                    {outcomeWords(intl, o)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <label className="flex min-h-11 items-center gap-2 text-sm">
            <Checkbox
              name="resolutions"
              checked={draft.resolutions}
              onChange={(e) => {
                set('resolutions', e.target.checked)
              }}
            />
            {intl.formatMessage(drm.resolutionsOnly)}
          </label>
          <Button
            type="button"
            variant="ghost"
            className="min-h-11"
            aria-expanded={more}
            aria-controls={`${id}-more`}
            onClick={() => {
              setMore((m) => !m)
            }}
          >
            {intl.formatMessage(drm.moreFilters)}
            <ChevronDown aria-hidden="true" className={more ? 'rotate-180' : undefined} />
          </Button>
        </div>
        <div id={`${id}-more`} hidden={!more} className="flex flex-wrap items-start gap-6">
          <fieldset className="flex flex-col gap-1">
            <legend className="mb-1 text-sm font-medium">{intl.formatMessage(drm.exceptions)}</legend>
            {scope.flags.map((flag) => (
              <label key={flag} className="flex min-h-11 items-center gap-2 text-sm">
                <Checkbox
                  name="flag"
                  value={flag}
                  checked={draft.flag.includes(flag)}
                  onChange={(e) => {
                    set('flag', e.target.checked ? [...draft.flag, flag] : draft.flag.filter((f) => f !== flag))
                  }}
                />
                {flagWords(intl, flag)}
              </label>
            ))}
          </fieldset>
          <Field label={intl.formatMessage(drm.memoType)} className="w-full sm:w-56">
            {(p) => (
              <Select
                {...p}
                name="memoType"
                className={CONTROL}
                value={draft.memoType}
                onChange={(e) => {
                  set('memoType', e.target.value)
                }}
              >
                <option value="">{intl.formatMessage(drm.any)}</option>
                {memoTypes.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </Select>
            )}
          </Field>
          <Field label={intl.formatMessage(drm.state)} className="w-full sm:w-56">
            {(p) => (
              <Select
                {...p}
                name="state"
                className={CONTROL}
                value={draft.state}
                onChange={(e) => {
                  set('state', scope.states.find((s) => s === e.target.value) ?? '')
                }}
              >
                <option value="">{intl.formatMessage(drm.any)}</option>
                {scope.states.map((s) => (
                  <option key={s} value={s}>
                    {stateWords(intl, s)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      </fieldset>
    </form>
  )
}
