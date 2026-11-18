import { useQuery } from '@tanstack/react-query'
import { useIntl } from 'react-intl'
import { listClassificationLevelsOptions } from '@/api/generated/@tanstack/react-query.gen'
import type { MeetingResponse } from '@/api/generated/types.gen'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Select, Textarea } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { mm, mmt, mmv } from './messages'
import { timesBody, type PlaceValue, type TimesValue } from './form'
import { MODES } from './shared'
import { instantOf, yourTimes } from './time'
import { TimeZonePicker } from './TimeZonePicker'

/** Date, from, to, "ends on another day", the time zone by city, and the times on the viewer's clock when they differ. */
export function TimesFields({
  value,
  onChange,
  errors,
}: {
  value: TimesValue
  onChange: (next: TimesValue) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const set = (patch: Partial<TimesValue>) => {
    onChange({ ...value, ...patch })
  }
  const body = timesBody(value)
  const start = value.zone && value.date && value.from ? instantOf(body.startsAt, value.zone) : undefined
  const end = value.zone && value.date && value.to ? instantOf(body.endsAt, value.zone) : undefined
  const mine =
    start && end && end > start ? yourTimes(intl, { startsAt: start, endsAt: end, timeZone: value.zone }) : undefined
  return (
    <>
      <div className="flex flex-wrap gap-4">
        <Field label={intl.formatMessage(mm.date)} required error={errors.date}>
          {(p) => (
            <Input
              {...p}
              type="date"
              className="w-44"
              value={value.date}
              onChange={(e) => {
                set({ date: e.target.value })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(mm.from)} required error={errors.startsAt}>
          {(p) => (
            <Input
              {...p}
              type="time"
              className="w-32"
              value={value.from}
              onChange={(e) => {
                set({ from: e.target.value })
              }}
            />
          )}
        </Field>
        <Field label={intl.formatMessage(mm.to)} required error={value.otherDay ? undefined : errors.endsAt}>
          {(p) => (
            <Input
              {...p}
              type="time"
              className="w-32"
              value={value.to}
              onChange={(e) => {
                set({ to: e.target.value })
              }}
            />
          )}
        </Field>
      </div>
      <label className="flex min-h-11 items-center gap-3 text-sm">
        <Checkbox
          className="size-5"
          checked={value.otherDay}
          onChange={(e) => {
            set({ otherDay: e.target.checked, endDate: e.target.checked ? value.endDate || value.date : '' })
          }}
        />
        {intl.formatMessage(mm.otherDay)}
      </label>
      {value.otherDay ? (
        <Field label={intl.formatMessage(mm.endDate)} required error={errors.endDate ?? errors.endsAt}>
          {(p) => (
            <Input
              {...p}
              type="date"
              className="w-44"
              value={value.endDate}
              onChange={(e) => {
                set({ endDate: e.target.value })
              }}
            />
          )}
        </Field>
      ) : null}
      <Field
        label={intl.formatMessage(mm.timeZone)}
        hint={
          mine
            ? `${intl.formatMessage(mm.timeZoneHint)} ${intl.formatMessage(mmv.onYourClock, { times: mine })}`
            : intl.formatMessage(mm.timeZoneHint)
        }
        error={errors.timeZone}
      >
        {(p) => (
          <TimeZonePicker
            id={p.id}
            describedBy={p['aria-describedby']}
            invalid={Boolean(p['aria-invalid'])}
            value={value.zone}
            onChange={(zone) => {
              set({ zone })
            }}
          />
        )}
      </Field>
    </>
  )
}

/** In person, online or both; where; the join link (a plain https field) and the joining instructions. */
export function PlaceFields({
  value,
  onChange,
  errors,
}: {
  value: PlaceValue
  onChange: (next: PlaceValue) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const set = (patch: Partial<PlaceValue>) => {
    onChange({ ...value, ...patch })
  }
  return (
    <>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(mm.howLegend)}</legend>
        <div className="flex flex-wrap gap-x-6">
          {MODES.map((mode) => (
            <label key={mode} className="flex min-h-11 items-center gap-3 text-sm">
              <input
                type="radio"
                name="meeting-mode"
                className="size-5 accent-primary"
                checked={value.mode === mode}
                onChange={() => {
                  set({ mode })
                }}
              />
              {intl.formatMessage(mode === 'HYBRID' ? mm.hybridChoice : mm[mode])}
            </label>
          ))}
        </div>
      </fieldset>
      {value.mode !== 'ONLINE' ? (
        <Field
          label={intl.formatMessage(mm.location)}
          hint={intl.formatMessage(mm.locationHint)}
          required
          error={errors.location}
        >
          {(p) => (
            <Input
              {...p}
              maxLength={500}
              value={value.location}
              onChange={(e) => {
                set({ location: e.target.value })
              }}
            />
          )}
        </Field>
      ) : null}
      {value.mode !== 'IN_PERSON' ? (
        <>
          <Field
            label={intl.formatMessage(mm.joinUrl)}
            hint={intl.formatMessage(mm.joinUrlHint)}
            required
            error={errors.joinUrl}
          >
            {(p) => (
              <Input
                {...p}
                type="url"
                inputMode="url"
                dir="ltr"
                placeholder="https://"
                maxLength={2000}
                value={value.joinUrl}
                onChange={(e) => {
                  set({ joinUrl: e.target.value })
                }}
              />
            )}
          </Field>
          <Field
            label={intl.formatMessage(mm.joinDetails)}
            hint={intl.formatMessage(mm.joinDetailsHint)}
            optionalLabel={intl.formatMessage(mm.optional)}
            error={errors.joinDetails}
          >
            {(p) => (
              <Textarea
                {...p}
                rows={2}
                maxLength={1000}
                value={value.joinDetails}
                onChange={(e) => {
                  set({ joinDetails: e.target.value })
                }}
              />
            )}
          </Field>
        </>
      ) : null}
    </>
  )
}

/** Ordinary or special. */
export function KindField({
  value,
  onChange,
}: {
  value: MeetingResponse['kind']
  onChange: (kind: MeetingResponse['kind']) => void
}) {
  const intl = useIntl()
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-sm font-medium">{intl.formatMessage(mm.kindLegend)}</legend>
      <div className="flex flex-wrap gap-x-6">
        {(['ORDINARY', 'SPECIAL'] as const).map((kind) => (
          <label key={kind} className="flex min-h-11 items-center gap-3 text-sm">
            <input
              type="radio"
              name="meeting-kind"
              className="size-5 accent-primary"
              checked={value === kind}
              onChange={() => {
                onChange(kind)
              }}
            />
            {intl.formatMessage(mm[kind])}
          </label>
        ))}
      </div>
    </fieldset>
  )
}

/** The optional title. */
export function TitleField({
  value,
  onChange,
  error,
}: {
  value: string
  onChange: (title: string) => void
  error?: string
}) {
  const intl = useIntl()
  const terms = useTerms()
  return (
    <Field
      label={intl.formatMessage(mm.title)}
      hint={intl.formatMessage(mmt.titleHint, terms)}
      optionalLabel={intl.formatMessage(mm.optional)}
      error={error}
    >
      {(p) => (
        <Input
          {...p}
          maxLength={200}
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )}
    </Field>
  )
}

/**
 * The classification: the committee's level by default ([floorId], shown by name when known) and never below it; the
 * server also refuses a level above the user's own clearance, in words. [withDefault] offers the committee's level as
 * an empty choice (a new meeting).
 */
export function ClassificationField({
  value,
  onChange,
  floorId,
  withDefault = false,
  error,
}: {
  value: string
  onChange: (id: string) => void
  floorId?: string | null
  withDefault?: boolean
  error?: string
}) {
  const intl = useIntl()
  const terms = useTerms()
  const levels = useQuery(listClassificationLevelsOptions())
  const floor = levels.data?.find((l) => l.id === floorId)
  const choices = (levels.data ?? []).filter((l) => !floor || l.rank >= floor.rank).sort((a, b) => a.rank - b.rank)
  return (
    <Field
      label={intl.formatMessage(mm.classification)}
      hint={intl.formatMessage(mmt.classificationHint, terms)}
      error={error}
    >
      {(p) => (
        <Select
          {...p}
          className="w-72"
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        >
          {withDefault ? (
            <option value="">
              {floor
                ? intl.formatMessage(mmv.withDetail, {
                    label: intl.formatMessage(mmt.committeeLevel, terms),
                    detail: floor.name,
                  })
                : intl.formatMessage(mmt.committeeLevel, terms)}
            </option>
          ) : null}
          {choices.map((l) => (
            <option key={l.id} value={l.id}>
              {l.name}
            </option>
          ))}
        </Select>
      )}
    </Field>
  )
}
