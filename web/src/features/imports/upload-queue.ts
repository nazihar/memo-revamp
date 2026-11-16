import { PARALLEL, type Pending } from './folder'

/** How one upload ended, as the sender reads the server's answer. */
export type SendResult =
  | { kind: 'sent' }
  /** The pipeline refused the file (422, with the reason code); it is listed, not retried. */
  | { kind: 'refused'; reason: string }
  /** Worth another try later (scanner unavailable, rate limit, lost connection). */
  | { kind: 'retry'; afterMs?: number }
  /** Nothing more can be sent (the batch moved on, the session ended): the queue stops. */
  | { kind: 'stop'; reason: string }

export type Phase = 'ready' | 'sending' | 'pausing' | 'paused' | 'done' | 'stopped'

export interface QueueSnapshot {
  phase: Phase
  files: number
  bytes: number
  sentFiles: number
  sentBytes: number
  /** Files in flight now. */
  active: number
  refused: { path: string; reason: string }[]
  /** Given up after every attempt; choosing the folder again sends them. */
  failed: string[]
  /** Why the queue stopped, when it did. */
  stopReason?: string
  /** Bytes a second while sending, once measurable. */
  rate?: number
}

export interface QueueOptions {
  parallel?: number
  attempts?: number
  /** Waits [ms] (tests replace it). */
  wait?: (ms: number) => Promise<void>
  now?: () => number
}

const BACKOFF_MS = [2_000, 5_000, 15_000, 30_000]

/**
 * Sends the originals a few at a time (design E06 §5.1 step 3) with pause and resume. Pausing lets the uploads in
 * flight finish and starts no new one; a refusal is listed with its reason; a temporary failure is retried with
 * back-off, then listed as failed. The queue knows nothing of React: the screen subscribes to its snapshots.
 */
export class UploadQueue {
  private readonly items: Pending[]
  private readonly send: (item: Pending) => Promise<SendResult>
  private readonly parallel: number
  private readonly attempts: number
  private readonly wait: (ms: number) => Promise<void>
  private readonly now: () => number
  private next = 0
  private activeMs = 0
  private since?: number
  private state: QueueSnapshot
  private readonly listeners = new Set<() => void>()

  constructor(items: Pending[], send: (item: Pending) => Promise<SendResult>, options: QueueOptions = {}) {
    this.items = items
    this.send = send
    this.parallel = options.parallel ?? PARALLEL
    this.attempts = options.attempts ?? BACKOFF_MS.length + 1
    this.wait = options.wait ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)))
    this.now = options.now ?? (() => Date.now())
    this.state = {
      phase: items.length === 0 ? 'done' : 'ready',
      files: items.length,
      bytes: items.reduce((sum, i) => sum + i.size, 0),
      sentFiles: 0,
      sentBytes: 0,
      active: 0,
      refused: [],
      failed: [],
    }
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  getSnapshot = (): QueueSnapshot => this.state

  /** Starts sending, or resumes after a pause. */
  start(): void {
    if (this.state.phase !== 'ready' && this.state.phase !== 'paused' && this.state.phase !== 'pausing') return
    this.since = this.now()
    this.update({ phase: 'sending' })
    this.fill()
  }

  /** Starts no new upload; those in flight finish. */
  pause(): void {
    if (this.state.phase !== 'sending') return
    this.clock()
    this.update({ phase: this.state.active > 0 ? 'pausing' : 'paused' })
  }

  /** Stops for good (the screen is leaving, or the batch moved on). */
  stop(reason: string): void {
    if (this.state.phase === 'done' || this.state.phase === 'stopped') return
    this.clock()
    this.update({ phase: 'stopped', stopReason: reason })
  }

  private clock() {
    if (this.since !== undefined) this.activeMs += this.now() - this.since
    this.since = undefined
  }

  private update(change: Partial<QueueSnapshot>) {
    const seconds = (this.activeMs + (this.since === undefined ? 0 : this.now() - this.since)) / 1000
    const sentBytes = change.sentBytes ?? this.state.sentBytes
    this.state = { ...this.state, ...change, ...(seconds >= 1 && sentBytes > 0 ? { rate: sentBytes / seconds } : {}) }
    for (const listener of this.listeners) listener()
  }

  private fill() {
    while (this.state.phase === 'sending' && this.state.active < this.parallel && this.next < this.items.length) {
      const item = this.items[this.next]
      this.next += 1
      if (item) {
        this.update({ active: this.state.active + 1 })
        void this.run(item)
      }
    }
    this.settle()
  }

  private settle() {
    if (this.state.active > 0) return
    if (this.next >= this.items.length && (this.state.phase === 'sending' || this.state.phase === 'pausing')) {
      this.clock()
      this.update({ phase: 'done' })
    } else if (this.state.phase === 'pausing') {
      this.update({ phase: 'paused' })
    }
  }

  private async run(item: Pending) {
    let result: SendResult = { kind: 'retry' }
    for (let attempt = 1; attempt <= this.attempts; attempt++) {
      try {
        result = await this.send(item)
      } catch {
        result = { kind: 'retry' }
      }
      if (result.kind !== 'retry' || attempt === this.attempts || this.state.phase === 'stopped') break
      await this.wait(result.afterMs ?? BACKOFF_MS[attempt - 1] ?? BACKOFF_MS[BACKOFF_MS.length - 1] ?? 0)
    }
    const active = this.state.active - 1
    switch (result.kind) {
      case 'sent':
        this.update({ active, sentFiles: this.state.sentFiles + 1, sentBytes: this.state.sentBytes + item.size })
        break
      case 'refused':
        this.update({ active, refused: [...this.state.refused, { path: item.path, reason: result.reason }] })
        break
      case 'retry':
        this.update({ active, failed: [...this.state.failed, item.path] })
        break
      case 'stop':
        this.update({ active })
        this.stop(result.reason)
        break
    }
    this.fill()
  }
}
