import { useEffect, useId, useRef, useState, type ReactNode, type SyntheticEvent } from 'react'
import { useIntl } from 'react-intl'
import {
  changeActionDue,
  markActionDone,
  postActionUpdate,
  requestActionDue,
  verifyAction,
} from '@/api/generated/sdk.gen'
import type { ActionResponse } from '@/api/generated/types.gen'
import { Button } from '@/components/ui/button'
import { Dialog } from '@/components/ui/dialog'
import { Field } from '@/components/ui/field'
import { Input, Textarea } from '@/components/ui/form-controls'
import { useTerms } from '@/features/terms/terms'
import { newIdempotencyKey } from '@/lib/idempotency'
import { EvidenceFiles, type Attached } from './Evidence'
import { am } from './messages'
import { actionRefusal, fieldProblems, staleRefusal } from './problems'
import { confidenceWord, day, type Confidence } from './words'

export type ActionDialogKind = 'update' | 'requestDue' | 'changeDue' | 'done' | 'verify'

/** What a dialog reports when its change went through: the action as it is now and the sentence to announce. */
export type Done = (next: ActionResponse, said: string) => void

const TEXT_MAX = 4000
const NOTE_MAX = 2000
const REASON_MAX = 500
const CONFIDENCES: readonly Confidence[] = ['ON_TRACK', 'AT_RISK', 'OFF_TRACK']

/**
 * The action page's dialogs (flows AL2–AL4, and the verifier's from flow AM): one form each, with its fields' problems in
 * words at the field and a refusal in words above the buttons (role alert). A change sends the action's version (If-Match)
 * and one idempotency key per dialog, so a double click acts once; a lost race reloads the action ([onStale]).
 */
export function ActionDialog({
  kind,
  action,
  onClose,
  onDone,
  onStale,
}: {
  kind: ActionDialogKind
  action: ActionResponse
  onClose: () => void
  onDone: Done
  onStale: () => void
}) {
  const props = { action, onClose, onDone, onStale }
  switch (kind) {
    case 'update':
      return <UpdateDialog {...props} />
    case 'requestDue':
      return <DueDialog {...props} direct={false} />
    case 'changeDue':
      return <DueDialog {...props} direct />
    case 'done':
      return <DoneDialog {...props} />
    case 'verify':
      return <VerifyDialog {...props} />
  }
}

interface DialogProps {
  action: ActionResponse
  onClose: () => void
  onDone: Done
  onStale: () => void
}

/** The shared frame: the form, the refusal and the two buttons. */
function useSend(onStale: () => void) {
  const intl = useIntl()
  const terms = useTerms()
  const [key] = useState(() => newIdempotencyKey())
  const [busy, setBusy] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [refusal, setRefusal] = useState<string>()
  /** Sends [call]; a refusal at one of [shown] (the dialog's own fields) goes to its field, anything else above the buttons. */
  const send = async (shown: readonly string[], call: (headers: { 'Idempotency-Key': string }) => Promise<void>) => {
    setBusy(true)
    setRefusal(undefined)
    setErrors({})
    try {
      await call({ 'Idempotency-Key': key })
    } catch (problem) {
      const fields = Object.fromEntries(
        Object.entries(fieldProblems(intl, terms, problem)).filter(([field]) => shown.includes(field)),
      )
      setErrors(fields)
      if (!Object.keys(fields).length) setRefusal(actionRefusal(intl, terms, problem))
      if (staleRefusal(problem)) onStale()
    } finally {
      setBusy(false)
    }
  }
  return { intl, terms, busy, errors, setErrors, refusal, send }
}

function Frame({
  title,
  description,
  busy,
  submit,
  errors,
  refusal,
  onClose,
  onSubmit,
  children,
}: {
  title: string
  description?: string
  busy: boolean
  submit: string
  /** The fields' problems: focus moves to the first field with one, which then reads its problem. */
  errors: Record<string, string>
  refusal?: string
  onClose: () => void
  onSubmit: (e: SyntheticEvent) => void
  children: ReactNode
}) {
  const intl = useIntl()
  const formId = useId()
  const form = useRef<HTMLFormElement>(null)
  const first = Object.keys(errors)[0]
  useEffect(() => {
    if (first) form.current?.querySelector<HTMLElement>(`[name="${CSS.escape(first)}"]`)?.focus()
  }, [first, errors])
  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title={title}
      description={description}
      footer={
        <>
          <Button type="button" variant="outline" className="min-h-11" onClick={onClose}>
            {intl.formatMessage(am.cancel)}
          </Button>
          <Button type="submit" form={formId} className="min-h-11" disabled={busy}>
            {submit}
          </Button>
        </>
      }
    >
      <form ref={form} id={formId} noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        {children}
        {refusal ? (
          <p role="alert" className="text-sm font-medium text-destructive">
            {refusal}
          </p>
        ) : null}
      </form>
    </Dialog>
  )
}

/** A radio choice with a 44 px target. */
function Choice({
  name,
  checked,
  onSelect,
  children,
}: {
  name: string
  checked: boolean
  onSelect: () => void
  children: ReactNode
}) {
  return (
    <label className="flex min-h-11 cursor-pointer items-center gap-3 rounded-md border px-3 has-[:checked]:border-primary">
      <input type="radio" name={name} className="size-5 accent-primary" checked={checked} onChange={onSelect} />
      <span>{children}</span>
    </label>
  )
}

/** AL2: "What has happened?", "How is it going?" for the owner, and files. */
function UpdateDialog({ action, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [text, setText] = useState('')
  const [confidence, setConfidence] = useState<Confidence | undefined>(action.confidence ?? undefined)
  const [files, setFiles] = useState<Attached[]>([])
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (!text.trim()) {
      s.setErrors({ text: s.intl.formatMessage(am.fieldRequired) })
      return
    }
    void s.send(['text', 'confidence', 'fileIds'], async (headers) => {
      const next = await postActionUpdate({
        path: { actionId: action.id },
        headers: { ...headers, 'If-Match': `"${String(action.version)}"` },
        body: {
          text: text.trim(),
          ...(action.can.confidence && confidence ? { confidence } : {}),
          ...(files.length ? { fileIds: files.map((f) => f.fileId) } : {}),
        },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(am.updatePosted))
    })
  }
  return (
    <Frame
      title={s.intl.formatMessage(am.updateTitle)}
      busy={s.busy}
      submit={s.intl.formatMessage(am.updateSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <Field label={s.intl.formatMessage(am.updateText)} required error={s.errors.text}>
        {(p) => (
          <Textarea
            {...p}
            name="text"
            rows={6}
            maxLength={TEXT_MAX}
            value={text}
            className="text-base"
            onChange={(e) => {
              setText(e.target.value)
            }}
          />
        )}
      </Field>
      {action.can.confidence ? (
        <fieldset className="flex flex-col gap-2" aria-describedby="action-confidence-hint">
          <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(am.updateHow)}</legend>
          {CONFIDENCES.map((c) => (
            <Choice
              key={c}
              name="confidence"
              checked={confidence === c}
              onSelect={() => {
                setConfidence(c)
              }}
            >
              {confidenceWord(s.intl, c)}
            </Choice>
          ))}
          <p id="action-confidence-hint" className="text-xs text-muted-foreground">
            {s.intl.formatMessage(am.updateHowHint)}
          </p>
          {s.errors.confidence ? <p className="text-xs font-medium text-destructive">{s.errors.confidence}</p> : null}
        </fieldset>
      ) : null}
      <EvidenceFiles
        actionId={action.id}
        files={files}
        onChange={setFiles}
        label={s.intl.formatMessage(am.attach)}
        error={s.errors.fileIds}
      />
    </Frame>
  )
}

/** AL3: a new date and why; asked for (the secretary or chair agrees) or, under OWNER_WITH_REASON, changed at once. */
function DueDialog({ action, onClose, onDone, onStale, direct }: DialogProps & { direct: boolean }) {
  const s = useSend(onStale)
  const [newDate, setNewDate] = useState('')
  const [reason, setReason] = useState('')
  const current = action.dueDate ? day(s.intl, action.dueDate) : ''
  const explain = direct
    ? action.can.requestDue
      ? undefined
      : s.intl.formatMessage(am.dueOwnerChanges, s.terms)
    : action.dueChange === 'CHAIR_AGREES'
      ? s.intl.formatMessage(am.dueChairAgrees, { date: current })
      : s.intl.formatMessage(am.dueSecretaryAgrees, { ...s.terms, date: current })
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    const missing: Record<string, string> = {}
    if (!newDate) missing.newDate = s.intl.formatMessage(am.fieldRequired)
    if (!reason.trim()) missing.reason = s.intl.formatMessage(am.fieldRequired)
    if (Object.keys(missing).length) {
      s.setErrors(missing)
      return
    }
    void s.send(['newDate', 'reason'], async (headers) => {
      const options = {
        path: { actionId: action.id },
        headers: { ...headers, 'If-Match': `"${String(action.version)}"` },
        body: { newDate, reason: reason.trim() },
        throwOnError: true as const,
      }
      const next = direct ? await changeActionDue(options) : await requestActionDue(options)
      onDone(next.data, s.intl.formatMessage(direct ? am.dueChanged : am.dueRequested))
    })
  }
  return (
    <Frame
      title={s.intl.formatMessage(direct ? am.dueChangeTitle : am.dueTitle)}
      description={explain}
      busy={s.busy}
      submit={s.intl.formatMessage(direct ? am.dueChangeSend : am.dueSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <Field label={s.intl.formatMessage(am.dueNew)} required error={s.errors.newDate}>
        {(p) => (
          <Input
            {...p}
            name="newDate"
            type="date"
            className="w-fit text-base"
            value={newDate}
            onChange={(e) => {
              setNewDate(e.target.value)
            }}
          />
        )}
      </Field>
      <Field label={s.intl.formatMessage(am.dueWhy)} required error={s.errors.reason}>
        {(p) => (
          <Textarea
            {...p}
            name="reason"
            rows={3}
            maxLength={REASON_MAX}
            value={reason}
            className="text-base"
            onChange={(e) => {
              setReason(e.target.value)
            }}
          />
        )}
      </Field>
    </Frame>
  )
}

/** AL4: "What was done" and the evidence; then it goes to the verifier, never to the owner. */
function DoneDialog({ action, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [note, setNote] = useState('')
  const [files, setFiles] = useState<Attached[]>([])
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (!note.trim()) {
      s.setErrors({ note: s.intl.formatMessage(am.fieldRequired) })
      return
    }
    void s.send(['note', 'fileIds'], async (headers) => {
      const next = await markActionDone({
        path: { actionId: action.id },
        headers: { ...headers, 'If-Match': `"${String(action.version)}"` },
        body: { note: note.trim(), ...(files.length ? { fileIds: files.map((f) => f.fileId) } : {}) },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(am.doneSent, s.terms))
    })
  }
  return (
    <Frame
      title={s.intl.formatMessage(am.doneTitle)}
      description={s.intl.formatMessage(am.doneHint)}
      busy={s.busy}
      submit={s.intl.formatMessage(am.doneSend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <Field label={s.intl.formatMessage(am.doneWhat)} required error={s.errors.note}>
        {(p) => (
          <Textarea
            {...p}
            name="note"
            rows={5}
            maxLength={NOTE_MAX}
            value={note}
            className="text-base"
            onChange={(e) => {
              setNote(e.target.value)
            }}
          />
        )}
      </Field>
      <EvidenceFiles
        actionId={action.id}
        files={files}
        onChange={setFiles}
        label={s.intl.formatMessage(am.evidence)}
        error={s.errors.fileIds}
      />
    </Frame>
  )
}

/** The verifier's choice (flow AM1): close it, or send it back with what is missing. */
function VerifyDialog({ action, onClose, onDone, onStale }: DialogProps) {
  const s = useSend(onStale)
  const [decision, setDecision] = useState<'CLOSE' | 'SEND_BACK'>('CLOSE')
  const [remark, setRemark] = useState('')
  const submit = (e: SyntheticEvent) => {
    e.preventDefault()
    if (decision === 'SEND_BACK' && !remark.trim()) {
      s.setErrors({ remark: s.intl.formatMessage(am.fieldRemarkRequired) })
      return
    }
    void s.send(['remark'], async (headers) => {
      const next = await verifyAction({
        path: { actionId: action.id },
        headers: { ...headers, 'If-Match': `"${String(action.version)}"` },
        body: { decision, ...(remark.trim() ? { remark: remark.trim() } : {}) },
        throwOnError: true,
      })
      onDone(next.data, s.intl.formatMessage(decision === 'CLOSE' ? am.verifyClosed : am.verifySentBack))
    })
  }
  return (
    <Frame
      title={s.intl.formatMessage(am.verifyTitle)}
      busy={s.busy}
      submit={s.intl.formatMessage(am.verifySend)}
      errors={s.errors}
      refusal={s.refusal}
      onClose={onClose}
      onSubmit={submit}
    >
      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{s.intl.formatMessage(am.verifyChoice)}</legend>
        <Choice
          name="decision"
          checked={decision === 'CLOSE'}
          onSelect={() => {
            setDecision('CLOSE')
          }}
        >
          {s.intl.formatMessage(am.verifyClose)}
        </Choice>
        <Choice
          name="decision"
          checked={decision === 'SEND_BACK'}
          onSelect={() => {
            setDecision('SEND_BACK')
          }}
        >
          {s.intl.formatMessage(am.verifySendBack)}
        </Choice>
      </fieldset>
      <Field
        label={s.intl.formatMessage(am.verifyRemark)}
        hint={s.intl.formatMessage(am.verifyRemarkHint)}
        required={decision === 'SEND_BACK'}
        optionalLabel={s.intl.formatMessage(am.optional)}
        error={s.errors.remark}
      >
        {(p) => (
          <Textarea
            {...p}
            name="remark"
            rows={3}
            maxLength={NOTE_MAX}
            value={remark}
            className="text-base"
            onChange={(e) => {
              setRemark(e.target.value)
            }}
          />
        )}
      </Field>
    </Frame>
  )
}
