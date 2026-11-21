import { useIntl } from 'react-intl'
import { Field } from '@/components/ui/field'
import { Checkbox, Input } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { ft, ftt } from './messages'
import { NumberInput } from './NumberInput'
import { MAX_REPLY_DAYS, type Meetings, type TypeSettings } from './settings'

/**
 * The Meetings tab (Phase 4 E05 T8, flow Z, design E05 §1.8): what calendar invitations are called (the meeting's title
 * or the committee's name, or a neutral title for committees whose name should not appear in calendars), the reply
 * deadline and its reminder, the reminder the working day before, and whether a decision may be recorded without a
 * quorum (with a reason, to be ratified). Changed on a draft and reviewed by a second administrator like every other
 * part of the type; read-only unless the version is a draft ([onChange] set).
 */
export function MeetingsTab({
  settings,
  onChange,
  errors,
}: {
  settings: TypeSettings
  onChange?: (next: Partial<TypeSettings>) => void
  errors: Record<string, string>
}) {
  const intl = useIntl()
  const terms = useTerms()
  const policy = settings.meetings
  const set = (next: Partial<Meetings>) => onChange?.({ meetings: { ...policy, ...next } })
  const neutral = policy.invitationTitle === 'NEUTRAL'
  return (
    <fieldset disabled={!onChange} className="flex max-w-2xl flex-col gap-5">
      <legend className="sr-only">{intl.formatMessage(ft.tabMeetings)}</legend>
      <fieldset className="flex flex-col gap-3" aria-describedby="meetings-neutral-lead">
        <legend className="text-base font-semibold">{intl.formatMessage(ft.meetingsInvitations)}</legend>
        <p id="meetings-neutral-lead" className="text-xs text-muted-foreground">
          {intl.formatMessage(ftt.meetingsNeutralLead, terms)}
        </p>
        <fieldset className="flex flex-col gap-1">
          <legend className="text-sm font-medium">{intl.formatMessage(ft.meetingsTitle)}</legend>
          <Radio
            name="invitation-title"
            checked={!neutral}
            label={intl.formatMessage(ftt.invitationName, terms)}
            onChange={() => {
              set({ invitationTitle: 'NAME' })
            }}
          />
          <Radio
            name="invitation-title"
            checked={neutral}
            label={intl.formatMessage(ft.meetingsTitleNeutral)}
            onChange={() => {
              set({ invitationTitle: 'NEUTRAL' })
            }}
          />
        </fieldset>
        {neutral ? (
          <Field
            label={intl.formatMessage(ft.meetingsNeutralTitle)}
            hint={intl.formatMessage(ft.meetingsNeutralTitleHint)}
            error={errors['meetings.neutralTitle']}
            required
          >
            {(p) => (
              <Input
                {...p}
                dir="auto"
                maxLength={100}
                className="min-h-11"
                value={policy.neutralTitle}
                onChange={(e) => {
                  set({ neutralTitle: e.target.value })
                }}
              />
            )}
          </Field>
        ) : null}
      </fieldset>
      <fieldset className="flex flex-col gap-3 border-t pt-4">
        <legend className="text-base font-semibold">{intl.formatMessage(ft.meetingsReplies)}</legend>
        <Field
          label={intl.formatMessage(ft.meetingsDeadline)}
          hint={intl.formatMessage(ft.meetingsDeadlineHint)}
          error={errors['meetings.replyDeadlineWorkingDays']}
        >
          {(p) => (
            <NumberInput
              {...p}
              min={0}
              max={MAX_REPLY_DAYS}
              className="min-h-11"
              value={policy.replyDeadlineWorkingDays}
              onChange={(replyDeadlineWorkingDays) => {
                set({ replyDeadlineWorkingDays })
              }}
            />
          )}
        </Field>
        <Check
          checked={policy.replyReminder}
          label={intl.formatMessage(ft.meetingsReplyReminder)}
          onChange={(replyReminder) => {
            set({ replyReminder })
          }}
        />
        <Check
          checked={policy.meetingReminder}
          label={intl.formatMessage(ft.meetingsMeetingReminder)}
          onChange={(meetingReminder) => {
            set({ meetingReminder })
          }}
        />
      </fieldset>
      <fieldset className="flex flex-col gap-1 border-t pt-4" aria-describedby="meetings-override-hint">
        <legend className="text-base font-semibold">{intl.formatMessage(ft.meetingsOverride)}</legend>
        <Radio
          name="quorum-override"
          checked={policy.quorumOverride === 'NEVER'}
          label={intl.formatMessage(ft.meetingsOverrideNever)}
          onChange={() => {
            set({ quorumOverride: 'NEVER' })
          }}
        />
        <Radio
          name="quorum-override"
          checked={policy.quorumOverride === 'WITH_REASON'}
          label={intl.formatMessage(ft.meetingsOverrideWithReason)}
          onChange={() => {
            set({ quorumOverride: 'WITH_REASON' })
          }}
        />
        <p id="meetings-override-hint" className="text-xs text-muted-foreground">
          {intl.formatMessage(ft.meetingsOverrideHint)}
        </p>
      </fieldset>
    </fieldset>
  )
}

function Radio({
  name,
  checked,
  label,
  onChange,
}: {
  name: string
  checked: boolean
  label: string
  onChange: () => void
}) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
      <input
        type="radio"
        name={name}
        className="size-5 shrink-0 accent-primary"
        checked={checked}
        onChange={onChange}
      />
      {label}
    </label>
  )
}

function Check({ checked, label, onChange }: { checked: boolean; label: string; onChange: (value: boolean) => void }) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 text-sm">
      <Checkbox
        className="size-5"
        checked={checked}
        onChange={(e) => {
          onChange(e.target.checked)
        }}
      />
      {label}
    </label>
  )
}
