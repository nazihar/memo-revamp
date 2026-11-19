import { Coffee, EllipsisVertical, Lock, Plus } from 'lucide-react'
import { useState } from 'react'
import { useIntl } from 'react-intl'
import {
  addAgendaItem,
  addAgendaSection,
  removeAgendaItem,
  removeAgendaSection,
  updateAgendaSection,
} from '@/api/generated/sdk.gen'
import type { AgendaItemRequest, AgendaMoveRequest } from '@/api/generated/types.gen'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
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
import { FormDialog } from '@/features/forums/FormDialog'
import { newIdempotencyKey } from '@/lib/idempotency'
import { cn } from '@/lib/utils'
import { DragRow, EmptySectionDrop, SortableList } from './AgendaDnd'
import {
  duration,
  localTime,
  locate,
  moveBody,
  PURPOSES,
  sectionChange,
  sectionMove,
  type Item,
  type MoveCommand,
  type Purpose,
  type Section,
} from './draft'
import { ap, apv, purposeLabels, STANDING_KINDS, standingLabels } from './messages'
import { useWhere } from './hooks'
import { focusRow, menuId, useAgenda } from './useAgenda'

/**
 * The agenda as its secretaries build it (flow O2): sections as headings with their start time, items numbered as they
 * would be published, times and durations, and each row's Move menu and drag handle. Officers who only read (the chair)
 * see the same rows without menus.
 */
export function AgendaList({ onMove }: { onMove: (body: AgendaMoveRequest) => void }) {
  const intl = useIntl()
  const { draft, editable, canAdd } = useAgenda()
  const [adding, setAdding] = useState<{ sectionId?: string; parentId?: string }>()
  const [addingSection, setAddingSection] = useState(false)
  return (
    <div className="flex flex-col gap-4">
      {draft.sections.length === 0 ? (
        <p className="text-muted-foreground">{intl.formatMessage(ap.noSections)}</p>
      ) : null}
      {draft.sections.map((section, index) => (
        <SectionBlock
          key={section.id}
          section={section}
          index={index}
          onMove={onMove}
          onAddSubItem={(parentId) => {
            setAdding({ sectionId: section.id, parentId })
          }}
        />
      ))}
      {canAdd ? (
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            disabled={draft.sections.length === 0}
            onClick={() => {
              setAdding({})
            }}
          >
            <Plus aria-hidden="true" />
            {intl.formatMessage(ap.addItem)}
          </Button>
          {editable ? (
            <Button
              variant="outline"
              onClick={() => {
                setAddingSection(true)
              }}
            >
              <Plus aria-hidden="true" />
              {intl.formatMessage(ap.addSection)}
            </Button>
          ) : null}
        </div>
      ) : null}
      {adding ? (
        <AddItemDialog
          sectionId={adding.sectionId}
          parentId={adding.parentId}
          onClose={() => {
            setAdding(undefined)
          }}
        />
      ) : null}
      {addingSection ? (
        <SectionDialog
          onClose={() => {
            setAddingSection(false)
          }}
        />
      ) : null}
    </div>
  )
}

function SectionBlock({
  section,
  index,
  onMove,
  onAddSubItem,
}: {
  section: Section
  index: number
  onMove: (body: AgendaMoveRequest) => void
  onAddSubItem: (parentId: string) => void
}) {
  const intl = useIntl()
  const { draft, editable } = useAgenda()
  const headingId = `agenda-section-${section.id}`
  const first = section.items.find((i) => i.localStartsAt)?.localStartsAt
  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-1">
      <div className="flex min-h-11 flex-wrap items-center gap-x-3 gap-y-1 border-b pb-1">
        <h2 id={headingId} dir="auto" className="text-base font-semibold">
          {section.title}
        </h2>
        {section.kind === 'CONSENT' ? <Badge tone="neutral">{intl.formatMessage(ap.consent)}</Badge> : null}
        {section.membersOnly ? <Badge tone="neutral">{intl.formatMessage(ap.membersOnly)}</Badge> : null}
        {section.receivesSubmissions ? (
          <span className="text-sm text-muted-foreground">{intl.formatMessage(ap.landsHere)}</span>
        ) : null}
        <span className="ms-auto flex items-center gap-3 text-sm text-muted-foreground tabular-nums">
          {first ? <span>{localTime(intl.locale, first)}</span> : null}
          {section.minutes ? <span>{duration(intl.locale, section.minutes)}</span> : null}
          {editable ? (
            <SectionMenu section={section} index={index} count={draft.sections.length} onMove={onMove} />
          ) : null}
        </span>
      </div>
      {section.items.length === 0 ? (
        <EmptySectionDrop sectionId={section.id} enabled={editable}>
          <p className="px-2 py-3 text-sm text-muted-foreground">{intl.formatMessage(ap.emptySection)}</p>
        </EmptySectionDrop>
      ) : (
        <SortableList ids={section.items.map((i) => i.id)}>
          <ol aria-label={section.title} className="flex flex-col">
            {section.items.map((item) => (
              <ItemRow key={item.id} item={item} onMove={onMove} onAddSubItem={onAddSubItem} />
            ))}
          </ol>
        </SortableList>
      )}
    </section>
  )
}

function ItemRow({
  item,
  parent,
  onMove,
  onAddSubItem,
}: {
  item: Item
  parent?: Item
  onMove: (body: AgendaMoveRequest) => void
  onAddSubItem: (parentId: string) => void
}) {
  const intl = useIntl()
  const { editable } = useAgenda()
  const isBreak = item.kind === 'BREAK'
  return (
    <DragRow
      id={item.id}
      title={item.title}
      enabled={editable}
      className={cn('flex flex-col rounded-md', parent && 'ps-8')}
    >
      {(handle) => (
        <>
          <div className="flex min-h-11 items-start gap-2 py-1">
            {handle}
            <span className="mt-2.5 w-10 shrink-0 font-medium tabular-nums">
              {isBreak ? (
                <Coffee aria-hidden="true" className="size-4" />
              ) : (
                (item.number ?? intl.formatMessage(ap.noNumber))
              )}
            </span>
            <div className="mt-2 flex min-w-0 flex-1 flex-col gap-0.5">
              <p className="flex flex-wrap items-baseline gap-x-3">
                <span dir="auto" className="font-medium">
                  {item.title}
                </span>
                {item.purpose && !isBreak ? (
                  <span className="text-sm text-muted-foreground">
                    {intl.formatMessage(purposeLabels[item.purpose])}
                  </span>
                ) : null}
              </p>
              <ItemFacts item={item} />
            </div>
            <span className="mt-2.5 flex shrink-0 gap-3 text-sm tabular-nums text-muted-foreground">
              {item.localStartsAt ? <span>{localTime(intl.locale, item.localStartsAt)}</span> : null}
              {item.minutes ? <span>{duration(intl.locale, item.minutes)}</span> : null}
            </span>
            <ItemMenu item={item} parent={parent} onMove={onMove} onAddSubItem={onAddSubItem} />
          </div>
          {item.subItems.length ? (
            <SortableList ids={item.subItems.map((s) => s.id)}>
              <ol aria-label={intl.formatMessage(apv.subItemsOf, { title: item.title })} className="flex flex-col">
                {item.subItems.map((sub) => (
                  <ItemRow key={sub.id} item={sub} parent={item} onMove={onMove} onAddSubItem={onAddSubItem} />
                ))}
              </ol>
            </SortableList>
          ) : null}
        </>
      )}
    </DragRow>
  )
}

/** The second line of a row: presenters, papers, the memo's number, lateness and who can see it, in words. */
function ItemFacts({ item }: { item: Item }) {
  const intl = useIntl()
  const pages = item.papers.reduce((sum, p) => sum + (p.pages ?? 0), 0)
  const facts: string[] = []
  if (item.memoReference) facts.push(item.memoReference)
  if (item.presenters.length) {
    facts.push(intl.formatMessage(apv.presenters, { names: intl.formatList(item.presenters.map((p) => p.name)) }))
  }
  if (item.papers.length) facts.push(intl.formatMessage(apv.papers, { count: item.papers.length, pages }))
  const late = item.late !== 'NONE' ? intl.formatMessage(ap[`late${item.late}`]) : undefined
  const latePaper = item.papers.some((p) => p.late === 'AWAITING_CHAIR')
    ? intl.formatMessage(ap.paperAwaitingChair)
    : undefined
  if (!facts.length && !late && !latePaper && !item.restrictedTo) return null
  return (
    <div className="flex flex-col gap-0.5 text-sm text-muted-foreground">
      {facts.length || late || latePaper ? (
        <p className="flex flex-wrap gap-x-2">
          {facts.map((f) => (
            <span key={f} dir="auto">
              {f}
            </span>
          ))}
          {late ? <span className="font-medium text-foreground">{late}</span> : null}
          {latePaper ? <span className="font-medium text-foreground">{latePaper}</span> : null}
        </p>
      ) : null}
      {item.restrictedTo ? (
        <p className="flex items-center gap-1.5">
          <Lock aria-hidden="true" className="size-3.5" />
          {intl.formatMessage(item.titleHidden ? apv.restrictedHidden : apv.restricted, {
            count: item.restrictedTo.length,
          })}
        </p>
      ) : null}
    </div>
  )
}

/** The row's ⋯ menu (flow O2): the sheet's tabs, the moves, and removal. Commands that cannot apply are disabled. */
function ItemMenu({
  item,
  parent,
  onMove,
  onAddSubItem,
}: {
  item: Item
  parent?: Item
  onMove: (body: AgendaMoveRequest) => void
  onAddSubItem: (parentId: string) => void
}) {
  const intl = useIntl()
  const { draft, editable, canAdd, openSheet } = useAgenda()
  const [dialog, setDialog] = useState<'section' | 'position' | 'remove'>()
  if (!editable && !canAdd) return null
  const isBreak = item.kind === 'BREAK'
  const body = (command: MoveCommand) => moveBody(draft, item.id, command)
  const command = (c: MoveCommand) => {
    const b = body(c)
    if (b) onMove(b)
  }
  const label = intl.formatMessage(apv.itemActions, { title: item.title })
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button id={menuId(item.id)} variant="ghost" size="icon" aria-label={label}>
            <EllipsisVertical aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-64">
          {editable ? (
            <DropdownMenuItem
              onSelect={() => {
                openSheet(item.id, 'details')
              }}
            >
              {intl.formatMessage(ap.edit)}
            </DropdownMenuItem>
          ) : null}
          {!isBreak ? (
            <DropdownMenuItem
              onSelect={() => {
                openSheet(item.id, 'papers')
              }}
            >
              {intl.formatMessage(ap.papersAction)}
            </DropdownMenuItem>
          ) : null}
          {editable && !isBreak ? (
            <DropdownMenuItem
              onSelect={() => {
                openSheet(item.id, 'access')
              }}
            >
              {intl.formatMessage(ap.whoCanSee)}
            </DropdownMenuItem>
          ) : null}
          {editable ? (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuItem
                disabled={!body('up')}
                onSelect={() => {
                  command('up')
                }}
              >
                {intl.formatMessage(ap.moveUp)}
              </DropdownMenuItem>
              <DropdownMenuItem
                disabled={!body('down')}
                onSelect={() => {
                  command('down')
                }}
              >
                {intl.formatMessage(ap.moveDown)}
              </DropdownMenuItem>
              {!parent ? (
                <DropdownMenuItem
                  disabled={draft.sections.length < 2}
                  onSelect={() => {
                    setDialog('section')
                  }}
                >
                  {intl.formatMessage(ap.moveToSection)}
                </DropdownMenuItem>
              ) : null}
              {!parent ? (
                <DropdownMenuItem
                  disabled={!body('indent')}
                  onSelect={() => {
                    command('indent')
                  }}
                >
                  {intl.formatMessage(ap.indent)}
                </DropdownMenuItem>
              ) : (
                <DropdownMenuItem
                  onSelect={() => {
                    command('outdent')
                  }}
                >
                  {intl.formatMessage(ap.outdent)}
                </DropdownMenuItem>
              )}
              <DropdownMenuItem
                onSelect={() => {
                  setDialog('position')
                }}
              >
                {intl.formatMessage(ap.moveToPosition)}
              </DropdownMenuItem>
              {!parent && !isBreak ? (
                <DropdownMenuItem
                  onSelect={() => {
                    onAddSubItem(item.id)
                  }}
                >
                  {intl.formatMessage(ap.addSubItem)}
                </DropdownMenuItem>
              ) : null}
              <DropdownMenuSeparator />
              <DropdownMenuItem
                className="text-destructive"
                onSelect={() => {
                  setDialog('remove')
                }}
              >
                {intl.formatMessage(ap.remove)}
              </DropdownMenuItem>
            </>
          ) : null}
        </DropdownMenuContent>
      </DropdownMenu>
      {dialog === 'section' ? (
        <MoveToSectionDialog
          item={item}
          onMove={onMove}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
      {dialog === 'position' ? (
        <MoveToPositionDialog
          item={item}
          onMove={onMove}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
      {dialog === 'remove' ? (
        <RemoveItemDialog
          item={item}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
    </>
  )
}

function MoveToSectionDialog({
  item,
  onMove,
  onClose,
}: {
  item: Item
  onMove: (body: AgendaMoveRequest) => void
  onClose: () => void
}) {
  const intl = useIntl()
  const { draft } = useAgenda()
  const at = locate(draft, item.id)
  const others = draft.sections.filter((s) => s.id !== at?.section.id)
  const [sectionId, setSectionId] = useState(others[0]?.id ?? '')
  const [where, setWhere] = useState<'end' | 'start'>('end')
  return (
    <FormDialog
      cancelLabel={intl.formatMessage(ap.cancel)}
      title={intl.formatMessage(apv.moveToSectionTitle, { title: item.title })}
      submitLabel={intl.formatMessage(ap.move)}
      onClose={onClose}
      onSubmit={() => {
        const section = draft.sections.find((s) => s.id === sectionId)
        if (!section) return Promise.resolve(undefined)
        onClose()
        onMove({
          itemId: item.id,
          toSectionId: section.id,
          toParentId: null,
          toPosition: where === 'start' ? 0 : section.items.length,
        })
        return Promise.resolve(undefined)
      }}
    >
      <Field label={intl.formatMessage(ap.section)} required>
        {(p) => (
          <Select
            {...p}
            value={sectionId}
            onChange={(e) => {
              setSectionId(e.target.value)
            }}
          >
            {others.map((s) => (
              <option key={s.id} value={s.id}>
                {s.title}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.where)}</legend>
        {(['end', 'start'] as const).map((w) => (
          <label key={w} className="flex min-h-11 items-center gap-2 text-sm">
            <input
              type="radio"
              name="where"
              className="size-4 accent-primary"
              checked={where === w}
              onChange={() => {
                setWhere(w)
              }}
            />
            {intl.formatMessage(w === 'end' ? ap.atEnd : ap.atStart)}
          </label>
        ))}
      </fieldset>
    </FormDialog>
  )
}

function MoveToPositionDialog({
  item,
  onMove,
  onClose,
}: {
  item: Item
  onMove: (body: AgendaMoveRequest) => void
  onClose: () => void
}) {
  const intl = useIntl()
  const { draft } = useAgenda()
  const at = locate(draft, item.id)
  const count = at?.siblings.length ?? 1
  const [position, setPosition] = useState(String((at?.index ?? 0) + 1))
  const [error, setError] = useState<string>()
  const where = at?.parent ? intl.formatMessage(apv.underItem, { title: at.parent.title }) : (at?.section.title ?? '')
  return (
    <FormDialog
      cancelLabel={intl.formatMessage(ap.cancel)}
      title={intl.formatMessage(apv.moveToPositionTitle, { title: item.title })}
      description={intl.formatMessage(apv.positionLead, { count, where })}
      submitLabel={intl.formatMessage(ap.move)}
      onClose={onClose}
      onSubmit={() => {
        const n = Number(position)
        if (!Number.isInteger(n) || n < 1 || n > count) {
          setError(intl.formatMessage(apv.positionRange, { count }))
          return Promise.resolve(undefined)
        }
        onClose()
        if (at && n - 1 !== at.index) {
          onMove({
            itemId: item.id,
            toSectionId: at.section.id,
            toParentId: at.parent?.id ?? null,
            toPosition: n - 1,
          })
        }
        return Promise.resolve(undefined)
      }}
    >
      <Field label={intl.formatMessage(ap.position)} required error={error}>
        {(p) => (
          <Input
            {...p}
            type="number"
            inputMode="numeric"
            min={1}
            max={count}
            className="w-28"
            value={position}
            onChange={(e) => {
              setPosition(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

function RemoveItemDialog({ item, onClose }: { item: Item; onClose: () => void }) {
  const intl = useIntl()
  const { meeting, change, announce } = useAgenda()
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(apv.removeTitle, { title: item.title })}
      description={intl.formatMessage(
        item.kind === 'SUBMISSION' ? ap.removeSubmission : item.subItems.length ? ap.removeWithSubItems : ap.removeText,
      )}
      confirmLabel={intl.formatMessage(ap.removeConfirm)}
      cancelLabel={intl.formatMessage(ap.keep)}
      destructive
      onConfirm={() => {
        void change((headers) =>
          removeAgendaItem({ path: { meetingId: meeting.id, itemId: item.id }, headers, throwOnError: true }),
        ).then((out) => {
          announce(out.draft ? intl.formatMessage(apv.removed, { title: item.title }) : out.refusal)
        })
      }}
    />
  )
}

/** A section's ⋯ menu: rename and flags, moves, removal (only when empty). */
function SectionMenu({
  section,
  index,
  count,
  onMove,
}: {
  section: Section
  index: number
  count: number
  onMove: (body: AgendaMoveRequest) => void
}) {
  const intl = useIntl()
  const { draft, meeting, change, announce } = useAgenda()
  const [dialog, setDialog] = useState<'edit' | 'remove'>()
  const toggle = async (overrides: Parameters<typeof sectionChange>[1], done: string) => {
    const out = await change((headers) =>
      updateAgendaSection({
        path: { meetingId: meeting.id, sectionId: section.id },
        headers,
        body: sectionChange(section, overrides),
        throwOnError: true,
      }),
    )
    announce(out.draft ? done : out.refusal)
  }
  const move = (up: boolean) => {
    const body = sectionMove(draft, section.id, up)
    if (body) onMove(body)
  }
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(apv.sectionActions, { title: section.title })}
          >
            <EllipsisVertical aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="min-w-64">
          <DropdownMenuItem
            onSelect={() => {
              setDialog('edit')
            }}
          >
            {intl.formatMessage(ap.renameSection)}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() =>
              void toggle(
                { kind: section.kind === 'CONSENT' ? 'STANDARD' : 'CONSENT' },
                intl.formatMessage(section.kind === 'CONSENT' ? apv.consentOff : apv.consentOn, {
                  title: section.title,
                }),
              )
            }
          >
            {intl.formatMessage(section.kind === 'CONSENT' ? ap.consentOffAction : ap.consentOnAction)}
          </DropdownMenuItem>
          <DropdownMenuItem
            onSelect={() =>
              void toggle(
                { membersOnly: !section.membersOnly },
                intl.formatMessage(section.membersOnly ? apv.membersOnlyOff : apv.membersOnlyOn, {
                  title: section.title,
                }),
              )
            }
          >
            {intl.formatMessage(section.membersOnly ? ap.membersOnlyOffAction : ap.membersOnlyOnAction)}
          </DropdownMenuItem>
          {!section.receivesSubmissions ? (
            <DropdownMenuItem
              onSelect={() =>
                void toggle(
                  { receivesSubmissions: true },
                  intl.formatMessage(apv.landsHereOn, { title: section.title }),
                )
              }
            >
              {intl.formatMessage(ap.landsHereAction)}
            </DropdownMenuItem>
          ) : null}
          <DropdownMenuSeparator />
          <DropdownMenuItem
            disabled={index === 0}
            onSelect={() => {
              move(true)
            }}
          >
            {intl.formatMessage(ap.moveSectionUp)}
          </DropdownMenuItem>
          <DropdownMenuItem
            disabled={index === count - 1}
            onSelect={() => {
              move(false)
            }}
          >
            {intl.formatMessage(ap.moveSectionDown)}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem
            className="text-destructive"
            disabled={section.items.length > 0}
            onSelect={() => {
              setDialog('remove')
            }}
          >
            {intl.formatMessage(ap.removeSection)}
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
      {dialog === 'edit' ? (
        <SectionDialog
          section={section}
          onClose={() => {
            setDialog(undefined)
          }}
        />
      ) : null}
      {dialog === 'remove' ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) setDialog(undefined)
          }}
          title={intl.formatMessage(apv.removeSectionTitle, { title: section.title })}
          description={intl.formatMessage(ap.removeSectionText)}
          confirmLabel={intl.formatMessage(ap.removeConfirm)}
          cancelLabel={intl.formatMessage(ap.keep)}
          destructive
          onConfirm={() => {
            void change((headers) =>
              removeAgendaSection({
                path: { meetingId: meeting.id, sectionId: section.id },
                headers,
                throwOnError: true,
              }),
            ).then((out) => {
              announce(out.draft ? intl.formatMessage(apv.removed, { title: section.title }) : out.refusal)
            })
          }}
        />
      ) : null}
    </>
  )
}

/** Adds a section, or renames one and sets its flags. */
function SectionDialog({ section, onClose }: { section?: Section; onClose: () => void }) {
  const intl = useIntl()
  const { meeting, change, announce } = useAgenda()
  const [title, setTitle] = useState(section?.title ?? '')
  const [consent, setConsent] = useState(section?.kind === 'CONSENT')
  const [membersOnly, setMembersOnly] = useState(section?.membersOnly ?? false)
  const [receives, setReceives] = useState(section?.receivesSubmissions ?? false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const submit = async () => {
    if (!title.trim()) {
      setErrors({ title: intl.formatMessage(ap.required) })
      return undefined
    }
    const body = {
      title: title.trim(),
      kind: consent ? ('CONSENT' as const) : ('STANDARD' as const),
      membersOnly,
      receivesSubmissions: receives,
    }
    const path = { meetingId: meeting.id }
    const out = await change((headers) =>
      section
        ? updateAgendaSection({ path: { ...path, sectionId: section.id }, headers, body, throwOnError: true })
        : addAgendaSection({
            path,
            headers: { ...headers, 'Idempotency-Key': newIdempotencyKey() },
            body,
            throwOnError: true,
          }),
    )
    if (out.draft) {
      announce(intl.formatMessage(section ? ap.saved : apv.sectionAdded, { title: body.title }))
      onClose()
      return undefined
    }
    setErrors(out.fields)
    return Object.keys(out.fields).length ? undefined : out.refusal
  }
  return (
    <FormDialog
      cancelLabel={intl.formatMessage(ap.cancel)}
      title={
        section ? intl.formatMessage(apv.editSectionTitle, { title: section.title }) : intl.formatMessage(ap.addSection)
      }
      submitLabel={intl.formatMessage(section ? ap.save : ap.addSectionSubmit)}
      onClose={onClose}
      onSubmit={submit}
    >
      <Field label={intl.formatMessage(ap.sectionTitle)} required error={errors.title}>
        {(p) => (
          <Input
            {...p}
            dir="auto"
            maxLength={200}
            value={title}
            onChange={(e) => {
              setTitle(e.target.value)
            }}
          />
        )}
      </Field>
      <CheckField
        label={intl.formatMessage(ap.consentField)}
        hint={intl.formatMessage(ap.consentHint)}
        checked={consent}
        onChange={setConsent}
      />
      <CheckField
        label={intl.formatMessage(ap.membersOnlyField)}
        hint={intl.formatMessage(ap.membersOnlyHint)}
        checked={membersOnly}
        onChange={setMembersOnly}
      />
      <CheckField
        label={intl.formatMessage(ap.landsHereField)}
        hint={intl.formatMessage(ap.landsHereHint)}
        checked={receives}
        onChange={setReceives}
      />
    </FormDialog>
  )
}

type AddKind = 'ITEM' | 'BREAK' | 'STANDING'

/** Adds an item, a break, a standing item or a sub-item (flow O2's "+ Add item"). */
function AddItemDialog({
  sectionId,
  parentId,
  onClose,
}: {
  sectionId?: string
  parentId?: string
  onClose: () => void
}) {
  const intl = useIntl()
  const { draft, meeting, change, announce } = useAgenda()
  const where = useWhere()
  const parent = parentId ? locate(draft, parentId)?.siblings.find((i) => i.id === parentId) : undefined
  const receiving = draft.sections.find((s) => s.receivesSubmissions)
  const [kind, setKind] = useState<AddKind>('ITEM')
  const [standingKind, setStandingKind] = useState<(typeof STANDING_KINDS)[number]>('AOB')
  const [title, setTitle] = useState('')
  const [section, setSection] = useState(sectionId ?? receiving?.id ?? draft.sections[0]?.id ?? '')
  const [purpose, setPurpose] = useState<Purpose>('INFORMATION')
  const [minutes, setMinutes] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const kinds: AddKind[] = parent ? ['ITEM'] : ['ITEM', 'BREAK', 'STANDING']
  const used = new Set(
    draft.sections.flatMap((s) => s.items.flatMap((i) => [i, ...i.subItems])).map((i) => i.standingKind),
  )

  const submit = async () => {
    const shown =
      kind === 'BREAK'
        ? intl.formatMessage(ap.breakTitle)
        : title.trim() || (kind === 'STANDING' ? intl.formatMessage(standingLabels[standingKind]) : '')
    const missing: Record<string, string> = {}
    if (!shown) missing.title = intl.formatMessage(ap.required)
    if (minutes && (!/^\d+$/.test(minutes) || Number(minutes) > 600))
      missing.minutes = intl.formatMessage(ap.minutesRange)
    setErrors(missing)
    if (Object.keys(missing).length) return undefined
    const before = new Set(
      draft.sections.flatMap((s) => s.items.flatMap((i) => [i.id, ...i.subItems.map((x) => x.id)])),
    )
    const body: AgendaItemRequest = {
      kind,
      title: shown,
      sectionId: parent ? null : section,
      parentId: parent?.id ?? null,
      ...(kind === 'STANDING' ? { standingKind } : {}),
      ...(kind !== 'BREAK' ? { purpose } : {}),
      minutes: parent || !minutes ? null : Number(minutes),
    }
    const out = await change((headers) =>
      addAgendaItem({
        path: { meetingId: meeting.id },
        headers: { ...headers, 'Idempotency-Key': newIdempotencyKey() },
        body,
        throwOnError: true,
      }),
    )
    if (out.draft) {
      const added = out.draft.sections
        .flatMap((s) => s.items.flatMap((i) => [i, ...i.subItems]))
        .find((i) => !before.has(i.id))
      if (added) {
        announce(where(out.draft, added.id, true))
        focusRow(added.id)
      }
      onClose()
      return undefined
    }
    setErrors(out.fields)
    return Object.keys(out.fields).length ? undefined : out.refusal
  }

  return (
    <FormDialog
      cancelLabel={intl.formatMessage(ap.cancel)}
      title={parent ? intl.formatMessage(apv.addSubItemTitle, { title: parent.title }) : intl.formatMessage(ap.addItem)}
      description={meeting.status === 'IN_PROGRESS' ? intl.formatMessage(ap.tabledHint) : undefined}
      submitLabel={intl.formatMessage(ap.addItemSubmit)}
      onClose={onClose}
      onSubmit={submit}
    >
      {kinds.length > 1 ? (
        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.whatToAdd)}</legend>
          {kinds.map((k) => (
            <label key={k} className="flex min-h-11 items-center gap-2 text-sm">
              <input
                type="radio"
                name="kind"
                className="size-4 accent-primary"
                checked={kind === k}
                onChange={() => {
                  setKind(k)
                }}
              />
              {intl.formatMessage(ap[`kind${k}`])}
            </label>
          ))}
        </fieldset>
      ) : null}
      {kind === 'STANDING' ? (
        <Field label={intl.formatMessage(ap.standingKind)} required error={errors.standingKind}>
          {(p) => (
            <Select
              {...p}
              value={standingKind}
              onChange={(e) => {
                setStandingKind(e.target.value as typeof standingKind)
              }}
            >
              {STANDING_KINDS.map((k) => (
                <option key={k} value={k} disabled={used.has(k)}>
                  {intl.formatMessage(standingLabels[k])}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {kind !== 'BREAK' ? (
        <Field
          label={intl.formatMessage(ap.itemTitle)}
          required={kind !== 'STANDING'}
          optionalLabel={intl.formatMessage(ap.optional)}
          hint={kind === 'STANDING' ? intl.formatMessage(ap.standingTitleHint) : undefined}
          error={errors.title}
        >
          {(p) => (
            <Input
              {...p}
              dir="auto"
              maxLength={300}
              value={title}
              onChange={(e) => {
                setTitle(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
      {!parent ? (
        <Field label={intl.formatMessage(ap.section)} required error={errors.sectionId}>
          {(p) => (
            <Select
              {...p}
              value={section}
              onChange={(e) => {
                setSection(e.target.value)
              }}
            >
              {draft.sections.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.title}
                </option>
              ))}
            </Select>
          )}
        </Field>
      ) : null}
      {kind !== 'BREAK' ? (
        <Field label={intl.formatMessage(ap.purpose)} required>
          {(p) => (
            <Select
              {...p}
              value={purpose}
              onChange={(e) => {
                setPurpose(e.target.value as Purpose)
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
      ) : null}
      {!parent ? (
        <Field
          label={intl.formatMessage(ap.minutes)}
          optionalLabel={intl.formatMessage(ap.optional)}
          error={errors.minutes}
        >
          {(p) => (
            <Input
              {...p}
              type="number"
              inputMode="numeric"
              min={0}
              max={600}
              className="w-28"
              value={minutes}
              onChange={(e) => {
                setMinutes(e.target.value)
              }}
            />
          )}
        </Field>
      ) : (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.subItemNoTime)}</p>
      )}
    </FormDialog>
  )
}
