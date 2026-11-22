import { useState } from 'react'
import { useIntl } from 'react-intl'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { isDay, PRESETS, type PeriodSearch, type Preset } from './analytics-params'
import { am } from './messages'

const LABELS = {
  '2y': am.period2y,
  '5y': am.period5y,
  thisYear: am.periodThisYear,
  lastYear: am.periodLastYear,
} satisfies Record<Preset, unknown>

/**
 * The period of every analytics page (design E08 §2.1): the last 12 months by default, presets, or two dates. A choice
 * goes into the address at once, so Back, a reload and a change of language keep it; two dates go when "Show" is
 * pressed, so typing a date does not ask the server on every key. The server refuses more than 10 years; the page says
 * so in words.
 */
export function PeriodPicker({ search, onChange }: { search: PeriodSearch; onChange: (next: PeriodSearch) => void }) {
  const intl = useIntl()
  const dated = !search.period && (search.from !== undefined || search.to !== undefined)
  const [custom, setCustom] = useState(dated)
  const [from, setFrom] = useState(search.from ?? '')
  const [to, setTo] = useState(search.to ?? '')
  const choice = custom ? 'custom' : (search.period ?? '')
  return (
    <form
      aria-label={intl.formatMessage(am.period)}
      className="flex flex-wrap items-end gap-3"
      onSubmit={(e) => {
        e.preventDefault()
        onChange({ ...(isDay(from) ? { from } : {}), ...(isDay(to) ? { to } : {}) })
      }}
    >
      <Field label={intl.formatMessage(am.period)} className="min-w-52">
        {(p) => (
          <Select
            {...p}
            className="min-h-11"
            value={choice}
            onChange={(e) => {
              const value = e.target.value
              if (value === 'custom') {
                setCustom(true)
                return
              }
              setCustom(false)
              const preset = PRESETS.find((x) => x === value)
              onChange(preset ? { period: preset } : {})
            }}
          >
            <option value="">{intl.formatMessage(am.period12m)}</option>
            {PRESETS.map((p) => (
              <option key={p} value={p}>
                {intl.formatMessage(LABELS[p])}
              </option>
            ))}
            <option value="custom">{intl.formatMessage(am.periodCustom)}</option>
          </Select>
        )}
      </Field>
      {custom ? (
        <>
          <Field label={intl.formatMessage(am.from)}>
            {(p) => (
              <Input
                {...p}
                type="date"
                className="min-h-11"
                value={from}
                onChange={(e) => {
                  setFrom(e.target.value)
                }}
              />
            )}
          </Field>
          <Field label={intl.formatMessage(am.to)}>
            {(p) => (
              <Input
                {...p}
                type="date"
                className="min-h-11"
                value={to}
                onChange={(e) => {
                  setTo(e.target.value)
                }}
              />
            )}
          </Field>
          <Button type="submit" className="min-h-11">
            {intl.formatMessage(am.apply)}
          </Button>
        </>
      ) : null}
    </form>
  )
}
