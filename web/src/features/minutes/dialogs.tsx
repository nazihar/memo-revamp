import { X } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import {
  askForMinutesComments,
  changeMinutesDeadline,
  circulateMinutes,
  endMinutesCirculation,
  makeMinutesDraft,
  regenerateMinutesItem,
  reviewMinutes,
  sendMinutesForReview,
  withdrawMinutes,
} from '@/api/generated/sdk.gen'
import type { MinutesCopySetResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Field } from '@/components/ui/field'
import { Checkbox, Input, Textarea } from '@/components/ui/form-controls'
import { FormDialog } from '@/features/forums/FormDialog'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { useMinutesWrite } from './data'
import { mn, mnv } from './messages'
import { fieldProblems, ifMatch, itemName, reasonOf, copyLabel, refusal, type Minutes, type MinutesItem } from './words'

const MAX_NOTE = 1_000

/** Each dialog says what happened when it worked ([onDone] with the words), or shows the refusal in words. */
interface StepProps {
  page: Minutes
  onDone: (said: string) => void
  onClose: () => void
}

/** Tomorrow as `YYYY-MM-DD` in the viewer's calendar (the earliest deadline the server accepts). */
function tomorrow(): string {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  return `${String(d.getFullYear())}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** "Make the draft" over an existing one: "All your edits will be replaced" (flow AD3). */
export function RemakeDialog({ page, onDone, onClose }: StepProps) {
  const intl = useIntl()
  const write = useMinutesWrite()
  return (
    <FormDialog
      large
      destructive
      title={intl.formatMessage(mn.remakeAllTitle)}
      description={intl.formatMessage(mn.remakeAllWarning)}
      submitLabel={intl.formatMessage(mn.remakeAllConfirm)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        try {
          const body = { replaceEdits: true }
          await write(['remake', body], (key) =>
            makeMinutesDraft({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.making))
          return undefined
        } catch (problem) {
          return refusal(intl, problem)
        }
      }}
    />
  )
}

/** "Fill item 5 again from the decision and notes" ("Your edits to item 5 will be replaced"), or "Restore item 6". */
export function FillDialog({
  page,
  item,
  restore,
  onDone,
  onClose,
}: StepProps & { item: MinutesItem; restore: boolean }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  return (
    <FormDialog
      large
      title={
        restore
          ? intl.formatMessage(mnv.restore, { number: item.number })
          : intl.formatMessage(mnv.fillTitle, { number: item.number })
      }
      description={restore ? undefined : intl.formatMessage(mn.fillWarning)}
      submitLabel={
        restore
          ? intl.formatMessage(mnv.restore, { number: item.number })
          : intl.formatMessage(mnv.fillAgainNamed, { number: item.number })
      }
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        try {
          const body = { restore }
          await write(['fill', item.itemId, body], (key) =>
            regenerateMinutesItem({
              path: { meetingId: page.meetingId, itemId: item.itemId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mnv.filling, { number: item.number }))
          return undefined
        } catch (problem) {
          return refusal(intl, problem)
        }
      }}
    >
      <p className="text-base">{itemName(intl, item)}</p>
    </FormDialog>
  )
}

/** "Send to the chair" (flow AD5); with no chair or vice-chair able to review, offers circulating with a reason. */
export function SendDialog({ page, onDone, onClose, onNoReviewer }: StepProps & { onNoReviewer: () => void }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.sendTitle)}
      description={intl.formatMessage(mn.sendLead)}
      submitLabel={intl.formatMessage(mn.send)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        try {
          const headers = ifMatch(page)
          await write(['send', headers], (key) =>
            sendMinutesForReview({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key, ...headers },
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.sent))
          return undefined
        } catch (problem) {
          if (reasonOf(problem) === 'NO_REVIEWER') {
            onNoReviewer()
            return undefined
          }
          return refusal(intl, problem)
        }
      }}
    />
  )
}

/** "Circulate for comments" (flow AF): the deadline (optional) and, when nobody could review, why. */
export function CirculateDialog({ page, noReviewer, onDone, onClose }: StepProps & { noReviewer: boolean }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [until, setUntil] = useState('')
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const reasonRef = useRef<HTMLTextAreaElement>(null)
  return (
    <FormDialog
      large
      title={intl.formatMessage(noReviewer ? mn.noReviewerTitle : mn.circulateTitle)}
      description={intl.formatMessage(noReviewer ? mn.noReviewer : mn.circulateLead)}
      submitLabel={intl.formatMessage(mn.circulate)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        if (noReviewer && !reason.trim()) {
          setErrors({ reason: intl.formatMessage(mn.reasonRequired) })
          reasonRef.current?.focus()
          return undefined
        }
        try {
          const headers = ifMatch(page)
          const body = { ...(until ? { commentsUntil: until } : {}), ...(noReviewer ? { reason: reason.trim() } : {}) }
          await write(['circulate', body, headers], (key) =>
            circulateMinutes({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key, ...headers },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.circulated))
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          setErrors(fields)
          return Object.keys(fields).length ? undefined : refusal(intl, problem)
        }
      }}
    >
      <Field
        label={intl.formatMessage(mn.deadline)}
        hint={intl.formatMessage(mn.deadlineHint)}
        optionalLabel={intl.formatMessage(mn.optional)}
        error={errors.commentsUntil}
      >
        {(p) => (
          <Input
            {...p}
            type="date"
            name="commentsUntil"
            min={tomorrow()}
            className="min-h-11 text-base"
            value={until}
            onChange={(e) => {
              setUntil(e.target.value)
            }}
          />
        )}
      </Field>
      {noReviewer ? (
        <Field label={intl.formatMessage(mn.whyNoReview)} required error={errors.reason}>
          {(p) => (
            <Textarea
              {...p}
              ref={reasonRef}
              name="reason"
              rows={3}
              maxLength={MAX_NOTE}
              className="text-base"
              value={reason}
              onChange={(e) => {
                setReason(e.target.value)
              }}
            />
          )}
        </Field>
      ) : null}
    </FormDialog>
  )
}

/** A secretary records the chair's agreement given outside the app: how, and a note (flow AE1). */
export function OnBehalfDialog({ page, onDone, onClose }: StepProps) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const groupId = useId()
  const [how, setHow] = useState<'EMAIL' | 'PHONE' | 'IN_PERSON' | 'OTHER'>('EMAIL')
  const [note, setNote] = useState('')
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.recordAgreementTitle)}
      description={page.reviewer ? intl.formatMessage(mnv.withReviewer, { name: page.reviewer.name }) : undefined}
      submitLabel={intl.formatMessage(mn.record)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        try {
          const headers = ifMatch(page)
          const body = { decision: 'AGREE' as const, how, ...(note.trim() ? { comment: note.trim() } : {}) }
          await write(['behalf', body, headers], (key) =>
            reviewMinutes({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key, ...headers },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.agreementRecorded))
          return undefined
        } catch (problem) {
          return refusal(intl, problem)
        }
      }}
    >
      <fieldset className="flex flex-col gap-2" aria-labelledby={groupId}>
        <legend id={groupId} className="text-sm font-medium">
          {intl.formatMessage(mn.howAgreed)}
        </legend>
        {(['EMAIL', 'PHONE', 'IN_PERSON', 'OTHER'] as const).map((h) => (
          <label key={h} className="flex min-h-11 items-center gap-3 text-base">
            <input
              type="radio"
              name="how"
              className="size-5 accent-primary"
              checked={how === h}
              onChange={() => {
                setHow(h)
              }}
            />
            {intl.formatMessage(mn[h])}
          </label>
        ))}
      </fieldset>
      <Field
        label={intl.formatMessage(mn.agreementNote)}
        hint={intl.formatMessage(mn.agreementNoteHint)}
        optionalLabel={intl.formatMessage(mn.optional)}
      >
        {(p) => (
          <Input
            {...p}
            maxLength={MAX_NOTE}
            className="min-h-11 text-base"
            value={note}
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** "Take back to draft…" and "End the comment period now…": each with a reason (flow AF4). */
export function ReasonDialog({ page, kind, onDone, onClose }: StepProps & { kind: 'withdraw' | 'end' }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string>()
  const ref = useRef<HTMLTextAreaElement>(null)
  const withdraw = kind === 'withdraw'
  return (
    <FormDialog
      large
      destructive={withdraw}
      title={intl.formatMessage(withdraw ? mn.withdrawTitle : mn.endTitle)}
      description={intl.formatMessage(withdraw ? mn.withdrawLead : mn.endLead)}
      submitLabel={intl.formatMessage(withdraw ? mn.withdrawConfirm : mn.endConfirm)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        if (!reason.trim()) {
          setError(intl.formatMessage(mn.reasonRequired))
          ref.current?.focus()
          return undefined
        }
        try {
          const headers = ifMatch(page)
          const body = { reason: reason.trim() }
          const options = (key: string) => ({
            path: { meetingId: page.meetingId },
            headers: { 'Idempotency-Key': key, ...headers },
            body,
            throwOnError: true as const,
          })
          await write([kind, body, headers], (key) =>
            withdraw ? withdrawMinutes(options(key)) : endMinutesCirculation(options(key)),
          )
          onDone(intl.formatMessage(withdraw ? mn.withdrawn : mn.ended))
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          if (fields.reason) {
            setError(fields.reason)
            return undefined
          }
          return refusal(intl, problem)
        }
      }}
    >
      <Field label={intl.formatMessage(mn.reason)} required error={error}>
        {(p) => (
          <Textarea
            {...p}
            ref={ref}
            name="reason"
            rows={3}
            maxLength={MAX_NOTE}
            className="text-base"
            value={reason}
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** "Change the deadline" of circulated minutes (tomorrow at the earliest). */
export function DeadlineDialog({ page, onDone, onClose }: StepProps) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const [until, setUntil] = useState(page.commentsUntil?.slice(0, 10) ?? '')
  const [error, setError] = useState<string>()
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.changeDeadlineTitle)}
      submitLabel={intl.formatMessage(mn.save)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        if (!until) {
          setError(intl.formatMessage(mn.dateRange))
          return undefined
        }
        try {
          const headers = ifMatch(page)
          const body = { commentsUntil: until }
          await write(['deadline', body, headers], (key) =>
            changeMinutesDeadline({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key, ...headers },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.deadlineChanged))
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          if (fields.commentsUntil) {
            setError(fields.commentsUntil)
            return undefined
          }
          return refusal(intl, problem)
        }
      }}
    >
      <Field label={intl.formatMessage(mn.deadline)} required error={error}>
        {(p) => (
          <Input
            {...p}
            type="date"
            name="commentsUntil"
            min={tomorrow()}
            className="min-h-11 text-base"
            value={until}
            onChange={(e) => {
              setUntil(e.target.value)
            }}
          />
        )}
      </Field>
    </FormDialog>
  )
}

/** "Ask someone else for comments…" (flow AF4): people (1 to 20) and the items they read. */
export function AskDialog({ page, items, onDone, onClose }: StepProps & { items: MinutesItem[] }) {
  const intl = useIntl()
  const write = useMinutesWrite()
  const pickerId = useId()
  const itemsId = useId()
  const [picked, setPicked] = useState<Picked>()
  const [people, setPeople] = useState<Picked[]>([])
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const [errors, setErrors] = useState<Record<string, string>>({})
  return (
    <FormDialog
      side
      large
      title={intl.formatMessage(mn.askTitle)}
      description={intl.formatMessage(mn.askLead)}
      submitLabel={intl.formatMessage(mn.askSend)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={async () => {
        const missing: Record<string, string> = {}
        if (!people.length) missing.userIds = intl.formatMessage(mn.askNobody)
        if (!chosen.size) missing.itemIds = intl.formatMessage(mn.askNoItem)
        setErrors(missing)
        if (Object.keys(missing).length) return undefined
        try {
          const body = {
            userIds: people.map((p) => p.id),
            itemIds: items.filter((i) => chosen.has(i.itemId)).map((i) => i.itemId),
          }
          await write(['ask', body], (key) =>
            askForMinutesComments({
              path: { meetingId: page.meetingId },
              headers: { 'Idempotency-Key': key },
              body,
              throwOnError: true,
            }),
          )
          onDone(intl.formatMessage(mn.asked))
          return undefined
        } catch (problem) {
          const fields = fieldProblems(intl, problem)
          setErrors(fields)
          return Object.keys(fields).length ? undefined : refusal(intl, problem)
        }
      }}
    >
      <div className="flex flex-col gap-1.5">
        <label htmlFor={pickerId} className="text-sm font-medium">
          {intl.formatMessage(mn.askPerson)}
        </label>
        <div className="flex flex-wrap items-start gap-2">
          <div className="min-w-0 flex-1">
            <PeoplePicker
              id={pickerId}
              kind="user"
              value={picked}
              onChange={setPicked}
              invalid={Boolean(errors.userIds)}
              describedBy={errors.userIds ? `${pickerId}-error` : undefined}
            />
          </div>
          <Button
            variant="outline"
            className="min-h-11"
            disabled={!picked}
            onClick={() => {
              const person = picked
              if (person && !people.some((p) => p.id === person.id)) setPeople([...people, person])
              setPicked(undefined)
            }}
          >
            {intl.formatMessage(mn.askAdd)}
          </Button>
        </div>
        {errors.userIds ? (
          <p id={`${pickerId}-error`} className="text-xs font-medium text-destructive">
            {errors.userIds}
          </p>
        ) : null}
        {people.length ? (
          <ul aria-label={intl.formatMessage(mn.askPeopleList)} className="flex flex-wrap gap-2">
            {people.map((p) => (
              <li key={p.id} className="flex items-center gap-1 rounded-full border ps-3">
                {p.label}
                <Button
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mnv.removePerson, { name: p.label })}
                  onClick={() => {
                    setPeople(people.filter((q) => q.id !== p.id))
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
      <fieldset className="flex flex-col gap-1" aria-describedby={errors.itemIds ? `${itemsId}-error` : undefined}>
        <legend className="text-sm font-medium">{intl.formatMessage(mn.askItems)}</legend>
        {items.map((i) => (
          <label key={i.itemId} className="flex min-h-11 items-center gap-3 text-base">
            <Checkbox
              checked={chosen.has(i.itemId)}
              onChange={(e) => {
                const next = new Set(chosen)
                if (e.target.checked) next.add(i.itemId)
                else next.delete(i.itemId)
                setChosen(next)
              }}
            />
            {itemName(intl, i)}
          </label>
        ))}
        {errors.itemIds ? (
          <p id={`${itemsId}-error`} className="text-xs font-medium text-destructive">
            {errors.itemIds}
          </p>
        ) : null}
      </fieldset>
    </FormDialog>
  )
}

/** "See the copy for…" (flow AD4): each distinct copy with who reads it and what it leaves out; shows the one chosen. */
export function CopyForDialog({
  copies,
  onShow,
  onClose,
}: {
  copies: MinutesCopySetResponse[]
  onShow: (copy: MinutesCopySetResponse) => void
  onClose: () => void
}) {
  const intl = useIntl()
  const groupId = useId()
  const [chosen, setChosen] = useState(copies[0]?.setKey ?? '')
  return (
    <FormDialog
      large
      title={intl.formatMessage(mn.seeCopyFor)}
      submitLabel={intl.formatMessage(mn.showCopy)}
      cancelLabel={intl.formatMessage(mn.cancel)}
      onClose={onClose}
      onSubmit={() => {
        const copy = copies.find((c) => c.setKey === chosen)
        if (copy) onShow(copy)
        return Promise.resolve(undefined)
      }}
    >
      <fieldset className="flex flex-col gap-1" aria-labelledby={groupId}>
        <legend id={groupId} className="text-sm font-medium">
          {intl.formatMessage(mn.copyChoose)}
        </legend>
        {copies.map((c) => (
          <label key={c.setKey} className="flex min-h-11 items-center gap-3 text-base">
            <input
              type="radio"
              name="copy"
              className="size-5 shrink-0 accent-primary"
              checked={chosen === c.setKey}
              onChange={() => {
                setChosen(c.setKey)
              }}
            />
            {copyLabel(intl, c)}
          </label>
        ))}
      </fieldset>
    </FormDialog>
  )
}
