import { useQueryClient } from '@tanstack/react-query'
import { X } from 'lucide-react'
import { useId, useState } from 'react'
import { useIntl } from 'react-intl'
import { addMeetingGuest, createMeeting, scheduleMeeting } from '@/api/generated/sdk.gen'
import type { ForumPolicyResponse, ForumResponse, MeetingResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { useToast } from '@/components/ui/toast-context'
import { FormDialog } from '@/features/forums/FormDialog'
import { PeoplePicker, type Picked } from '@/features/org/PeoplePicker'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { missingTimes, placeBody, placeOf, timesBody, timesOf } from './form'
import { ClassificationField, KindField, PlaceFields, TimesFields, TitleField } from './MeetingFields'
import { mm, mmv } from './messages'
import { ifMatch, meetingFieldProblems, meetingRefusal, refreshMeetings } from './shared'

/**
 * "Schedule a meeting" (flow N2), a side sheet (full screen on a phone): kind, title, date and times in a zone chosen by
 * city, how and where, the join link, the classification and guests. "Save as draft" creates a draft only the officers
 * see; "Schedule" creates it and schedules it at once. Guests are added once the meeting exists.
 */
export function ScheduleSheet({
  forum,
  policy,
  onClose,
}: {
  forum: ForumResponse
  policy?: ForumPolicyResponse
  onClose: () => void
}) {
  const intl = useIntl()
  const terms = useTerms()
  const toast = useToast()
  const queryClient = useQueryClient()
  const guestsId = useId()
  const [kind, setKind] = useState<MeetingResponse['kind']>('ORDINARY')
  const [title, setTitle] = useState('')
  const [times, setTimes] = useState(timesOf())
  const [place, setPlace] = useState(placeOf())
  const [classificationId, setClassificationId] = useState('')
  const [guests, setGuests] = useState<Picked[]>([])
  const [pickerKey, setPickerKey] = useState(0)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [idempotencyKey] = useState(newIdempotencyKey)

  const save = async (andSchedule: boolean): Promise<string | undefined> => {
    const missing = missingTimes(intl.formatMessage(mm.required), times)
    setErrors(missing)
    if (Object.keys(missing).length) return undefined
    let meeting: MeetingResponse
    try {
      const created = await createMeeting({
        path: { forumId: forum.id },
        headers: { 'Idempotency-Key': idempotencyKey },
        body: {
          kind,
          ...(title.trim() ? { title: title.trim() } : {}),
          ...timesBody(times),
          ...placeBody(place),
          ...(classificationId ? { classificationId } : {}),
        },
        throwOnError: true,
      })
      meeting = created.data
    } catch (problem) {
      const fields = meetingFieldProblems(intl, terms, problem)
      setErrors(fields)
      return Object.keys(fields).length ? undefined : meetingRefusal(intl, terms, problem)
    }
    let failed = 0
    for (const guest of guests) {
      try {
        meeting = (
          await addMeetingGuest({ path: { meetingId: meeting.id }, body: { userId: guest.id }, throwOnError: true })
        ).data
      } catch {
        failed += 1
      }
    }
    let scheduled = false
    if (andSchedule) {
      try {
        await scheduleMeeting({ path: { meetingId: meeting.id }, headers: ifMatch(meeting), throwOnError: true })
        scheduled = true
      } catch (problem) {
        toast.show(intl.formatMessage(mmv.notScheduled, { reason: meetingRefusal(intl, terms, problem) }), 'error')
      }
    }
    await refreshMeetings(queryClient)
    if (failed) toast.show(intl.formatMessage(mmv.guestsFailed, { count: failed }), 'error')
    if (scheduled || !andSchedule) toast.show(intl.formatMessage(scheduled ? mm.scheduled : mm.savedDraft), 'success')
    onClose()
    return undefined
  }

  return (
    <FormDialog
      side
      title={intl.formatMessage(mmv.newTitle, { name: forum.name })}
      description={intl.formatMessage(mm.scheduleExplain)}
      submitLabel={intl.formatMessage(mm.scheduleSubmit)}
      cancelLabel={intl.formatMessage(mm.dismiss)}
      secondary={{ label: intl.formatMessage(mm.saveDraft), onSubmit: () => save(false) }}
      onSubmit={() => save(true)}
      onClose={onClose}
    >
      <KindField value={kind} onChange={setKind} />
      <TitleField value={title} onChange={setTitle} error={errors.title} />
      <TimesFields value={times} onChange={setTimes} errors={errors} />
      <PlaceFields value={place} onChange={setPlace} errors={errors} />
      <ClassificationField
        value={classificationId}
        onChange={setClassificationId}
        floorId={forum.classificationId ?? policy?.defaultClassificationId}
        withDefault
        error={errors.classificationId}
      />
      <div className="flex flex-col gap-1.5">
        <label htmlFor={guestsId} className="flex gap-1 text-sm font-medium">
          {intl.formatMessage(mm.guests)}
          <span className="font-normal text-muted-foreground">{intl.formatMessage(mm.optional)}</span>
        </label>
        <PeoplePicker
          key={pickerKey}
          id={guestsId}
          kind="user"
          describedBy={`${guestsId}-hint`}
          onChange={(picked) => {
            if (!picked) return
            if (!guests.some((g) => g.id === picked.id)) setGuests([...guests, picked])
            setPickerKey((k) => k + 1)
          }}
        />
        <p id={`${guestsId}-hint`} className="text-xs text-muted-foreground">
          {intl.formatMessage(mm.guestsHint)}
        </p>
        {guests.length ? (
          <ul aria-label={intl.formatMessage(mm.guests)} className="flex flex-wrap gap-2">
            {guests.map((g) => (
              <li key={g.id} className="flex items-center gap-1 rounded-full border bg-muted ps-3 text-sm">
                {g.label}
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-label={intl.formatMessage(mmv.removeGuest, { name: g.label })}
                  onClick={() => {
                    setGuests(guests.filter((x) => x.id !== g.id))
                  }}
                >
                  <X aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
        ) : null}
      </div>
    </FormDialog>
  )
}
