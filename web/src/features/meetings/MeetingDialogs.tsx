import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Ellipsis } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { getMeetingOptions } from '@/api/generated/@tanstack/react-query.gen'
import {
  addMeetingGuest,
  cancelMeeting,
  endMeeting,
  removeMeetingGuest,
  rescheduleMeeting,
  scheduleMeeting,
  startMeeting,
  updateMeeting,
} from '@/api/generated/sdk.gen'
import type { MeetingGuestResponse, MeetingResponse } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Field } from '@/components/ui/field'
import { Input, Textarea } from '@/components/ui/form-controls'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { sendHeldDrafts } from '@/features/actions/officer/issue'
import { HeldDraftsLine } from '@/features/actions/officer/MeetingActions'
import { HeldConsequences } from '@/features/decisions/HeldConsequences'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { missingTimes, placeBody, placeOf, timesBody, timesOf } from './form'
import { ClassificationField, KindField, PlaceFields, TimesFields, TitleField } from './MeetingFields'
import { mm, mmv } from './messages'
import {
  actionsFor,
  ifMatch,
  meetingFieldProblems,
  meetingName,
  meetingRefusal,
  refreshMeetings,
  type MeetingAction,
  type MeetingStatus,
} from './shared'

/** The menu label of [action] for a meeting in [status] (a draft's times are changed, a scheduled meeting is rescheduled). */
function actionLabel(action: MeetingAction, status: MeetingStatus) {
  switch (action) {
    case 'edit':
      return mm.editSettings
    case 'reschedule':
      return status === 'DRAFT' ? mm.changeTimes : mm.reschedule
    case 'schedule':
      return mm.scheduleAction
    case 'start':
      return mm.start
    case 'end':
      return mm.end
    case 'cancel':
      return status === 'DRAFT' ? mm.discard : mm.cancel
  }
}

/**
 * The meeting's ⋯ menu for its secretaries: the actions its state allows. With a full [meeting] the dialogs open at
 * once; from a list row ([status] and [meetingId] only) they load the meeting first, for its version (If-Match).
 */
export function MeetingMenu({
  meetingId,
  name,
  status,
  meeting,
}: {
  meetingId: string
  name: string
  status: MeetingStatus
  meeting?: MeetingResponse
}) {
  const intl = useIntl()
  const [open, setOpen] = useState<MeetingAction>()
  const actions = actionsFor(status)
  if (!actions.length) return null
  const close = () => {
    setOpen(undefined)
  }
  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="outline" size="icon" aria-label={intl.formatMessage(mmv.actionsFor, { name })}>
            <Ellipsis aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          {actions.map((action) => (
            <DropdownMenuItem
              key={action}
              onSelect={() => {
                setOpen(action)
              }}
            >
              {intl.formatMessage(actionLabel(action, status))}
            </DropdownMenuItem>
          ))}
        </DropdownMenuContent>
      </DropdownMenu>
      {open ? (
        meeting ? (
          <ActionDialog action={open} meeting={meeting} onClose={close} />
        ) : (
          <LoadedAction action={open} meetingId={meetingId} onClose={close} />
        )
      ) : null}
    </>
  )
}

/** Loads the meeting, then opens [action]'s dialog on it. */
function LoadedAction({
  action,
  meetingId,
  onClose,
}: {
  action: MeetingAction
  meetingId: string
  onClose: () => void
}) {
  const intl = useIntl()
  const meeting = useQuery(getMeetingOptions({ path: { meetingId } }))
  if (meeting.data) return <ActionDialog action={action} meeting={meeting.data} onClose={onClose} />
  return (
    <Dialog
      open
      onOpenChange={(o) => {
        if (!o) onClose()
      }}
      title={intl.formatMessage(mm.actions)}
    >
      {meeting.isError ? <ErrorState onRetry={() => void meeting.refetch()} /> : <LoadingState rows={2} />}
    </Dialog>
  )
}

function ActionDialog({
  action,
  meeting,
  onClose,
}: {
  action: MeetingAction
  meeting: MeetingResponse
  onClose: () => void
}) {
  switch (action) {
    case 'edit':
      return <EditDialog meeting={meeting} onClose={onClose} />
    case 'reschedule':
      return <RescheduleDialog meeting={meeting} onClose={onClose} />
    case 'cancel':
      return <CancelDialog meeting={meeting} onClose={onClose} />
    default:
      return <StepDialog step={action} meeting={meeting} onClose={onClose} />
  }
}

/** Runs [change]; on success refreshes the meetings, says [done] and closes; on a refusal returns it in words. */
function useChange(onClose: () => void, setErrors?: (errors: Record<string, string>) => void) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  return async (change: () => Promise<unknown>, done: string | (() => string)): Promise<string | undefined> => {
    try {
      await change()
      await refreshMeetings(queryClient)
      toast.show(typeof done === 'function' ? done() : done, 'success')
      onClose()
      return undefined
    } catch (problem) {
      if (isProblem(problem) && (problem.status === 412 || problem.status === 409)) await refreshMeetings(queryClient)
      const fields = meetingFieldProblems(intl, terms, problem)
      setErrors?.(fields)
      return setErrors && Object.keys(fields).length ? undefined : meetingRefusal(intl, terms, problem)
    }
  }
}

/** The meeting's settings (flow N): kind, title, how and where, the link and the classification (If-Match). */
function EditDialog({ meeting, onClose }: { meeting: MeetingResponse; onClose: () => void }) {
  const intl = useIntl()
  const [kind, setKind] = useState(meeting.kind)
  const [title, setTitle] = useState(meeting.title ?? '')
  const [place, setPlace] = useState(placeOf(meeting))
  const [classificationId, setClassificationId] = useState(meeting.classificationId)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const change = useChange(onClose, setErrors)
  return (
    <FormDialog
      side
      title={intl.formatMessage(mmv.editTitle, { name: meetingName(meeting) })}
      submitLabel={intl.formatMessage(mm.save)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      onSubmit={() =>
        change(
          () =>
            updateMeeting({
              path: { meetingId: meeting.id },
              headers: ifMatch(meeting),
              body: {
                kind,
                title: title.trim() || null,
                ...placeBody(place),
                classificationId,
              },
              throwOnError: true,
            }),
          intl.formatMessage(mm.saved),
        )
      }
      onClose={onClose}
    >
      <KindField value={kind} onChange={setKind} />
      <TitleField value={title} onChange={setTitle} error={errors.title} />
      <PlaceFields value={place} onChange={setPlace} errors={errors} />
      <ClassificationField value={classificationId} onChange={setClassificationId} error={errors.classificationId} />
    </FormDialog>
  )
}

/**
 * New date and time (flow N2): a scheduled meeting needs a reason ("Chair unavailable"); a draft's times just change.
 * The zone stays the meeting's unless another city is chosen.
 */
function RescheduleDialog({ meeting, onClose }: { meeting: MeetingResponse; onClose: () => void }) {
  const intl = useIntl()
  const draft = meeting.status === 'DRAFT'
  const [times, setTimes] = useState(timesOf(meeting))
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const change = useChange(onClose, setErrors)
  const name = meetingName(meeting)
  const submit = () => {
    const missing = {
      ...missingTimes(intl.formatMessage(mm.required), times),
      ...(!draft && !reason.trim() ? { reason: intl.formatMessage(mm.REASON_REQUIRED) } : {}),
    }
    setErrors(missing)
    if (Object.keys(missing).length) return Promise.resolve(undefined)
    return change(
      () =>
        rescheduleMeeting({
          path: { meetingId: meeting.id },
          headers: ifMatch(meeting),
          body: { ...timesBody(times), ...(reason.trim() ? { reason: reason.trim() } : {}) },
          throwOnError: true,
        }),
      intl.formatMessage(draft ? mm.saved : mm.rescheduled),
    )
  }
  return (
    <FormDialog
      side
      title={intl.formatMessage(draft ? mmv.changeTimesTitle : mmv.rescheduleTitle, { name })}
      description={draft ? undefined : intl.formatMessage(mm.rescheduleExplain)}
      submitLabel={intl.formatMessage(draft ? mm.save : mm.rescheduleSubmit)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      onSubmit={submit}
      onClose={onClose}
    >
      <TimesFields value={times} onChange={setTimes} errors={errors} />
      {draft ? null : (
        <ReasonField
          value={reason}
          onChange={setReason}
          hint={intl.formatMessage(mm.rescheduleReasonHint)}
          error={errors.reason}
        />
      )}
    </FormDialog>
  )
}

/**
 * Cancel a scheduled meeting, or discard a draft, with a reason and what happens in plain words (flow N2): "Members
 * will be told. Items from the queue go back to Ready for an agenda."
 */
function CancelDialog({ meeting, onClose }: { meeting: MeetingResponse; onClose: () => void }) {
  const intl = useIntl()
  const draft = meeting.status === 'DRAFT'
  const [reason, setReason] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const change = useChange(onClose, setErrors)
  const name = meetingName(meeting)
  const submit = () => {
    if (!reason.trim()) {
      setErrors({ reason: intl.formatMessage(mm.REASON_REQUIRED) })
      return Promise.resolve(undefined)
    }
    return change(
      () =>
        cancelMeeting({
          path: { meetingId: meeting.id },
          headers: ifMatch(meeting),
          body: { reason: reason.trim() },
          throwOnError: true,
        }),
      intl.formatMessage(draft ? mm.discarded : mm.cancelled),
    )
  }
  return (
    <FormDialog
      title={intl.formatMessage(draft ? mmv.discardTitle : mmv.cancelTitle, { name })}
      description={intl.formatMessage(draft ? mm.discardExplain : mm.cancelExplain)}
      submitLabel={intl.formatMessage(draft ? mm.discardSubmit : mm.cancelSubmit)}
      cancelLabel={intl.formatMessage(mm.keep)}
      destructive
      onSubmit={submit}
      onClose={onClose}
    >
      <ReasonField
        value={reason}
        onChange={setReason}
        hint={intl.formatMessage(mm.cancelReasonHint)}
        error={errors.reason}
      />
    </FormDialog>
  )
}

function ReasonField({
  value,
  onChange,
  hint,
  error,
}: {
  value: string
  onChange: (reason: string) => void
  hint: string
  error?: string
}) {
  const intl = useIntl()
  return (
    <Field label={intl.formatMessage(mm.reason)} hint={hint} required error={error}>
      {(p) => (
        <Textarea
          {...p}
          rows={2}
          maxLength={500}
          value={value}
          onChange={(e) => {
            onChange(e.target.value)
          }}
        />
      )}
    </Field>
  )
}

/** Schedule a draft, start the meeting or mark it held: one sentence on what follows, and one button. */
function StepDialog({
  step,
  meeting,
  onClose,
}: {
  step: 'schedule' | 'start' | 'end'
  meeting: MeetingResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const change = useChange(onClose)
  const name = meetingName(meeting)
  const options = { path: { meetingId: meeting.id }, headers: ifMatch(meeting), throwOnError: true } as const
  // "Send the n draft actions to their owners now" (E07 T10, flow AJ3), ticked by default; sent once the meeting is held.
  const [drafts, setDrafts] = useState({ send: true, count: 0 })
  const [issueKey] = useState(() => newIdempotencyKey())
  const heldSaid = { text: '' }
  const texts = {
    schedule: {
      title: mmv.scheduleTitle,
      explain: mm.scheduleConfirm,
      submit: mm.scheduleSubmit,
      done: mm.scheduled,
      run: () => scheduleMeeting(options),
    },
    start: {
      title: mmv.startTitle,
      explain: mm.startExplain,
      submit: mm.startSubmit,
      done: mm.started,
      run: () => startMeeting(options),
    },
    end: {
      title: mmv.endTitle,
      explain: mm.endExplain,
      submit: mm.endSubmit,
      done: mm.ended,
      run: () => endMeeting(options),
    },
  }[step]
  return (
    <FormDialog
      title={intl.formatMessage(texts.title, { name })}
      description={intl.formatMessage(texts.explain)}
      submitLabel={intl.formatMessage(texts.submit)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      onSubmit={() =>
        step === 'end' && drafts.send && drafts.count > 0 && meeting.canManage
          ? change(
              async () => {
                await texts.run()
                heldSaid.text = await sendHeldDrafts(intl, terms, meeting.id, issueKey)
              },
              () => heldSaid.text || intl.formatMessage(texts.done),
            )
          : change(texts.run, intl.formatMessage(texts.done))
      }
      onClose={onClose}
    >
      {step === 'end' ? <HeldConsequences meeting={meeting} /> : null}
      {step === 'end' && meeting.canManage ? (
        <HeldDraftsLine
          meetingId={meeting.id}
          send={drafts.send}
          onChange={(send, count) => {
            setDrafts({ send, count })
          }}
        />
      ) : null}
    </FormDialog>
  )
}

/** Add a guest for the whole meeting, with their role at it ("Chief Financial Officer"). */
export function AddGuestDialog({ meeting, onClose }: { meeting: MeetingResponse; onClose: () => void }) {
  const intl = useIntl()
  const personId = useId()
  const [person, setPerson] = useState<Picked>()
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState<Record<string, string>>({})
  const change = useChange(onClose, setErrors)
  const submit = () => {
    if (!person) {
      setErrors({ userId: intl.formatMessage(mm.unknownPerson) })
      return Promise.resolve(undefined)
    }
    return change(
      () =>
        addMeetingGuest({
          path: { meetingId: meeting.id },
          body: { userId: person.id, ...(note.trim() ? { note: note.trim() } : {}) },
          throwOnError: true,
        }),
      intl.formatMessage(mm.guestAdded),
    )
  }
  return (
    <FormDialog
      title={intl.formatMessage(mmv.addGuestTitle, { name: meetingName(meeting) })}
      description={intl.formatMessage(mm.guestsHint)}
      submitLabel={intl.formatMessage(mm.addGuest)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      onSubmit={submit}
      onClose={onClose}
    >
      <div className="flex flex-col gap-1.5">
        <label htmlFor={personId} className="text-sm font-medium">
          {intl.formatMessage(mm.guestPerson)}
        </label>
        <PeoplePicker
          id={personId}
          kind="user"
          value={person}
          onChange={setPerson}
          invalid={Boolean(errors.userId)}
          describedBy={errors.userId ? `${personId}-error` : undefined}
        />
        {errors.userId ? (
          <p id={`${personId}-error`} className="text-xs font-medium text-destructive">
            {errors.userId}
          </p>
        ) : null}
      </div>
      <Field
        label={intl.formatMessage(mm.guestNote)}
        hint={intl.formatMessage(mm.guestNoteHint)}
        optionalLabel={intl.formatMessage(mm.optional)}
        error={errors.note}
      >
        {(p) => (
          <Input
            {...p}
            maxLength={200}
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

/** Remove a guest: they no longer see the meeting; the record that they were invited stays. */
export function RemoveGuestDialog({
  meeting,
  guest,
  onClose,
}: {
  meeting: MeetingResponse
  guest: MeetingGuestResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const change = useChange(onClose)
  return (
    <FormDialog
      title={intl.formatMessage(mmv.removeGuestTitle, { name: guest.name })}
      description={intl.formatMessage(mm.removeGuestText)}
      submitLabel={intl.formatMessage(mm.remove)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      destructive
      onSubmit={() =>
        change(
          () => removeMeetingGuest({ path: { meetingId: meeting.id, userId: guest.userId }, throwOnError: true }),
          intl.formatMessage(mm.guestRemoved),
        )
      }
      onClose={onClose}
    />
  )
}
