import { useSyncExternalStore } from 'react'
import { registerImportOriginals, uploadFile } from '@/api/generated/sdk.gen'
import type { IntakeEntryView } from '@/api/generated/types.gen'
import { isProblem } from '@/api/http'
import { chunks, intakeKey, toSend, type FolderSelection, type LocalOriginal, type Pending } from './folder'
import { UploadQueue, type SendResult } from './upload-queue'

/** Purpose of an original while it is staged (design E06 §10). */
export const INTAKE_PURPOSE = 'imports.intake'

/**
 * One folder upload of a batch (design E06 §5.1): the folder chosen, the registration of its paths in chunks, then the
 * queue of what still has to be sent. Kept outside React, by batch, so moving between the batch's steps does not stop
 * an upload; a reload does, and the resume is to choose the same folder again.
 */
export interface IntakeSession {
  selection: FolderSelection
  stage: 'registering' | 'sending' | 'failed'
  registered: number
  /** Paths the server already held at the same size when the folder was chosen. */
  alreadyHere: number
  /** Why registration failed (a code of the answer), when it did. */
  error?: string
  queue?: UploadQueue
}

const sessions = new Map<string, IntakeSession>()
const listeners = new Set<() => void>()

function publish(batchId: string, session: IntakeSession | undefined) {
  if (session) sessions.set(batchId, session)
  else sessions.delete(batchId)
  for (const listener of listeners) listener()
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/** The upload of [batchId] in progress, if any; re-renders on every change of it or of its queue. */
export function useIntakeSession(batchId: string): IntakeSession | undefined {
  const session = useSyncExternalStore(subscribe, () => sessions.get(batchId))
  const queue = session?.queue
  useSyncExternalStore(queue?.subscribe ?? subscribe, queue?.getSnapshot ?? (() => undefined))
  return session
}

/** Forgets [batchId]'s upload (after it ended, or to choose another folder). */
export function clearIntakeSession(batchId: string): void {
  const session = sessions.get(batchId)
  session?.queue?.stop('CLEARED')
  publish(batchId, undefined)
}

/** Registers [files]' paths in chunks of 1,000, answering every entry in order. */
export async function registerPaths(
  batchId: string,
  files: readonly LocalOriginal[],
  onProgress: (registered: number) => void = () => undefined,
): Promise<IntakeEntryView[]> {
  const entries: IntakeEntryView[] = []
  for (const group of chunks(files)) {
    const { data } = await registerImportOriginals({
      path: { id: batchId },
      body: { files: group.map((f) => ({ path: f.path, size: f.size })) },
      throwOnError: true,
    })
    entries.push(...data)
    onProgress(entries.length)
  }
  return entries
}

/** Sends one original to its intake entry and reads the answer as the queue needs it. */
export async function sendOriginal(item: Pending): Promise<SendResult> {
  const name = item.path.slice(item.path.lastIndexOf('/') + 1)
  try {
    await uploadFile({
      query: { purpose: INTAKE_PURPOSE, owner: item.intakeId },
      headers: {
        'Idempotency-Key': intakeKey(item.intakeId, item.size),
        'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
      },
      body: item.file,
      throwOnError: true,
    })
    return { kind: 'sent' }
  } catch (error) {
    if (!isProblem(error)) return { kind: 'retry' }
    const status = error.status ?? 0
    if (status === 422) return { kind: 'refused', reason: error.reason ?? 'REFUSED' }
    if (status === 401 || status === 403 || status === 404 || status === 409) {
      return { kind: 'stop', reason: error.reason ?? (status === 409 ? 'WRONG_STATE' : 'FORBIDDEN') }
    }
    return { kind: 'retry' }
  }
}

/**
 * Starts the upload of [selection] for [batchId]: registers every path, then sends what the server does not hold at
 * the same size. [send] and [register] are replaceable for tests.
 */
export async function beginIntake(
  batchId: string,
  selection: FolderSelection,
  deps: { send?: typeof sendOriginal; register?: typeof registerPaths; wait?: (ms: number) => Promise<void> } = {},
): Promise<void> {
  clearIntakeSession(batchId)
  let session: IntakeSession = { selection, stage: 'registering', registered: 0, alreadyHere: 0 }
  publish(batchId, session)
  try {
    const entries = await (deps.register ?? registerPaths)(batchId, selection.files, (registered) => {
      session = { ...session, registered }
      publish(batchId, session)
    })
    const pending = toSend(selection.files, entries)
    const queue = new UploadQueue(pending, deps.send ?? sendOriginal, deps.wait ? { wait: deps.wait } : {})
    session = { ...session, stage: 'sending', alreadyHere: selection.files.length - pending.length, queue }
    publish(batchId, session)
    queue.start()
  } catch (error) {
    const code = isProblem(error) ? (error.errors?.[0]?.code ?? error.reason ?? 'FAILED') : 'FAILED'
    publish(batchId, { ...session, stage: 'failed', error: code })
  }
}
