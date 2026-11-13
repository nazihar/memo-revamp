import { useEffect, useMemo, useRef, useState } from 'react'
import { validateWorkflowDefinition } from '@/api/generated/sdk.gen'
import type { FieldProblem } from '@/api/generated/types.gen'
import { asDefinition, type DefinitionBody } from './definition'
import { apply, history, record, redo, undo, type Command, type History } from './edit'

export function print(body: DefinitionBody): string {
  return JSON.stringify(body, null, 2)
}

export function parseBody(text: string): DefinitionBody | undefined {
  try {
    return asDefinition(JSON.parse(text))
  } catch {
    return undefined
  }
}

/** What changes one field of one step: typing in it again extends the same undo step instead of adding one per key. */
function coalesceKey(command: Command): string | undefined {
  return command.kind === 'set' ? `${command.id}:${Object.keys(command.patch).sort().join(',')}` : undefined
}

/**
 * The body being edited (design E06 §3): the JSON text stays the source the page saves, and the builder's commands go
 * through [edit], each one undoable. Typing in the JSON view counts as one change when the builder is used next.
 */
export function useBuilder(original: string) {
  const [text, setText] = useState(original)
  const [hist, setHist] = useState<History | undefined>(() => {
    const body = parseBody(original)
    return body ? history(body) : undefined
  })
  const lastKey = useRef<string | undefined>(undefined)
  const presentText = useMemo(() => (hist ? print(hist.present) : undefined), [hist])
  const body = useMemo(() => (hist && text === presentText ? hist.present : parseBody(text)), [hist, text, presentText])

  /** The history with the JSON view's edits, if any, recorded as one change. */
  const synced = (): History | undefined => {
    if (!body) return hist
    if (!hist) return history(body)
    return body === hist.present ? hist : record(hist, body)
  }

  const show = (h: History) => {
    setHist(h)
    setText(print(h.present))
  }

  const edit = (command: Command) => {
    const h = synced()
    if (!h) return
    const next = apply(h.present, command)
    const key = coalesceKey(command)
    show(key !== undefined && key === lastKey.current ? { ...h, present: next, future: [] } : record(h, next))
    lastKey.current = key
  }

  const typed = (value: string) => {
    lastKey.current = undefined
    setText(value)
  }

  const step = (move: (h: History) => History) => {
    const h = synced()
    if (!h) return
    lastKey.current = undefined
    show(move(h))
  }

  const pending = body !== undefined && hist !== undefined && body !== hist.present
  return {
    text,
    body,
    edit,
    setText: typed,
    undo: () => {
      step(undo)
    },
    redo: () => {
      step(redo)
    },
    canUndo: pending || (hist?.past.length ?? 0) > 0,
    canRedo: !pending && (hist?.future.length ?? 0) > 0,
  }
}

/**
 * Validation as you edit (design E06 §3): half a second after the last change, the unsaved body goes to the server's
 * `validate`; only the newest answer counts. The body the page opened with is not sent again.
 */
export function useLiveValidation({
  path,
  text,
  original,
  enabled,
  onProblems,
}: {
  path: { key: string; number: string }
  text: string
  original: string
  enabled: boolean
  onProblems: (problems: FieldProblem[]) => void
}) {
  const [checking, setChecking] = useState(false)
  const latest = useRef(0)
  useEffect(() => {
    if (!enabled || text === original) return undefined
    const body = parseBody(text)
    if (!body) return undefined
    const id = ++latest.current
    const timer = setTimeout(() => {
      setChecking(true)
      validateWorkflowDefinition({ path, body: { body }, throwOnError: true })
        .then((result) => {
          if (id === latest.current) onProblems(result.data.problems)
        })
        .catch(() => {
          // A failed check changes nothing: the next edit, Check or Submit asks again.
        })
        .finally(() => {
          if (id === latest.current) setChecking(false)
        })
    }, DEBOUNCE_MS)
    return () => {
      clearTimeout(timer)
    }
    // path is a fresh object each render; key and number say the same.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, enabled, original, path.key, path.number, onProblems])
  return checking
}

export const DEBOUNCE_MS = 500
