import { EllipsisVertical, Plus } from 'lucide-react'
import { useIntl } from 'react-intl'
import type { AgendaPolicy, AgendaTemplateItem, AgendaTemplateSection } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Field } from '@/components/ui/field'
import { Input, Select } from '@/components/ui/form-controls'
import { CheckField } from './CheckField'
import { duration, PURPOSES, type Purpose } from './draft'
import { purposeLabels, STANDING_KINDS, standingLabels } from './messages'
import { tm, tmv } from './template-messages'

type StandingKind = (typeof STANDING_KINDS)[number]

/** Moves [list]'s entry at [from] one place up or down; the list is unchanged at its edge. */
function shift<T>(list: T[], from: number, up: boolean): T[] {
  const to = up ? from - 1 : from + 1
  if (to < 0 || to >= list.length) return list
  const next = [...list]
  const [moved] = next.splice(from, 1)
  if (moved !== undefined) next.splice(to, 0, moved)
  return next
}

/**
 * The committee type's Agenda tab (flow R): whether the chair agrees the agenda before its first publication, and the
 * standard agenda new meetings start from — sections (consent, members only, where new items land) and their standing
 * items, each with its Move menu. Only a draft version changes; meetings already started keep their agenda.
 */
export function TemplateTab({
  agenda,
  onChange,
}: {
  agenda: AgendaPolicy
  /** Absent when the version cannot be changed (not a draft, or no right). */
  onChange?: (agenda: AgendaPolicy) => void
}) {
  const intl = useIntl()
  const sections = agenda.sections ?? []
  const chair = agenda.chairAgreement ?? true
  const set = (next: Partial<AgendaPolicy>) => onChange?.({ ...agenda, chairAgreement: chair, sections, ...next })
  const setSections = (next: AgendaTemplateSection[]) => {
    set({ sections: next })
  }
  const setSection = (index: number, next: Partial<AgendaTemplateSection>) => {
    setSections(
      sections.map((s, i) => {
        if (i === index) return { ...s, ...next }
        // At most one section receives new items.
        return next.receivesSubmissions ? { ...s, receivesSubmissions: false } : s
      }),
    )
  }
  const used = new Set(sections.flatMap((s) => (s.items ?? []).map((i) => i.standingKind)).filter(Boolean))

  if (!onChange) return <ReadOnly agenda={agenda} />
  return (
    <div className="flex max-w-4xl flex-col gap-5">
      <CheckField
        label={intl.formatMessage(tm.chairAgreement)}
        hint={intl.formatMessage(tm.chairAgreementHint)}
        checked={chair}
        onChange={(checked) => {
          set({ chairAgreement: checked })
        }}
      />
      <div className="flex flex-col gap-1">
        <h3 className="font-semibold">{intl.formatMessage(tm.standard)}</h3>
        <p className="text-sm text-muted-foreground">{intl.formatMessage(tm.standardHint)}</p>
      </div>
      {sections.length === 0 ? <p>{intl.formatMessage(tm.noSections)}</p> : null}
      <ol className="flex flex-col gap-4">
        {sections.map((section, index) => (
          <li
            key={`${String(index)}-${section.title}`}
            aria-label={section.title || intl.formatMessage(tmv.sectionNumber, { number: index + 1 })}
            className="flex flex-col gap-3 rounded-md border p-3"
          >
            <div className="flex flex-wrap items-end gap-3">
              <Field label={intl.formatMessage(tm.sectionTitle)} className="min-w-56 flex-1">
                {(p) => (
                  <Input
                    {...p}
                    dir="auto"
                    maxLength={200}
                    defaultValue={section.title}
                    onBlur={(e) => {
                      if (e.target.value !== section.title) setSection(index, { title: e.target.value })
                    }}
                  />
                )}
              </Field>
              <ListMenu
                label={intl.formatMessage(tmv.sectionActions, { title: section.title })}
                first={index === 0}
                last={index === sections.length - 1}
                upLabel={intl.formatMessage(tm.moveSectionUp)}
                downLabel={intl.formatMessage(tm.moveSectionDown)}
                removeLabel={intl.formatMessage(tm.removeSection)}
                onMove={(up) => {
                  setSections(shift(sections, index, up))
                }}
                onRemove={() => {
                  setSections(sections.filter((_, i) => i !== index))
                }}
              />
            </div>
            <div className="flex flex-wrap gap-x-6">
              <CheckField
                label={intl.formatMessage(tm.consent)}
                checked={section.kind === 'CONSENT'}
                onChange={(checked) => {
                  setSection(index, { kind: checked ? 'CONSENT' : 'STANDARD' })
                }}
              />
              <CheckField
                label={intl.formatMessage(tm.membersOnly)}
                checked={section.membersOnly ?? false}
                onChange={(checked) => {
                  setSection(index, { membersOnly: checked })
                }}
              />
              <CheckField
                label={intl.formatMessage(tm.landsHere)}
                checked={section.receivesSubmissions ?? false}
                onChange={(checked) => {
                  setSection(index, { receivesSubmissions: checked })
                }}
              />
            </div>
            <StandingItems
              section={section}
              used={used}
              onChange={(items) => {
                setSection(index, { items })
              }}
            />
          </li>
        ))}
      </ol>
      <div>
        <Button
          variant="outline"
          disabled={sections.length >= 30}
          onClick={() => {
            setSections([
              ...sections,
              {
                title: intl.formatMessage(tmv.sectionNumber, { number: sections.length + 1 }),
                kind: 'STANDARD',
                membersOnly: false,
                receivesSubmissions: false,
                items: [],
              },
            ])
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(tm.addSection)}
        </Button>
      </div>
    </div>
  )
}

function StandingItems({
  section,
  used,
  onChange,
}: {
  section: AgendaTemplateSection
  used: Set<string | undefined>
  onChange: (items: AgendaTemplateItem[]) => void
}) {
  const intl = useIntl()
  const items = section.items ?? []
  const setItem = (index: number, next: Partial<AgendaTemplateItem>) => {
    onChange(items.map((item, i) => (i === index ? { ...item, ...next } : item)))
  }
  const free = STANDING_KINDS.find((k) => !used.has(k))
  return (
    <div className="flex flex-col gap-2">
      {items.length ? (
        <ol aria-label={intl.formatMessage(tmv.itemsOf, { title: section.title })} className="flex flex-col gap-2">
          {items.map((item, index) => (
            <li
              key={`${String(index)}-${item.title}`}
              className="grid items-end gap-2 rounded-md bg-muted/30 p-2 sm:grid-cols-[minmax(10rem,1fr)_12rem_10rem_6rem_auto]"
            >
              <Field label={intl.formatMessage(tm.itemTitle)}>
                {(p) => (
                  <Input
                    {...p}
                    dir="auto"
                    maxLength={300}
                    defaultValue={item.title}
                    onBlur={(e) => {
                      if (e.target.value !== item.title) setItem(index, { title: e.target.value })
                    }}
                  />
                )}
              </Field>
              <Field label={intl.formatMessage(tm.standingKind)}>
                {(p) => (
                  <Select
                    {...p}
                    value={item.standingKind ?? ''}
                    onChange={(e) => {
                      const value = e.target.value as StandingKind | ''
                      setItem(index, value ? { standingKind: value } : { standingKind: undefined })
                    }}
                  >
                    <option value="">{intl.formatMessage(tm.noKind)}</option>
                    {STANDING_KINDS.map((k) => (
                      <option key={k} value={k} disabled={used.has(k) && item.standingKind !== k}>
                        {intl.formatMessage(standingLabels[k])}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label={intl.formatMessage(tm.purpose)}>
                {(p) => (
                  <Select
                    {...p}
                    value={item.purpose ?? 'INFORMATION'}
                    onChange={(e) => {
                      setItem(index, { purpose: e.target.value as Purpose })
                    }}
                  >
                    {PURPOSES.map((x) => (
                      <option key={x} value={x}>
                        {intl.formatMessage(purposeLabels[x])}
                      </option>
                    ))}
                  </Select>
                )}
              </Field>
              <Field label={intl.formatMessage(tm.minutes)}>
                {(p) => (
                  <Input
                    {...p}
                    type="number"
                    inputMode="numeric"
                    min={0}
                    max={600}
                    value={item.minutes == null ? '' : String(item.minutes)}
                    onChange={(e) => {
                      setItem(index, { minutes: e.target.value === '' ? null : Number(e.target.value) })
                    }}
                  />
                )}
              </Field>
              <ListMenu
                label={intl.formatMessage(tmv.itemActions, { title: item.title })}
                first={index === 0}
                last={index === items.length - 1}
                upLabel={intl.formatMessage(tm.moveUp)}
                downLabel={intl.formatMessage(tm.moveDown)}
                removeLabel={intl.formatMessage(tm.removeItem)}
                onMove={(up) => {
                  onChange(shift(items, index, up))
                }}
                onRemove={() => {
                  onChange(items.filter((_, i) => i !== index))
                }}
              />
            </li>
          ))}
        </ol>
      ) : null}
      <div>
        <Button
          variant="outline"
          aria-label={intl.formatMessage(tmv.addItemTo, { title: section.title })}
          onClick={() => {
            onChange([
              ...items,
              {
                title: free ? intl.formatMessage(standingLabels[free]) : intl.formatMessage(tm.newItem),
                ...(free ? { standingKind: free } : {}),
                purpose: 'INFORMATION',
                minutes: 5,
              },
            ])
          }}
        >
          <Plus aria-hidden="true" />
          {intl.formatMessage(tm.addItem)}
        </Button>
      </div>
    </div>
  )
}

function ListMenu({
  label,
  first,
  last,
  upLabel,
  downLabel,
  removeLabel,
  onMove,
  onRemove,
}: {
  label: string
  first: boolean
  last: boolean
  upLabel: string
  downLabel: string
  removeLabel: string
  onMove: (up: boolean) => void
  onRemove: () => void
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label={label}>
          <EllipsisVertical aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-52">
        <DropdownMenuItem
          disabled={first}
          onSelect={() => {
            onMove(true)
          }}
        >
          {upLabel}
        </DropdownMenuItem>
        <DropdownMenuItem
          disabled={last}
          onSelect={() => {
            onMove(false)
          }}
        >
          {downLabel}
        </DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem className="text-destructive" onSelect={onRemove}>
          {removeLabel}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/** The template as a reviewer reads it: sections with their flags, and standing items with purpose and time. */
function ReadOnly({ agenda }: { agenda: AgendaPolicy }) {
  const intl = useIntl()
  const sections = agenda.sections ?? []
  return (
    <div className="flex max-w-4xl flex-col gap-4">
      <p>{intl.formatMessage((agenda.chairAgreement ?? true) ? tm.chairAgreesOn : tm.chairAgreesOff)}</p>
      {sections.length === 0 ? <p>{intl.formatMessage(tm.noSections)}</p> : null}
      {sections.map((s, index) => (
        <section key={`${String(index)}-${s.title}`} aria-label={s.title} className="flex flex-col gap-1">
          <h3 dir="auto" className="flex flex-wrap items-center gap-2 font-semibold">
            {s.title}
            {s.kind === 'CONSENT' ? <Badge tone="neutral">{intl.formatMessage(tm.consent)}</Badge> : null}
            {s.membersOnly ? <Badge tone="neutral">{intl.formatMessage(tm.membersOnly)}</Badge> : null}
            {s.receivesSubmissions ? (
              <span className="text-sm font-normal text-muted-foreground">{intl.formatMessage(tm.landsHere)}</span>
            ) : null}
          </h3>
          <ul className="flex flex-col ps-4 text-sm">
            {(s.items ?? []).map((i, n) => (
              <li key={`${String(n)}-${i.title}`} className="flex flex-wrap gap-x-4">
                <span dir="auto">{i.title}</span>
                {i.standingKind ? (
                  <span className="text-muted-foreground">{intl.formatMessage(standingLabels[i.standingKind])}</span>
                ) : null}
                <span className="text-muted-foreground">
                  {intl.formatMessage(purposeLabels[i.purpose ?? 'INFORMATION'])}
                </span>
                {i.minutes ? <span className="text-muted-foreground">{duration(intl.locale, i.minutes)}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}
