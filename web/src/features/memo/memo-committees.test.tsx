import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { BoxPage, MemoView, NoteEntryView, SubmissionResponse } from '@/api/generated/types.gen'
import { accessHandler, API_ORIGIN, memoPapersHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { NoteSheet } from './notes/NoteSheet'

const API = `${API_ORIGIN}/api/v1`

/** A credit proposal waiting for the Management Credit Committee (ADR-0058): no holder, no task. */
const memo: MemoView = {
  origin: 'NATIVE',
  followsUp: [],
  id: 'm1',
  reference: 'CR/2026-27/0042',
  subject: 'Term loan, Delta Textiles',
  type: { id: 't1', name: 'Credit proposal' },
  classification: { id: 'c1', rank: 0, name: 'Internal' },
  status: 'IN_REVIEW',
  initiator: { id: 'u1', name: 'Demo User' },
  route: [],
  round: 1,
  holders: [],
  withForum: 'Management Credit Committee',
  sendBackTargets: [],
  allowedActions: [],
  createdAt: '2026-11-17T08:00:00Z',
  updatedAt: '2026-11-18T08:00:00Z',
  version: 6,
  interests: [],
  details: [],
  staleDetails: [],
  links: [],
  linkedFrom: [],
  canLink: false,
  coauthors: [],
  rendition: 'NONE',
}

const paper = (s: Partial<SubmissionResponse> & Pick<SubmissionResponse, 'id'>): SubmissionResponse => ({
  forumId: 'f1',
  forumName: 'Management Credit Committee',
  subjectType: 'memo',
  subjectId: 'm1',
  memo: { id: 'm1', reference: 'CR/2026-27/0042', subject: 'Term loan, Delta Textiles' },
  waits: true,
  purpose: 'DECISION',
  status: 'QUEUED',
  submittedBy: { id: 'u1', name: 'Demo User' },
  submittedAt: '2026-11-18T09:00:00Z',
  version: 1,
  ...s,
})

function memoPage(papers: SubmissionResponse[], view: MemoView = memo, notes: NoteEntryView[] = []) {
  server.use(
    sessionHandler(signedInSession()),
    accessHandler([]),
    memoPapersHandler(papers),
    http.get(`${API}/memos/m1`, () => HttpResponse.json<MemoView>(view)),
    http.get(`${API}/memos/m1/history`, () => HttpResponse.json([])),
    http.get(`${API}/memos/m1/notes`, () => HttpResponse.json({ entries: notes, canWrite: false })),
    http.get(`${API}/memos/m1/approvals`, () => HttpResponse.json([])),
    http.get(`${API}/memos/m1/copies`, () =>
      HttpResponse.json({ copies: [], canEdit: false, canAdd: false, memoVersion: view.version }),
    ),
    http.get(`${API}/memos/m1/shares`, () => HttpResponse.json([])),
  )
}

describe('the memo and its committee (Phase 4 E02 T4, flow M2–M3)', () => {
  it('says the committee holds the memo and shows each paper’s timeline, newest first', async () => {
    memoPage([
      paper({
        id: 's2',
        status: 'ACCEPTED',
        targetDate: '2026-12-12',
        triagedAt: '2026-11-18T11:00:00Z',
        triagedBy: { id: 'u9', name: 'Demo Company Secretary' },
      }),
      paper({
        id: 's1',
        forumName: 'Board Risk Committee',
        forumId: 'f2',
        status: 'RETURNED',
        reason: 'Add the collateral valuation',
        submittedAt: '2026-11-10T09:00:00Z',
        triagedAt: '2026-11-11T09:00:00Z',
        triagedBy: { id: 'u9', name: 'Demo Company Secretary' },
      }),
    ])
    const { container } = renderRoute('/memos/m1')

    expect(await screen.findByText('With the Management Credit Committee', { selector: 'p' }, { timeout: 5000 }))
    const panel = await screen.findByRole('region', { name: 'Committees' })
    const [open, returned] = Array.from(panel.querySelectorAll(':scope > ul > li'))
    const first = within(open as HTMLElement)
    expect(first.getByRole('link', { name: 'With the Management Credit Committee' })).toHaveAttribute(
      'href',
      '/committees/f1/submissions/s2',
    )
    expect(first.getByText('Accepted')).toBeInTheDocument()
    expect(first.getByText(/Sent to the Management Credit Committee/)).toBeInTheDocument()
    expect(first.getByText(/Accepted for the meeting of Dec 12, 2026/)).toBeInTheDocument()
    expect(first.getByText('Next: decided at a meeting')).toBeInTheDocument()

    const second = within(returned as HTMLElement)
    expect(second.getByRole('link', { name: 'Board Risk Committee' })).toBeInTheDocument()
    expect(second.getByText(/Returned: Add the collateral valuation/)).toBeInTheDocument()
    expect(second.queryByText(/^Next:/)).toBeNull()
    await expectNoA11yViolations(container)
  }, 20_000)

  it('has no committee panel when the memo was never sent to one', async () => {
    memoPage([], { ...memo, withForum: null, holders: [{ id: 'u2', name: 'Demo Head of Credit' }] })
    renderRoute('/memos/m1')
    expect(await screen.findByText('Demo Head of Credit', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Committees' })).toBeNull()
    expect(screen.queryByText(/With the/)).toBeNull()
  }, 20_000)

  it('reads the panel in Arabic, right to left', async () => {
    memoPage([paper({ id: 's2' })])
    const { container } = renderRoute('/memos/m1', { locale: 'ar' })
    expect(await screen.findByRole('region', { name: 'لجان' }, { timeout: 5000 })).toBeInTheDocument()
    expect(screen.getAllByText('لدى Management Credit Committee').length).toBeGreaterThan(0)
    expect(screen.getByText('بانتظار الفرز')).toBeInTheDocument()
    expect(document.documentElement.dir).toBe('rtl')
    await expectNoA11yViolations(container)
  }, 20_000)

  it('shows “With the committee” in the Sent box', async () => {
    server.use(
      sessionHandler(signedInSession()),
      accessHandler([]),
      http.get(`${API}/memos`, () =>
        HttpResponse.json<BoxPage>({
          items: [
            {
              id: 'm1',
              reference: 'CR/2026-27/0042',
              subject: 'Term loan, Delta Textiles',
              status: 'IN_REVIEW',
              round: 1,
              holders: [],
              withForum: 'Management Credit Committee',
              updatedAt: '2026-11-18T08:00:00Z',
            },
          ],
          nextCursor: null,
        }),
      ),
    )
    renderRoute('/inbox?tab=sent')
    // The number is isolated for right-to-left text (U+2068 … U+2069), so match the line by its parts.
    expect(
      await screen.findByText(
        (text) => text.includes('CR/2026-27/0042') && text.endsWith(' · With the Management Credit Committee'),
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
  }, 20_000)

  it('names the committee on the note sheet when it returned the memo', async () => {
    const entry: NoteEntryView = {
      id: 'action-7',
      kind: 'REMARK',
      round: 1,
      author: { id: 'u9', name: 'Demo Company Secretary' },
      actionKind: 'FORUM_DECIDED',
      body: {
        type: 'doc',
        content: [{ type: 'paragraph', content: [{ type: 'text', text: 'Add the valuation' }] }],
      } as never,
      text: 'Add the valuation',
      createdAt: '2026-11-18T12:00:00Z',
      correctedBy: [],
      attachments: [],
      readableMemos: [],
      canCorrect: false,
      committee: 'Management Credit Committee',
      committeeOutcome: 'RETURNED',
    }
    server.use(
      http.get(`${API}/memos/m1/notes`, () => HttpResponse.json({ entries: [entry], canWrite: false })),
      http.get(`${API}/memos/m1/approvals`, () => HttpResponse.json([])),
      http.get(`${API}/memos/m1/history`, () => HttpResponse.json([{ round: 1, current: true, actions: [] }])),
    )
    renderWithProviders(<NoteSheet memoId="m1" onChanged={() => undefined} />)
    expect(await screen.findByText('Returned by the Management Credit Committee')).toBeInTheDocument()
    expect(screen.getByText('Demo Company Secretary')).toBeInTheDocument()
    expect(screen.getByText('Add the valuation')).toBeInTheDocument()
  })

  it('the committee’s decision carries its resolution number: on the note sheet with a link, and in the panel (E06 T10, flow AH)', async () => {
    const decided: NoteEntryView = {
      id: 'action-9',
      kind: 'REMARK',
      round: 1,
      author: { id: 'u9', name: 'Demo Company Secretary' },
      actionKind: 'FORUM_DECIDED',
      body: {
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [
              { type: 'text', text: 'Approved with conditions. Resolution MCC/2026/001: It was RESOLVED that …' },
            ],
          },
        ],
      } as never,
      text: 'Approved with conditions. Resolution MCC/2026/001: It was RESOLVED that …',
      createdAt: '2026-12-12T09:02:00Z',
      correctedBy: [],
      attachments: [],
      readableMemos: [],
      canCorrect: false,
      committee: 'Management Credit Committee',
      committeeOutcome: 'APPROVED_WITH_CONDITIONS',
      resolution: 'MCC/2026/001',
      resolutionId: 'r1',
      meetingLabel: 'MCC 4/2026',
    }
    const corrected: NoteEntryView = {
      ...decided,
      id: 'note-10',
      kind: 'COMMENT',
      actionKind: 'FORUM_DECISION_CORRECTED',
      committee: null,
      committeeOutcome: null,
      resolution: null,
      resolutionId: null,
      meetingLabel: null,
      text: 'The committee’s decision was corrected on 14 Dec 2026: …',
      body: {
        type: 'doc',
        content: [
          {
            type: 'paragraph',
            content: [{ type: 'text', text: 'The committee’s decision was corrected on 14 Dec 2026: …' }],
          },
        ],
      } as never,
      createdAt: '2026-12-14T09:00:00Z',
    }
    memoPage(
      [paper({ id: 's2', status: 'DECIDED', outcome: 'APPROVED_WITH_CONDITIONS', decidedAt: '2026-12-12T09:02:00Z' })],
      { ...memo, withForum: null },
      [decided, corrected],
    )
    const { container } = renderRoute('/memos/m1')
    const panel = await screen.findByRole('region', { name: 'Committees' }, { timeout: 5000 })
    expect(
      await within(panel).findByText('Decided at MCC 4/2026 — Approved with conditions — MCC/2026/001'),
    ).toBeInTheDocument()
    expect(await screen.findByText('Resolution MCC/2026/001 · MCC 4/2026')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open the resolution' })).toHaveAttribute('href', '/resolutions/r1')
    expect(screen.getByText('The committee’s decision was corrected')).toBeInTheDocument()
    await expectNoA11yViolations(container)
  }, 20_000)
})
