import { useQuery, useQueryClient } from '@tanstack/react-query'
import { EllipsisVertical, FileText, X } from 'lucide-react'
import { useId, useState, type ReactNode } from 'react'
import { useIntl } from 'react-intl'
import { getAgendaDraftQueryKey, getFileOptions } from '@/api/generated/@tanstack/react-query.gen'
import { addAgendaPaper, removeAgendaPaper, updateAgendaItem, updateAgendaPaper } from '@/api/generated/sdk.gen'
import type { AgendaItemChangeRequest } from '@/api/generated/types.gen'
import { FileUpload } from '@/components/page/FileUpload'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { AlertDialog, Dialog } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Field } from '@/components/ui/field'
import { Input, Select, Textarea } from '@/components/ui/form-controls'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/misc'
import { FormDialog } from '@/features/forums/FormDialog'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { newIdempotencyKey } from '@/lib/idempotency'
import { CheckField } from './CheckField'
import { itemChange, locate, paperChange, PURPOSES, type Item, type Paper, type Purpose } from './draft'
import { ap, apv, purposeLabels, sourceLabels } from './messages'
import { useAgenda, type Person, type SheetTab } from './useAgenda'

const PAPER_TYPES = '.pdf,.png,.jpg,.jpeg,.webp,.txt,.docx,.xlsx,.pptx,.odt,.ods,.odp'
const PAPER_MAX_BYTES = 50 * 1024 * 1024

/**
 * The item's side sheet (flow O3): Details, Papers and "Who can see this" as tabs of one sheet, opened by the row's
 * Edit…, Papers… and "Who can see this…". Each tab saves on its own; every save sends the whole item (the server
 * replaces it), so one tab never clears what another set.
 */
export function ItemSheet({ itemId, tab, onClose }: { itemId: string; tab: SheetTab; onClose: () => void }) {
  const intl = useIntl()
  const { draft, editable } = useAgenda()
  const at = locate(draft, itemId)
  const item = at?.siblings[at.index]
  const [current, setCurrent] = useState<SheetTab>(tab)
  if (!item) return null
  const isBreak = item.kind === 'BREAK'
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={item.number ? intl.formatMessage(apv.sheetTitle, { number: item.number, title: item.title }) : item.title}
      description={
        item.memoReference ? intl.formatMessage(apv.fromQueue, { reference: item.memoReference }) : undefined
      }
      className="fixed inset-y-0 end-0 start-auto m-0 h-dvh max-h-none w-full max-w-full overflow-y-auto rounded-none border-y-0 border-e-0 sm:w-[40rem]"
    >
      <Tabs
        value={current}
        onValueChange={(v) => {
          setCurrent(v as SheetTab)
        }}
      >
        <TabsList aria-label={intl.formatMessage(ap.sheetTabs)}>
          {editable ? <TabsTrigger value="details">{intl.formatMessage(ap.tabDetails)}</TabsTrigger> : null}
          {!isBreak ? <TabsTrigger value="papers">{intl.formatMessage(ap.tabPapers)}</TabsTrigger> : null}
          {editable && !isBreak ? <TabsTrigger value="access">{intl.formatMessage(ap.tabAccess)}</TabsTrigger> : null}
        </TabsList>
        {editable ? (
          <TabsContent value="details" className="pt-4">
            <DetailsTab item={item} subItem={Boolean(at.parent)} onClose={onClose} />
          </TabsContent>
        ) : null}
        {!isBreak ? (
          <TabsContent value="papers" className="pt-4">
            <PapersTab item={item} />
          </TabsContent>
        ) : null}
        {editable && !isBreak ? (
          <TabsContent value="access" className="pt-4">
            <AccessTab item={item} onClose={onClose} />
          </TabsContent>
        ) : null}
      </Tabs>
    </Dialog>
  )
}

/** Saves the item with [overrides]; answers a refusal (or field problems) to show. */
function useSaveItem(item: Item) {
  const { meeting, change, announce } = useAgenda()
  const intl = useIntl()
  return async (overrides: Partial<AgendaItemChangeRequest>) => {
    const out = await change((headers) =>
      updateAgendaItem({
        path: { meetingId: meeting.id, itemId: item.id },
        headers,
        body: itemChange(item, overrides),
        throwOnError: true,
      }),
    )
    if (out.draft) announce(intl.formatMessage(apv.itemSaved, { title: overrides.title ?? item.title }))
    return out
  }
}

/** A save button and the refusal of a tab, in words (role="alert"). */
function TabActions({
  busy,
  refusal,
  label,
  onCancel,
}: {
  busy: boolean
  refusal?: string
  label: string
  onCancel: () => void
}) {
  const intl = useIntl()
  return (
    <>
      {refusal ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
        >
          {refusal}
        </p>
      ) : null}
      <div className="flex flex-wrap justify-end gap-2 border-t pt-4">
        <Button variant="outline" onClick={onCancel}>
          {intl.formatMessage(ap.close)}
        </Button>
        <Button type="submit" disabled={busy}>
          {label}
        </Button>
      </div>
    </>
  )
}

interface PresenterRow {
  key: string
  userId?: string
  name: string
}

function DetailsTab({ item, subItem, onClose }: { item: Item; subItem: boolean; onClose: () => void }) {
  const intl = useIntl()
  const save = useSaveItem(item)
  const isBreak = item.kind === 'BREAK'
  const [title, setTitle] = useState(item.title)
  const [purpose, setPurpose] = useState<Purpose>(item.purpose ?? 'INFORMATION')
  const [resolution, setResolution] = useState(item.resolutionSought ?? '')
  const [description, setDescription] = useState(item.description ?? '')
  const [minutes, setMinutes] = useState(item.minutes == null ? '' : String(item.minutes))
  const [presenters, setPresenters] = useState<PresenterRow[]>(
    item.presenters.map((p, i) => ({ key: String(i), ...(p.userId ? { userId: p.userId } : {}), name: p.name })),
  )
  const presenterIds = new Set(item.presenters.flatMap((p) => (p.userId ? [p.userId] : [])))
  const [attendees, setAttendees] = useState<Person[]>(item.attendees.filter((a) => !presenterIds.has(a.id)))
  const [typed, setTyped] = useState('')
  const [picking, setPicking] = useState<Picked>()
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const [busy, setBusy] = useState(false)
  const typedId = useId()

  const submit = async () => {
    const missing: Record<string, string> = {}
    if (!title.trim()) missing.title = intl.formatMessage(ap.required)
    if (minutes && (!/^\d+$/.test(minutes) || Number(minutes) > 600))
      missing.minutes = intl.formatMessage(ap.minutesRange)
    setErrors(missing)
    setRefusal(undefined)
    if (Object.keys(missing).length) return
    setBusy(true)
    const out = await save({
      title: title.trim(),
      ...(isBreak ? {} : { purpose }),
      resolutionSought: resolution.trim() || null,
      description: description.trim() || null,
      minutes: subItem || !minutes ? null : Number(minutes),
      presenters: presenters.map((p) => (p.userId ? { userId: p.userId } : { name: p.name })),
      attendees: [...attendees.map((a) => a.id), ...presenters.flatMap((p) => (p.userId ? [p.userId] : []))],
    })
    setBusy(false)
    if (out.draft) return
    setErrors(out.fields)
    if (!Object.keys(out.fields).length) setRefusal(out.refusal)
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault()
        void submit()
      }}
    >
      <Field label={intl.formatMessage(ap.itemTitle)} required error={errors.title}>
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
      {!isBreak ? (
        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.purpose)}</legend>
          <div className="flex flex-wrap gap-x-5">
            {PURPOSES.map((x) => (
              <label key={x} className="flex min-h-11 items-center gap-2 text-sm">
                <input
                  type="radio"
                  name="purpose"
                  className="size-4 accent-primary"
                  checked={purpose === x}
                  onChange={() => {
                    setPurpose(x)
                  }}
                />
                {intl.formatMessage(purposeLabels[x])}
              </label>
            ))}
          </div>
        </fieldset>
      ) : null}
      {!isBreak ? (
        <Field
          label={intl.formatMessage(ap.resolutionSought)}
          optionalLabel={intl.formatMessage(ap.optional)}
          error={errors.resolutionSought}
        >
          {(p) => (
            <Textarea
              {...p}
              dir="auto"
              rows={3}
              maxLength={2000}
              value={resolution}
              onChange={(e) => {
                setResolution(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
      {!isBreak ? (
        <Field
          label={intl.formatMessage(ap.description)}
          optionalLabel={intl.formatMessage(ap.optional)}
          error={errors.description}
        >
          {(p) => (
            <Textarea
              {...p}
              dir="auto"
              rows={2}
              maxLength={2000}
              value={description}
              onChange={(e) => {
                setDescription(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
      {!subItem ? (
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
      {!isBreak ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.presentersField)}</legend>
          <PeopleChips
            people={presenters.map((p) => ({ id: p.key, name: p.name }))}
            onRemove={(key) => {
              setPresenters(presenters.filter((p) => p.key !== key))
            }}
          />
          {errors.presenters ? <p className="text-xs font-medium text-destructive">{errors.presenters}</p> : null}
          {presenters.length < 5 ? (
            <div className="flex flex-col gap-2">
              <label className="text-sm" htmlFor={`${typedId}-person`}>
                {intl.formatMessage(ap.presenterPerson)}
              </label>
              <PeoplePicker
                id={`${typedId}-person`}
                kind="user"
                value={picking}
                onChange={(picked) => {
                  if (!picked) return
                  setPicking(undefined)
                  if (!presenters.some((p) => p.userId === picked.id)) {
                    setPresenters([...presenters, { key: picked.id, userId: picked.id, name: picked.label }])
                  }
                }}
              />
              <label className="text-sm" htmlFor={typedId}>
                {intl.formatMessage(ap.presenterName)}
              </label>
              <div className="flex flex-wrap gap-2">
                <Input
                  id={typedId}
                  dir="auto"
                  maxLength={200}
                  className="flex-1"
                  value={typed}
                  onChange={(e) => {
                    setTyped(e.target.value)
                  }}
                />
                <Button
                  variant="outline"
                  disabled={!typed.trim()}
                  onClick={() => {
                    setPresenters([...presenters, { key: `name-${typed.trim()}`, name: typed.trim() }])
                    setTyped('')
                  }}
                >
                  {intl.formatMessage(ap.addName)}
                </Button>
              </div>
            </div>
          ) : null}
        </fieldset>
      ) : null}
      {!isBreak ? (
        <fieldset className="flex flex-col gap-2">
          <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.attendeesField)}</legend>
          <p className="text-xs text-muted-foreground">{intl.formatMessage(ap.attendeesHint)}</p>
          <PeopleChips
            people={attendees}
            onRemove={(id) => {
              setAttendees(attendees.filter((a) => a.id !== id))
            }}
          />
          {errors.attendees ? <p className="text-xs font-medium text-destructive">{errors.attendees}</p> : null}
          <label className="text-sm" htmlFor={`${typedId}-attendee`}>
            {intl.formatMessage(ap.addAttendee)}
          </label>
          <PeoplePicker
            id={`${typedId}-attendee`}
            kind="user"
            onChange={(picked) => {
              if (picked && !attendees.some((a) => a.id === picked.id)) {
                setAttendees([...attendees, { id: picked.id, name: picked.label }])
              }
            }}
          />
        </fieldset>
      ) : null}
      <TabActions busy={busy} refusal={refusal} label={intl.formatMessage(ap.save)} onCancel={onClose} />
    </form>
  )
}

function PeopleChips({ people, onRemove }: { people: Person[]; onRemove: (id: string) => void }) {
  const intl = useIntl()
  if (!people.length) return <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.nobody)}</p>
  return (
    <ul className="flex flex-wrap gap-2">
      {people.map((p) => (
        <li key={p.id} className="flex items-center gap-1 rounded-md border ps-3">
          <span dir="auto" className="text-sm">
            {p.name}
          </span>
          <Button
            variant="ghost"
            size="icon"
            aria-label={intl.formatMessage(apv.removePerson, { name: p.name })}
            onClick={() => {
              onRemove(p.id)
            }}
          >
            <X aria-hidden="true" />
          </Button>
        </li>
      ))}
    </ul>
  )
}

/** The levels an item or paper may take: never below the meeting's (design E03 §1.2, `CLASSIFICATION`). */
function useLevels(floorId: string) {
  const { levels } = useAgenda()
  const floor = levels.find((l) => l.id === floorId)?.rank ?? 0
  return levels.filter((l) => l.rank >= floor).sort((a, b) => a.rank - b.rank)
}

/** "Only these people": checkboxes over the meeting's audience. */
function Restriction({
  name,
  only,
  chosen,
  onOnly,
  onChosen,
  error,
  everyoneLabel,
}: {
  name: string
  only: boolean
  chosen: string[]
  onOnly: (only: boolean) => void
  onChosen: (ids: string[]) => void
  error?: string
  everyoneLabel: string
}) {
  const intl = useIntl()
  const { audience } = useAgenda()
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-sm font-medium">{intl.formatMessage(ap.whoCanSeeField)}</legend>
      {[false, true].map((value) => (
        <label key={String(value)} className="flex min-h-11 items-center gap-2 text-sm">
          <input
            type="radio"
            name={name}
            className="size-4 accent-primary"
            checked={only === value}
            onChange={() => {
              onOnly(value)
            }}
          />
          {value ? intl.formatMessage(ap.onlyThese) : everyoneLabel}
        </label>
      ))}
      {only ? (
        <div className="ms-6 flex flex-col gap-1">
          <p className="text-xs text-muted-foreground">{intl.formatMessage(ap.onlyTheseHint)}</p>
          <ul
            aria-label={intl.formatMessage(ap.onlyThese)}
            className="flex max-h-64 flex-col overflow-y-auto rounded-md border p-1"
          >
            {audience.map((person) => (
              <li key={person.id}>
                <label className="flex min-h-11 items-center gap-2 px-2 text-sm">
                  <input
                    type="checkbox"
                    className="size-4 accent-primary"
                    checked={chosen.includes(person.id)}
                    onChange={(e) => {
                      onChosen(e.target.checked ? [...chosen, person.id] : chosen.filter((id) => id !== person.id))
                    }}
                  />
                  <span dir="auto">{person.name}</span>
                </label>
              </li>
            ))}
          </ul>
          {error ? <p className="text-xs font-medium text-destructive">{error}</p> : null}
        </div>
      ) : null}
    </fieldset>
  )
}

function AccessTab({ item, onClose }: { item: Item; onClose: () => void }) {
  const intl = useIntl()
  const { meeting } = useAgenda()
  const save = useSaveItem(item)
  const levels = useLevels(meeting.classificationId)
  const [only, setOnly] = useState(Boolean(item.restrictedTo))
  const [chosen, setChosen] = useState<string[]>(item.restrictedTo?.map((p) => p.id) ?? [])
  const [hidden, setHidden] = useState(item.titleHidden)
  const [level, setLevel] = useState(item.classificationId)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setRefusal(undefined)
    if (only && !chosen.length) {
      setErrors({ restrictedTo: intl.formatMessage(ap.restrictionEmpty) })
      return
    }
    setErrors({})
    setBusy(true)
    const out = await save({ restrictedTo: only ? chosen : null, titleHidden: hidden, classificationId: level })
    setBusy(false)
    if (out.draft) return
    setErrors(out.fields)
    if (!Object.keys(out.fields).length) setRefusal(out.refusal)
  }

  return (
    <form
      noValidate
      className="flex flex-col gap-5"
      onSubmit={(e) => {
        e.preventDefault()
        void submit()
      }}
    >
      <Restriction
        name="item-access"
        only={only}
        chosen={chosen}
        onOnly={setOnly}
        onChosen={setChosen}
        error={errors.restrictedTo}
        everyoneLabel={intl.formatMessage(ap.everyone)}
      />
      <CheckField
        label={intl.formatMessage(ap.hideTitle)}
        hint={intl.formatMessage(ap.hideTitleHint)}
        checked={hidden}
        onChange={setHidden}
      />
      <Field label={intl.formatMessage(ap.classification)} error={errors.classificationId}>
        {(p) => (
          <Select
            {...p}
            value={level}
            onChange={(e) => {
              setLevel(e.target.value)
            }}
          >
            {levels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
      <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.conflictsLater)}</p>
      <TabActions busy={busy} refusal={refusal} label={intl.formatMessage(ap.save)} onCancel={onClose} />
    </form>
  )
}

/** Papers (flow O3): the pack in the order members see it, memo files left out, uploads, and each paper's ⋯ menu. */
function PapersTab({ item }: { item: Item }) {
  const intl = useIntl()
  const { meeting, editable, canAdd, change, announce } = useAgenda()
  const [refusal, setRefusal] = useState<string>()
  const add = async (fileId: string, title?: string) => {
    setRefusal(undefined)
    const out = await change((headers) =>
      addAgendaPaper({
        path: { meetingId: meeting.id, itemId: item.id },
        headers: { ...headers, 'Idempotency-Key': newIdempotencyKey() },
        body: { fileId, ...(title ? { title } : {}) },
        throwOnError: true,
      }),
    )
    if (out.draft) announce(intl.formatMessage(ap.paperAdded))
    else setRefusal(out.refusal)
  }
  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.papersLead)}</p>
      {item.papers.length ? (
        <ol aria-label={intl.formatMessage(ap.tabPapers)} className="flex flex-col divide-y rounded-md border">
          {item.papers.map((paper) => (
            <PaperRow key={paper.id} paper={paper} item={item} onRefusal={setRefusal} />
          ))}
        </ol>
      ) : (
        <p>{intl.formatMessage(ap.noPapers)}</p>
      )}
      {item.outOfPack.length ? (
        <section aria-labelledby={`out-${item.id}`} className="flex flex-col gap-2">
          <h3 id={`out-${item.id}`} className="text-sm font-semibold">
            {intl.formatMessage(ap.outOfPack)}
          </h3>
          <ul className="flex flex-col divide-y rounded-md border">
            {item.outOfPack.map((f) => (
              <li key={f.fileId} className="flex min-h-11 flex-wrap items-center justify-between gap-2 px-3 py-1">
                <span dir="auto" className="text-sm">
                  {intl.formatMessage(apv.outOfPackFile, {
                    title: f.title,
                    source: intl.formatMessage(sourceLabels[f.source]),
                  })}
                </span>
                {editable ? (
                  <Button variant="outline" onClick={() => void add(f.fileId)}>
                    {intl.formatMessage(ap.putBack)}
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {refusal ? (
        <p
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm text-destructive"
        >
          {refusal}
        </p>
      ) : null}
      {canAdd && item.papers.length < 20 ? (
        <FileUpload
          label={intl.formatMessage(ap.uploadPaper)}
          hint={intl.formatMessage(meeting.status === 'IN_PROGRESS' ? ap.uploadTabledHint : ap.uploadHint)}
          purpose="meeting.paper"
          owner={meeting.id}
          accept={PAPER_TYPES}
          maxBytes={PAPER_MAX_BYTES}
          onUploaded={(uploaded) => void add(uploaded.fileId)}
        />
      ) : null}
    </div>
  )
}

function PaperRow({ paper, item, onRefusal }: { paper: Paper; item: Item; onRefusal: (text?: string) => void }) {
  const intl = useIntl()
  const { meeting, editable, change, announce } = useAgenda()
  const [dialog, setDialog] = useState<'rename' | 'replace' | 'access' | 'remove'>()
  const facts = [
    intl.formatMessage(sourceLabels[paper.source]),
    paper.pages ? intl.formatMessage(apv.pages, { count: paper.pages }) : undefined,
    paper.late === 'AWAITING_CHAIR'
      ? intl.formatMessage(ap.paperAwaitingChair)
      : paper.late !== 'NONE'
        ? intl.formatMessage(ap[`late${paper.late}`])
        : undefined,
  ].filter(Boolean)
  const close = () => {
    setDialog(undefined)
  }
  return (
    <li className="flex min-h-11 items-start gap-2 px-3 py-1">
      <FileText aria-hidden="true" className="mt-3 size-4 shrink-0 text-muted-foreground" />
      <div className="mt-2 flex min-w-0 flex-1 flex-col gap-0.5">
        <span dir="auto" className="text-sm font-medium">
          {paper.title}
        </span>
        <span className="text-xs text-muted-foreground">{facts.join(' · ')}</span>
        {paper.restrictedTo ? (
          <Badge tone="warn" className="w-fit">
            {intl.formatMessage(apv.paperRestricted, { count: paper.restrictedTo.length })}
          </Badge>
        ) : null}
      </div>
      {editable ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              aria-label={intl.formatMessage(apv.paperActions, { title: paper.title })}
            >
              <EllipsisVertical aria-hidden="true" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="min-w-60">
            <DropdownMenuItem
              onSelect={() => {
                setDialog('rename')
              }}
            >
              {intl.formatMessage(ap.rename)}
            </DropdownMenuItem>
            {paper.source === 'UPLOAD' ? (
              <DropdownMenuItem
                onSelect={() => {
                  setDialog('replace')
                }}
              >
                {intl.formatMessage(ap.replace)}
              </DropdownMenuItem>
            ) : null}
            <DropdownMenuItem
              onSelect={() => {
                setDialog('access')
              }}
            >
              {intl.formatMessage(ap.onlyTheseAction)}
            </DropdownMenuItem>
            <DropdownMenuItem
              className="text-destructive"
              onSelect={() => {
                setDialog('remove')
              }}
            >
              {intl.formatMessage(ap.removeFromPack)}
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : null}
      {dialog === 'rename' ? <RenamePaperDialog paper={paper} onClose={close} /> : null}
      {dialog === 'replace' ? <ReplacePaperDialog paper={paper} onClose={close} /> : null}
      {dialog === 'access' ? <PaperAccessDialog paper={paper} item={item} onClose={close} /> : null}
      {dialog === 'remove' ? (
        <AlertDialog
          open
          onOpenChange={(open) => {
            if (!open) close()
          }}
          title={intl.formatMessage(apv.removePaperTitle, { title: paper.title })}
          description={intl.formatMessage(paper.source === 'UPLOAD' ? ap.removeUploadText : ap.removeMemoPaperText)}
          confirmLabel={intl.formatMessage(ap.removeFromPack)}
          cancelLabel={intl.formatMessage(ap.keep)}
          destructive
          onConfirm={() => {
            void change((headers) =>
              removeAgendaPaper({ path: { meetingId: meeting.id, paperId: paper.id }, headers, throwOnError: true }),
            ).then((out) => {
              onRefusal(out.refusal)
              announce(out.draft ? intl.formatMessage(apv.removed, { title: paper.title }) : out.refusal)
            })
          }}
        />
      ) : null}
    </li>
  )
}

function PaperDialog({
  title,
  description,
  onSubmit,
  onClose,
  children,
}: {
  title: string
  description?: string
  onSubmit: () => Promise<string | undefined>
  onClose: () => void
  children: ReactNode
}) {
  const intl = useIntl()
  return (
    <FormDialog
      title={title}
      description={description}
      submitLabel={intl.formatMessage(ap.save)}
      cancelLabel={intl.formatMessage(ap.cancel)}
      onSubmit={onSubmit}
      onClose={onClose}
    >
      {children}
    </FormDialog>
  )
}

function useSavePaper(paper: Paper, onClose: () => void) {
  const { meeting, change, announce } = useAgenda()
  const intl = useIntl()
  return async (overrides: Parameters<typeof paperChange>[1], setErrors: (e: Record<string, string>) => void) => {
    const out = await change((headers) =>
      updateAgendaPaper({
        path: { meetingId: meeting.id, paperId: paper.id },
        headers,
        body: paperChange(paper, overrides),
        throwOnError: true,
      }),
    )
    if (out.draft) {
      announce(intl.formatMessage(ap.saved))
      onClose()
      return undefined
    }
    setErrors(out.fields)
    return Object.keys(out.fields).length ? undefined : out.refusal
  }
}

function RenamePaperDialog({ paper, onClose }: { paper: Paper; onClose: () => void }) {
  const intl = useIntl()
  const save = useSavePaper(paper, onClose)
  const [title, setTitle] = useState(paper.title)
  const [errors, setErrors] = useState<Record<string, string>>({})
  return (
    <PaperDialog
      title={intl.formatMessage(apv.renamePaperTitle, { title: paper.title })}
      onClose={onClose}
      onSubmit={() => {
        if (!title.trim()) {
          setErrors({ title: intl.formatMessage(ap.required) })
          return Promise.resolve(undefined)
        }
        return save({ title: title.trim() }, setErrors)
      }}
    >
      <Field label={intl.formatMessage(ap.paperTitle)} required error={errors.title}>
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
    </PaperDialog>
  )
}

function PaperAccessDialog({ paper, item, onClose }: { paper: Paper; item: Item; onClose: () => void }) {
  const intl = useIntl()
  const save = useSavePaper(paper, onClose)
  const levels = useLevels(item.classificationId)
  const [only, setOnly] = useState(Boolean(paper.restrictedTo))
  const [chosen, setChosen] = useState<string[]>(paper.restrictedTo?.map((p) => p.id) ?? [])
  const [level, setLevel] = useState(paper.classificationId)
  const [errors, setErrors] = useState<Record<string, string>>({})
  return (
    <PaperDialog
      title={intl.formatMessage(apv.paperAccessTitle, { title: paper.title })}
      description={intl.formatMessage(ap.paperAccessLead)}
      onClose={onClose}
      onSubmit={() => {
        if (only && !chosen.length) {
          setErrors({ restrictedTo: intl.formatMessage(ap.restrictionEmpty) })
          return Promise.resolve(undefined)
        }
        return save({ restrictedTo: only ? chosen : null, classificationId: level }, setErrors)
      }}
    >
      <Restriction
        name="paper-access"
        only={only}
        chosen={chosen}
        onOnly={setOnly}
        onChosen={setChosen}
        error={errors.restrictedTo}
        everyoneLabel={intl.formatMessage(ap.everyoneWhoSeesItem)}
      />
      <Field label={intl.formatMessage(ap.classification)} error={errors.classificationId}>
        {(p) => (
          <Select
            {...p}
            value={level}
            onChange={(e) => {
              setLevel(e.target.value)
            }}
          >
            {levels.map((l) => (
              <option key={l.id} value={l.id}>
                {l.name}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </PaperDialog>
  )
}

/** "Replace with a new version…": a new version of the uploaded file; members see "Paper replaced" when published. */
function ReplacePaperDialog({ paper, onClose }: { paper: Paper; onClose: () => void }) {
  const intl = useIntl()
  const { meeting, announce } = useAgenda()
  const file = useQuery(getFileOptions({ path: { fileId: paper.fileId } }))
  const queryClient = useQueryClient()
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={intl.formatMessage(apv.replaceTitle, { title: paper.title })}
      description={intl.formatMessage(ap.replaceLead)}
    >
      {file.data ? (
        <FileUpload
          label={intl.formatMessage(ap.newVersion)}
          purpose="meeting.paper"
          owner={meeting.id}
          accept={PAPER_TYPES}
          maxBytes={PAPER_MAX_BYTES}
          version={{ fileId: paper.fileId, fileVersion: file.data.version }}
          onUploaded={() => {
            // The draft's papers take the new version on the server; reload it to show the new pages.
            void queryClient
              .invalidateQueries({ queryKey: getAgendaDraftQueryKey({ path: { meetingId: meeting.id } }) })
              .finally(() => {
                announce(intl.formatMessage(ap.replaced))
                onClose()
              })
          }}
        />
      ) : (
        <p className="text-sm text-muted-foreground">{intl.formatMessage(ap.loading)}</p>
      )}
    </Dialog>
  )
}
