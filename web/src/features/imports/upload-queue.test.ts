import { describe, expect, it, vi } from 'vitest'
import type { Pending } from './folder'
import { UploadQueue, type SendResult } from './upload-queue'

function items(count: number, size = 10): Pending[] {
  return Array.from({ length: count }, (_, i) => ({
    path: `f${String(i)}.pdf`,
    size,
    file: new File(['x'], `f${String(i)}.pdf`),
    intakeId: `i${String(i)}`,
    state: 'EXPECTED' as const,
  }))
}

/** A sender whose uploads finish when the test says so. */
function controlled() {
  const waiting = new Map<string, (result: SendResult) => void>()
  const started: string[] = []
  const send = (item: Pending) =>
    new Promise<SendResult>((resolve) => {
      started.push(item.path)
      waiting.set(item.path, resolve)
    })
  const finish = async (path: string, result: SendResult = { kind: 'sent' }) => {
    waiting.get(path)?.(result)
    waiting.delete(path)
    await Promise.resolve()
    await Promise.resolve()
  }
  return { send, started, finish, waiting }
}

describe('upload queue (design E06 §5.1 step 3)', () => {
  it('sends three at a time and counts files and bytes', async () => {
    const c = controlled()
    const queue = new UploadQueue(items(5), c.send)
    queue.start()
    expect(c.started).toEqual(['f0.pdf', 'f1.pdf', 'f2.pdf'])
    expect(queue.getSnapshot().active).toBe(3)
    await c.finish('f0.pdf')
    expect(c.started).toHaveLength(4)
    expect(queue.getSnapshot()).toMatchObject({ sentFiles: 1, sentBytes: 10, files: 5, bytes: 50 })
    for (const p of ['f1.pdf', 'f2.pdf', 'f3.pdf', 'f4.pdf']) await c.finish(p)
    expect(queue.getSnapshot()).toMatchObject({ phase: 'done', sentFiles: 5, sentBytes: 50, active: 0 })
  })

  it('pauses after the uploads in flight, starts nothing new, and resumes where it stopped', async () => {
    const c = controlled()
    const queue = new UploadQueue(items(5), c.send)
    queue.start()
    queue.pause()
    expect(queue.getSnapshot().phase).toBe('pausing')
    await c.finish('f0.pdf')
    await c.finish('f1.pdf')
    expect(c.started).toHaveLength(3)
    await c.finish('f2.pdf')
    expect(queue.getSnapshot().phase).toBe('paused')
    expect(c.started).toHaveLength(3)
    queue.start()
    expect(queue.getSnapshot().phase).toBe('sending')
    expect(c.started).toEqual(['f0.pdf', 'f1.pdf', 'f2.pdf', 'f3.pdf', 'f4.pdf'])
    await c.finish('f3.pdf')
    await c.finish('f4.pdf')
    expect(queue.getSnapshot()).toMatchObject({ phase: 'done', sentFiles: 5 })
  })

  it('lists a refused file with its reason and goes on', async () => {
    const c = controlled()
    const queue = new UploadQueue(items(2), c.send)
    queue.start()
    await c.finish('f0.pdf', { kind: 'refused', reason: 'MALWARE' })
    await c.finish('f1.pdf')
    expect(queue.getSnapshot()).toMatchObject({
      phase: 'done',
      sentFiles: 1,
      refused: [{ path: 'f0.pdf', reason: 'MALWARE' }],
    })
  })

  it('retries a temporary failure with back-off, then lists it as failed', async () => {
    const wait = vi.fn(() => Promise.resolve())
    let calls = 0
    const queue = new UploadQueue(
      items(1),
      () => {
        calls += 1
        return Promise.resolve<SendResult>(calls < 3 ? { kind: 'retry' } : { kind: 'sent' })
      },
      { wait, attempts: 5 },
    )
    queue.start()
    await vi.waitFor(() => {
      expect(queue.getSnapshot().phase).toBe('done')
    })
    expect(calls).toBe(3)
    expect(wait).toHaveBeenCalledTimes(2)
    expect(queue.getSnapshot().sentFiles).toBe(1)

    const failing = new UploadQueue(items(1), () => Promise.reject(new Error('offline')), { wait, attempts: 2 })
    failing.start()
    await vi.waitFor(() => {
      expect(failing.getSnapshot().phase).toBe('done')
    })
    expect(failing.getSnapshot().failed).toEqual(['f0.pdf'])
  })

  it('stops for good when the batch moved on', async () => {
    const c = controlled()
    const queue = new UploadQueue(items(5), c.send)
    queue.start()
    await c.finish('f0.pdf', { kind: 'stop', reason: 'WRONG_STATE' })
    expect(queue.getSnapshot()).toMatchObject({ phase: 'stopped', stopReason: 'WRONG_STATE' })
    expect(c.started).toHaveLength(3)
  })

  it('is done at once with nothing to send', () => {
    expect(new UploadQueue([], () => Promise.resolve({ kind: 'sent' })).getSnapshot().phase).toBe('done')
  })
})
