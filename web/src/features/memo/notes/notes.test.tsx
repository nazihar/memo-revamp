import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { HistoryAction, NoteEntryView, StampView } from '@/api/generated/types.gen'
import { API_ORIGIN, server } from '@/test/msw'
import { renderWithProviders } from '@/test/render'
import { NoteRender } from './NoteRender'
import { NoteSheet } from './NoteSheet'
import { mentions, plainText, toNoteDoc, type NoteDoc } from './note-doc'
import { roundsOf } from './note-rounds'

const bob = { id: 'u2', name: 'Demo Head of Credit' }

const note = (over: Partial<NoteEntryView> = {}): NoteEntryView => ({
  id: 'n1',
  kind: 'COMMENT',
  round: 1,
  author: bob,
  body: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Thanks.' }] }] } as never,
  text: 'Thanks.',
  createdAt: '2026-11-09T11:02:00Z',
  correctedBy: [],
  attachments: [],
  readableMemos: [],
  canCorrect: false,
  ...over,
})

const stamp: StampView = {
  id: 's1',
  decision: 'APPROVE',
  round: 2,
  person: bob,
  decidedAt: '2026-11-09T14:20:00Z',
  factors: ['PASSWORD', 'TOTP'],
  secondFactorAgeSeconds: 125,
  stepUpRequired: true,
  documentNumber: 2,
  documentHash: '3f9a1c07',
}

describe('note format', () => {
  it('keeps exactly the format: Tiptap extras, empty texts and unknown nodes are dropped', () => {
    const doc = toNoteDoc({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          attrs: { textAlign: 'left' },
          content: [
            { type: 'text', text: 'See ', marks: [{ type: 'bold' }] },
            {
              type: 'text',
              text: 'policy',
              marks: [
                {
                  type: 'link',
                  attrs: { href: 'https://bank.example', target: '_blank', rel: 'noopener', class: null },
                },
              ],
            },
            { type: 'mention', attrs: { id: 'u2', label: 'Demo Head of Credit', mentionSuggestionChar: '@' } },
            { type: 'text', text: '' },
          ],
        },
        { type: 'image', attrs: { src: 'https://x.example/a.png' } },
      ],
    })
    expect(doc).toEqual({
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: 'See ', marks: [{ type: 'bold' }] },
            { type: 'text', text: 'policy', marks: [{ type: 'link', attrs: { href: 'https://bank.example' } }] },
            { type: 'mention', attrs: { id: 'u2', label: 'Demo Head of Credit' } },
          ],
        },
      ],
    })
    expect(plainText(doc)).toBe('See policy@Demo Head of Credit')
    expect(mentions(doc)).toEqual(['u2'])
  })
})

describe('note rendering', () => {
  it('draws text as text, opens https links safely, and hides memos the reader may not open', () => {
    const doc: NoteDoc = {
      type: 'doc',
      content: [
        {
          type: 'paragraph',
          content: [
            { type: 'text', text: '<b>not bold</b> ' },
            { type: 'text', text: 'policy', marks: [{ type: 'link', attrs: { href: 'https://bank.example/p' } }] },
            { type: 'text', text: ' ' },
            { type: 'memoRef', attrs: { id: 'm9', label: 'M-2026-00007' } },
          ],
        },
      ],
    }
    renderWithProviders(<NoteRender doc={doc} />)
    expect(screen.getByText(/<b>not bold<\/b>/)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: 'policy' })
    expect(link).toHaveAttribute('rel', 'noopener noreferrer nofollow')
    expect(link).toHaveAttribute('target', '_blank')
    expect(screen.getByText('Memo (restricted)')).toBeInTheDocument()
    expect(screen.queryByText('M-2026-00007')).not.toBeInTheDocument()
  })
})

describe('note sheet', () => {
  it('puts a decision and its remark in one stamp, repeats nothing, and lists rounds newest first', () => {
    const remark = note({ id: 'n2', kind: 'REMARK', actionKind: 'APPROVE', round: 2, createdAt: stamp.decidedAt })
    const event: HistoryAction & { round: number } = {
      seq: 9,
      kind: 'REASSIGN',
      round: 1,
      actor: { id: 'u9', name: 'Admin' },
      occurredAt: '2026-11-08T09:00:00Z',
      to: [],
      details: {},
    }
    const decided: HistoryAction & { round: number } = {
      ...event,
      seq: 10,
      kind: 'APPROVE',
      round: 2,
      actor: bob,
      occurredAt: stamp.decidedAt,
    }
    const rounds = roundsOf([note(), remark], [stamp], [event, decided])
    expect(rounds.map(([r]) => r)).toEqual([2, 1])
    const [, second] = rounds[0] ?? [0, []]
    expect(second).toHaveLength(1)
    expect(second[0]).toMatchObject({ kind: 'stamp', remark: { id: 'n2' } })
    const [, first] = rounds[1] ?? [0, []]
    expect(first.map((i) => i.kind)).toEqual(['event', 'note'])
  })

  it('shows how a decision was authenticated, and the check says what it found', async () => {
    const user = userEvent.setup()
    server.use(
      http.get(`${API_ORIGIN}/api/v1/memos/m1/notes`, () => HttpResponse.json({ entries: [], canWrite: false })),
      http.get(`${API_ORIGIN}/api/v1/memos/m1/approvals`, () => HttpResponse.json([stamp])),
      http.get(`${API_ORIGIN}/api/v1/memos/m1/history`, () =>
        HttpResponse.json([{ round: 2, current: true, actions: [] }]),
      ),
      http.post(`${API_ORIGIN}/api/v1/memos/m1/approvals/check`, () =>
        HttpResponse.json({ ok: true, checkedAt: '2026-11-15T10:00:00Z', problems: [] }),
      ),
    )
    renderWithProviders(<NoteSheet memoId="m1" onChanged={() => undefined} />)
    const box = await screen.findByRole('article', { name: /Demo Head of Credit/ })
    expect(
      within(box).getByText(/Authenticated with password and authenticator, verified 2 minutes before/),
    ).toBeInTheDocument()
    expect(within(box).getByText('Document v2 · 3f9a1c07')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Write a note' })).not.toBeInTheDocument()

    await user.click(within(box).getByRole('button', { name: 'Check' }))
    const dialog = await screen.findByRole('dialog', { name: 'Check the authenticated approvals' })
    await user.click(within(dialog).getByRole('button', { name: 'Check' }))
    expect(await within(dialog).findByText(/match what was approved/)).toBeInTheDocument()
  })
})
