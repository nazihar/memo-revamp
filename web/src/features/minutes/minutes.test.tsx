import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  ItemLink,
  MeetingResponse,
  MeetingSummaryResponse,
  MemberAgendaResponse,
  MemberItemResponse,
  MinutesApprovalResponse,
  MinutesCommentResponse,
  MinutesCommentsResponse,
  MinutesCorrectionsResponse,
  MinutesRecordResponse,
  MinutesResponse,
} from '@/api/generated/types.gen'
import { partsForPath } from '@/i18n/catalogue-parts'
import {
  accessHandler,
  API_ORIGIN,
  csrfHandler,
  myMeetingsHandler,
  server,
  sessionHandler,
  signedInSession,
} from '@/test/msw'
import { expectNoA11yViolations, renderRoute } from '@/test/render'

const API = `${API_ORIGIN}/api/v1`
const ME = signedInSession().user?.id ?? ''
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

vi.mock('@/features/meetings/time', async (original) => ({
  ...(await original<typeof import('@/features/meetings/time')>()),
  viewerZone: () => 'Asia/Dhaka',
}))

// PDF.js does not run in jsdom: the viewer's document loads, and the test reads which address it was given.
const pdf = vi.hoisted(() => ({ urls: [] as string[] }))
vi.mock('pdfjs-dist', () => ({
  GlobalWorkerOptions: { workerPort: {} },
  getDocument: ({ url }: { url: string }) => {
    pdf.urls.push(url)
    return {
      promise: Promise.resolve({ numPages: 9, getPage: () => new Promise(() => undefined) }),
      destroy: () => Promise.resolve(),
    }
  },
}))

/** BRC 3/2026, held on Saturday 12 December 2026 in Dhaka. */
const meeting: MeetingResponse = {
  id: 'm1',
  forumId: 'f1',
  forumName: 'Board Risk Committee',
  forumCode: 'BRC',
  typeVersion: 2,
  kind: 'ORDINARY',
  title: null,
  startsAt: '2026-12-12T04:00:00Z',
  endsAt: '2026-12-12T07:00:00Z',
  timeZone: 'Asia/Dhaka',
  localStartsAt: '2026-12-12T10:00:00',
  localEndsAt: '2026-12-12T13:00:00',
  mode: 'IN_PERSON',
  location: 'Board Room',
  classificationId: 'l1',
  number: 3,
  label: 'BRC 3/2026',
  status: 'HELD',
  relation: 'OFFICER',
  canManage: true,
  guests: [],
  version: 3,
}

const item = (
  id: string,
  number: string,
  title: string,
  extra: Partial<MemberItemResponse> = {},
): MemberItemResponse => ({
  id,
  number,
  title,
  access: 'FULL',
  kind: 'SUBMISSION',
  changes: [],
  late: 'NONE',
  papers: [],
  presenters: [],
  subItems: [],
  links: [],
  ...extra,
})

const agenda: MemberAgendaResponse = {
  meetingId: 'm1',
  status: 'HELD',
  startsAt: meeting.startsAt,
  endsAt: meeting.endsAt,
  timeZone: 'Asia/Dhaka',
  revision: 1,
  latestRevision: 1,
  preview: false,
  sections: [
    {
      id: 's1',
      kind: 'STANDARD',
      title: 'For decision',
      membersOnly: false,
      items: [item('i5', '5', 'Term loan, Delta Textiles'), item('i6', '6', 'Restricted item')],
    },
  ],
}

const NO_STRUCTURE: MinutesResponse['structure'] = {
  pending: false,
  ok: true,
  problems: [],
  trackedChanges: 0,
  comments: 0,
}

/** The minutes page for a secretary of a held meeting with no draft yet. */
const base: MinutesResponse = {
  meetingId: 'm1',
  forumId: 'f1',
  state: 'DRAFT',
  approval: 'AT_NEXT_MEETING',
  heldAt: '2026-12-12T07:05:00Z',
  structure: NO_STRUCTURE,
  items: [
    { itemId: 'i5', number: '5' },
    { itemId: 'i6', number: '6' },
  ],
  undecided: [],
  viewerRecused: false,
  mayGenerate: true,
  mayEdit: false,
  version: 4,
  viewerAccess: 'MANAGER',
  agreed: false,
  answered: false,
  copies: [],
  maySendForReview: false,
  mayReview: false,
  mayCirculate: false,
  mayWithdraw: false,
  mayEndCirculation: false,
  mayComment: false,
  mayConfirm: false,
  mayReadCopy: false,
  mayProposeCorrection: false,
}

const drafted: MinutesResponse = {
  ...base,
  draft: {
    fileId: 'file1',
    versionId: 'v1',
    number: 1,
    savedAt: '2026-12-12T09:20:00Z',
    savedBy: { id: 'u1', name: 'K. Secretary' },
  },
  mayEdit: true,
  editFileId: 'file1',
  maySendForReview: true,
  version: 6,
}

const circulated: MinutesResponse = {
  ...drafted,
  state: 'CIRCULATED',
  mayGenerate: false,
  maySendForReview: false,
  reviewer: { id: 'u2', name: 'Dr F. Karim' },
  confirmer: { id: 'u2', name: 'Dr F. Karim' },
  circulatedAt: '2026-12-14T04:00:00Z',
  commentsUntil: '2026-12-19T12:00:00Z',
  mayWithdraw: true,
  mayReadCopy: true,
  answers: { readers: 7, noComments: 4, suggestions: 1, notYet: 2 },
  copies: [
    {
      setKey: 'k1',
      redacted: [{ itemId: 'i5', number: '5', kind: 'RECUSED' }],
      readers: [{ id: 'u3', name: 'Demo Director' }],
    },
    { setKey: 'k0', redacted: [], readers: [{ id: 'u4', name: 'S. Ali' }] },
  ],
}

/** A member reading the circulated minutes. */
const reader: MinutesResponse = {
  ...base,
  state: 'CIRCULATED',
  viewerAccess: 'READER',
  myAction: 'COMMENT',
  mayGenerate: false,
  commentsUntil: '2026-12-19T12:00:00Z',
  mayComment: true,
  mayReadCopy: true,
}

interface Calls {
  posts: { path: string; body: unknown; key: string | null; ifMatch: string | null }[]
}

/** The requests of the minutes page, the minutes answering [pages] in turn (the last one repeats). */
function handlers(
  calls: Calls,
  pages: MinutesResponse[],
  options: { meeting?: MeetingResponse; comments?: MinutesCommentResponse[] } = {},
) {
  let read = 0
  const record =
    (path: string) =>
    async ({ request }: { request: Request }) => {
      const text = await request.text()
      calls.posts.push({
        path,
        body: text ? (JSON.parse(text) as unknown) : null,
        key: request.headers.get('Idempotency-Key'),
        ifMatch: request.headers.get('If-Match'),
      })
    }
  return [
    sessionHandler(signedInSession()),
    accessHandler([]),
    csrfHandler(),
    http.get(`${API}/authz/classification-levels`, () => HttpResponse.json([])),
    http.get(`${API}/meetings/m1`, () => HttpResponse.json<MeetingResponse>(options.meeting ?? meeting)),
    http.get(`${API}/meetings/m1/agenda`, () => HttpResponse.json<MemberAgendaResponse>(agenda)),
    http.get(`${API}/meetings/m1/minutes`, () => {
      const page = pages[Math.min(read, pages.length - 1)] ?? base
      read++
      return HttpResponse.json<MinutesResponse>(page, { headers: { ETag: `"${String(page.version)}"` } })
    }),
    http.get(`${API}/meetings/m1/minutes/comments`, () =>
      HttpResponse.json<MinutesCommentsResponse>({ all: true, comments: options.comments ?? [] }),
    ),
    ...['draft', 'send-for-review', 'circulate', 'review', 'withdraw', 'end-circulation'].map((step) =>
      http.post(`${API}/meetings/m1/minutes/${step}`, async (info) => {
        await record(step)(info)
        return HttpResponse.json<MinutesResponse>(pages[pages.length - 1] ?? base, {
          status: step === 'draft' ? 202 : 200,
        })
      }),
    ),
    http.post(`${API}/meetings/m1/minutes/items/:itemId/regenerate`, async (info) => {
      await record(`regenerate:${String(info.params.itemId)}`)(info)
      return HttpResponse.json<MinutesResponse>(pages[pages.length - 1] ?? base, { status: 202 })
    }),
  ]
}

const problem = (status: number, extra: Record<string, unknown>) =>
  HttpResponse.json({ type: 'urn:memo:problem:conflict', status, ...extra }, { status })

describe('Drafting the minutes (flow AD)', () => {
  it('makes the draft, says it is being made while it polls, then offers the editor and Send to the chair', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    const making: MinutesResponse = {
      ...base,
      mayGenerate: false,
      work: { kind: 'ALL', state: 'PENDING', requestedAt: '2026-12-12T09:19:00Z' },
      version: 5,
    }
    server.use(...handlers(calls, [{ ...base, undecided: [{ itemId: 'i6', number: '6' }] }, making, drafted]))
    renderRoute('/meetings/m1/minutes')

    expect(partsForPath('/meetings/m1/minutes')).toContain('minutes')
    expect(
      await screen.findByRole('heading', { name: 'Minutes — Board Risk Committee — BRC 3/2026' }),
    ).toBeInTheDocument()
    const steps = screen.getByRole('navigation', { name: 'Steps' })
    expect(
      within(steps)
        .getAllByRole('listitem')
        .map((li) => li.textContent),
    ).toEqual([
      '1Draft(now)',
      '2Chair’s review',
      '3Circulated for comments',
      '4Approved at the next meeting',
      '5Approved by the chair',
    ])
    expect(screen.getByText(/^No draft yet/)).toBeInTheDocument()
    expect(
      screen.getByText('Item 6 has no decision. Record one before you send the minutes to the chair.'),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Make the draft' }))
    expect(calls.posts[0]?.path).toBe('draft')
    expect(calls.posts[0]?.key).toMatch(UUID)
    await waitFor(() => {
      expect(screen.getAllByRole('status').map((s) => s.textContent)).toContain(
        'Making the draft… This takes a few seconds.',
      )
    })
    // The page reads itself again while the job runs, then shows the draft.
    expect(
      await screen.findByText('Draft ready (version 1, Dec 12, 2026, 3:20 PM, by K. Secretary)', {}, { timeout: 8000 }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open in the editor' })).toHaveAttribute(
      'href',
      '/meetings/m1/minutes/edit',
    )
    expect(screen.getByRole('button', { name: 'Fill item 5 again' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Send to the chair' }))
    const dialog = await screen.findByRole('dialog', { name: 'Send the draft to the chair?' })
    await user.click(within(dialog).getByRole('button', { name: 'Send' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)?.path).toBe('send-for-review')
    })
    expect(calls.posts.at(-1)?.ifMatch).toBe('"6"')
  }, 30_000)

  it('shows the structure banner with Restore item 6, and says refusals in words', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    const broken: MinutesResponse = {
      ...drafted,
      structure: {
        pending: false,
        ok: false,
        problems: [{ problem: 'MISSING', itemId: 'i6', number: '6' }],
        trackedChanges: 2,
        comments: 1,
      },
    }
    server.use(
      http.post(`${API}/meetings/m1/minutes/send-for-review`, () =>
        problem(409, { reason: 'STRUCTURE', items: [{ itemId: 'i6', number: '6', problem: 'MISSING' }] }),
      ),
      ...handlers(calls, [broken]),
    )
    renderRoute('/meetings/m1/minutes')

    const banner = await screen.findByRole('region', { name: 'Something is wrong with the draft' })
    expect(banner).toHaveTextContent('The block for item 6 is missing.')
    expect(banner).toHaveTextContent('Copies for people who declared an interest cannot be made until it is put right.')
    expect(screen.getByText(/The draft has 2 suggested changes and 1 comment left/)).toBeInTheDocument()

    await user.click(within(banner).getByRole('button', { name: 'Restore item 6' }))
    const restore = await screen.findByRole('dialog', { name: 'Restore item 6' })
    expect(restore).toHaveTextContent('Item 6 — Restricted item')
    await user.click(within(restore).getByRole('button', { name: 'Restore item 6' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({ path: 'regenerate:i6', body: { restore: true } })
    })

    await user.click(screen.getByRole('button', { name: 'Send to the chair' }))
    const send = await screen.findByRole('dialog', { name: 'Send the draft to the chair?' })
    await user.click(within(send).getByRole('button', { name: 'Send' }))
    expect(await within(send).findByRole('alert')).toHaveTextContent('The block for item 6 is missing.')
  }, 30_000)

  it('with nobody able to review, circulates with a reason', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    server.use(
      http.post(`${API}/meetings/m1/minutes/send-for-review`, () => problem(409, { reason: 'NO_REVIEWER' })),
      ...handlers(calls, [drafted]),
    )
    renderRoute('/meetings/m1/minutes')

    await user.click(await screen.findByRole('button', { name: 'Send to the chair' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Send' }))
    const circulate = await screen.findByRole('dialog', { name: 'Circulate without a review?' })
    await user.click(within(circulate).getByRole('button', { name: 'Circulate for comments' }))
    expect(within(circulate).getByText('Say why.')).toBeInTheDocument()
    expect(calls.posts).toHaveLength(0)
    await user.type(
      within(circulate).getByRole('textbox', { name: /Why is nobody reviewing/ }),
      'Both declared an interest',
    )
    await user.click(within(circulate).getByRole('button', { name: 'Circulate for comments' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({ path: 'circulate', body: { reason: 'Both declared an interest' } })
    })
  }, 30_000)

  it('shows the chair’s comments when the draft came back, and a recused secretary drafts nothing', async () => {
    const calls: Calls = { posts: [] }
    server.use(
      ...handlers(calls, [
        {
          ...drafted,
          lastReview: {
            decision: 'RETURN',
            by: { id: 'u2', name: 'Dr F. Karim' },
            comment: 'Add the sector outlook',
            at: '2026-12-13T05:00:00Z',
          },
        },
      ]),
    )
    const first = renderRoute('/meetings/m1/minutes')
    expect(await screen.findByText('Dr F. Karim sent the draft back on December 13, 2026.')).toBeInTheDocument()
    expect(screen.getByText('“Add the sector outlook”')).toBeInTheDocument()
    first.unmount()

    server.use(...handlers(calls, [{ ...base, viewerRecused: true, mayGenerate: false, undecided: [] }]))
    renderRoute('/meetings/m1/minutes')
    expect(await screen.findByText(/so another secretary drafts and edits the minutes/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Make the draft' })).not.toBeInTheDocument()
  }, 30_000)
})

describe('The chair’s review (flow AE)', () => {
  const reviewing: MinutesResponse = {
    ...base,
    state: 'CHAIR_REVIEW',
    viewerAccess: 'REVIEWER',
    myAction: 'REVIEW',
    mayGenerate: false,
    mayReview: true,
    mayEdit: true,
    editFileId: 'file1',
    mayReadCopy: true,
  }

  it('sending back needs a comment and returns the chair to the meeting', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    server.use(
      http.get(`${API}/meetings/m1/declarations/mine`, () =>
        HttpResponse.json({ meetingId: 'm1', latestRevision: 1, declarations: [], toReview: [] }),
      ),
      http.post(`${API}/meetings/m1/minutes/review`, async ({ request }) => {
        calls.posts.push({
          path: 'review',
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
          ifMatch: request.headers.get('If-Match'),
        })
        return new HttpResponse(null, { status: 204 })
      }),
      ...handlers(calls, [reviewing]),
    )
    const { router } = renderRoute('/meetings/m1/minutes')

    const card = await screen.findByRole('region', {
      name: 'Draft minutes of Board Risk Committee — BRC 3/2026 — ready for your review',
    })
    expect(within(card).getByRole('link', { name: 'Suggest changes in the document' })).toHaveAccessibleDescription(
      'Your changes show as suggestions the secretary accepts or rejects.',
    )
    await user.click(within(card).getByRole('button', { name: 'Send back with comments' }))
    expect(within(card).getByText('Say what should change.')).toBeInTheDocument()
    expect(within(card).getByRole('textbox', { name: /Comments to the secretary/ })).toHaveFocus()
    expect(calls.posts).toHaveLength(0)

    await user.type(within(card).getByRole('textbox', { name: /Comments to the secretary/ }), 'Add the sector outlook')
    await user.click(within(card).getByRole('button', { name: 'Send back with comments' }))
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/meetings/m1')
    })
    expect(calls.posts[0]).toMatchObject({
      body: { decision: 'RETURN', comment: 'Add the sector outlook' },
      ifMatch: '"4"',
    })
  }, 30_000)

  it('a secretary records the chair’s agreement given by email', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    server.use(
      ...handlers(calls, [
        {
          ...drafted,
          state: 'CHAIR_REVIEW',
          mayGenerate: false,
          mayEdit: false,
          maySendForReview: false,
          mayReview: true,
          reviewer: { id: 'u2', name: 'Dr F. Karim' },
        },
      ]),
    )
    renderRoute('/meetings/m1/minutes')
    expect(await screen.findByText('The draft is with Dr F. Karim for review.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Record the chair’s agreement…' }))
    const dialog = await screen.findByRole('dialog', { name: 'Record the chair’s agreement' })
    await user.click(within(dialog).getByRole('radio', { name: 'By phone' }))
    await user.type(within(dialog).getByRole('textbox', { name: /Note/ }), 'Call of 13 December')
    await user.click(within(dialog).getByRole('button', { name: 'Record the agreement' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({
        path: 'review',
        body: { decision: 'AGREE', how: 'PHONE', comment: 'Call of 13 December' },
      })
    })
  }, 30_000)
})

describe('Circulation (flow AF)', () => {
  it('a member reads their copy once it is made, says “No comments” and suggests a correction', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    let probes = 0
    pdf.urls.length = 0
    const reply: MinutesCommentResponse = {
      id: 'c1',
      by: { id: ME, name: 'Nadia Karim' },
      itemId: 'i5',
      itemNumber: '5',
      kind: 'SUGGESTION',
      text: 'My question on the sector outlook is not recorded',
      at: '2026-12-14T06:00:00Z',
      response: 'ADOPTED',
      responseNote: 'added to item 5',
      respondedBy: { id: 'u1', name: 'K. Secretary' },
      mine: true,
    }
    server.use(
      ...handlers(calls, [reader], {
        meeting: { ...meeting, relation: 'MEMBER', canManage: false },
        comments: [reply],
      }),
      http.get(`${API}/meetings/m1/minutes/document`, ({ request }) => {
        probes++
        expect(request.headers.get('Range')).toBe('bytes=0-0')
        return probes === 1
          ? new HttpResponse(null, { status: 202, headers: { 'Retry-After': '1' } })
          : new HttpResponse(new Uint8Array([37]), { status: 206 })
      }),
      http.post(`${API}/meetings/m1/minutes/comments`, async ({ request }) => {
        const body = await request.json()
        calls.posts.push({ path: 'comments', body, key: request.headers.get('Idempotency-Key'), ifMatch: null })
        return HttpResponse.json<MinutesCommentResponse>(
          { ...reply, id: 'c2', kind: 'NO_COMMENTS', text: null },
          { status: 201 },
        )
      }),
    )
    renderRoute('/meetings/m1/minutes')

    const card = await screen.findByRole('region', { name: 'Draft minutes of Board Risk Committee — BRC 3/2026' })
    expect(card).toHaveTextContent('Please send any comments by Saturday, December 19.')
    // Their own comment with the secretary's reply.
    expect(await within(card).findByText('My question on the sector outlook is not recorded')).toBeInTheDocument()
    expect(within(card).getByText('Adopted — K. Secretary: added to item 5')).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Read the minutes' }))
    const viewer = await screen.findByRole('dialog', { name: 'Draft minutes of Board Risk Committee — BRC 3/2026' })
    expect(
      await within(viewer).findByText('The copy is being made. It opens by itself when it is ready.'),
    ).toBeInTheDocument()
    await waitFor(
      () => {
        expect(pdf.urls).toEqual(['/api/v1/meetings/m1/minutes/document'])
      },
      { timeout: 5000 },
    )
    await user.click(within(viewer).getByRole('button', { name: 'Back to the minutes' }))

    await user.click(within(card).getByRole('button', { name: 'No comments' }))
    const none = await screen.findByRole('dialog', {
      name: 'Tell the secretary you have no comments on these minutes?',
    })
    await user.click(within(none).getByRole('button', { name: 'No comments' }))
    await waitFor(() => {
      expect(calls.posts[0]).toMatchObject({ path: 'comments', body: { kind: 'NO_COMMENTS' } })
    })
    const said = await within(card).findByText(/You told the secretary you have no comments\. You can still suggest/)
    await waitFor(() => {
      expect(said).toHaveFocus()
    })

    await user.click(within(card).getByRole('button', { name: 'Suggest a correction' }))
    const suggest = await screen.findByRole('dialog', { name: 'Suggest a correction' })
    expect(suggest).toHaveAccessibleDescription('The secretary and the chair will see this; other members will not.')
    await user.click(within(suggest).getByRole('button', { name: 'Send' }))
    expect(within(suggest).getByText('Write your suggestion.')).toBeInTheDocument()
    await user.selectOptions(
      within(suggest).getByRole('combobox', { name: 'Which part?' }),
      'Item 5 — Term loan, Delta Textiles',
    )
    await user.type(within(suggest).getByRole('textbox', { name: /Your suggestion/ }), 'Name the valuer')
    await user.click(within(suggest).getByRole('button', { name: 'Send' }))
    await waitFor(() => {
      expect(calls.posts[1]).toMatchObject({ body: { kind: 'SUGGESTION', itemId: 'i5', text: 'Name the valuer' } })
    })
  }, 30_000)

  it('the secretaries see the answers, adopt a suggestion and see the copy for a reader who declared an interest', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    pdf.urls.length = 0
    const suggestion: MinutesCommentResponse = {
      id: 'c1',
      by: { id: 'u4', name: 'S. Ali' },
      itemId: 'i5',
      itemNumber: '5',
      kind: 'SUGGESTION',
      text: 'My question is not recorded',
      at: '2026-12-14T06:00:00Z',
      mine: false,
    }
    server.use(
      http.post(`${API}/meetings/m1/minutes/comments/c1/response`, async ({ request }) => {
        calls.posts.push({
          path: 'response',
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
          ifMatch: null,
        })
        return HttpResponse.json<MinutesCommentResponse>({ ...suggestion, response: 'ADOPTED' })
      }),
      http.get(`${API}/meetings/m1/minutes/preview`, () => new HttpResponse(new Uint8Array([37]), { status: 206 })),
      ...handlers(calls, [circulated], {
        comments: [
          suggestion,
          { id: 'c0', by: { id: 'u5', name: 'R. Das' }, kind: 'NO_COMMENTS', at: '2026-12-14T05:00:00Z', mine: false },
        ],
      }),
    )
    renderRoute('/meetings/m1/minutes')

    expect(
      await screen.findByText('Circulated on December 14, 2026. Comments by Saturday, December 19.'),
    ).toBeInTheDocument()
    const panel = screen.getByRole('region', { name: 'Comments' })
    expect(panel).toHaveTextContent('7 readers: 4 no comments · 1 suggestion · 2 not yet')
    expect(await within(panel).findByText('No comments: R. Das')).toBeInTheDocument()
    await user.type(within(panel).getByRole('textbox', { name: /Note to the member/ }), 'Added to item 5')
    await user.click(within(panel).getByRole('button', { name: 'Adopt: S. Ali, Item 5' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({
        path: 'response',
        body: { response: 'ADOPTED', note: 'Added to item 5' },
      })
    })

    await user.click(screen.getByRole('button', { name: 'See the copy for…' }))
    const copies = await screen.findByRole('dialog', { name: 'See the copy for…' })
    expect(
      within(copies).getByRole('radio', { name: 'Demo Director — item 5 (declared an interest) left out' }),
    ).toBeChecked()
    expect(within(copies).getByRole('radio', { name: 'Everyone else — the full minutes' })).toBeInTheDocument()
    await user.click(within(copies).getByRole('button', { name: 'Show the copy' }))
    await waitFor(() => {
      expect(pdf.urls).toEqual(['/api/v1/meetings/m1/minutes/preview?as=u3'])
    })
    expect(screen.getByRole('button', { name: 'Take back to draft…' })).toBeInTheDocument()
  }, 30_000)
})

describe('Approval (flow AG)', () => {
  const awaiting: MinutesResponse = {
    ...base,
    state: 'AWAITING_CHAIR',
    viewerAccess: 'READER',
    myAction: 'CONFIRM',
    mayGenerate: false,
    mayConfirm: true,
    mayReadCopy: true,
    approvingMeeting: { id: 'm2', label: 'BRC 4/2026', startsAt: '2027-01-09T04:00:00Z' },
    confirmer: { id: ME, name: 'Nadia Karim' },
  }

  it('the chair approves with a fresh step-up; a refusal is said in words', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    let steppedUp = false
    let refuse = false
    server.use(
      http.post(`${API}/meetings/m1/minutes/confirm`, ({ request }) => {
        if (refuse)
          return HttpResponse.json(
            { type: 'urn:memo:problem:forbidden', status: 403, reason: 'NOT_CONFIRMER' },
            { status: 403 },
          )
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        calls.posts.push({
          path: 'confirm',
          body: null,
          key: request.headers.get('Idempotency-Key'),
          ifMatch: request.headers.get('If-Match'),
        })
        return HttpResponse.json<MinutesResponse>({ ...awaiting, state: 'APPROVED', mayConfirm: false })
      }),
      http.post(`${API}/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
      ...handlers(calls, [awaiting], { meeting: { ...meeting, relation: 'MEMBER', canManage: false } }),
    )
    renderRoute('/meetings/m1/minutes')

    const card = await screen.findByRole('region', {
      name: 'Approve the minutes of Board Risk Committee — BRC 3/2026 (December 12, 2026)',
    })
    expect(card).toHaveTextContent('Approved at BRC 4/2026 on January 9, 2027.')
    expect(card).toHaveTextContent('When you approve, the minutes are locked and kept as the permanent record.')
    expect(within(card).getByRole('link', { name: 'Not yet' })).toHaveAttribute('href', '/meetings/m1')
    await user.click(within(card).getByRole('button', { name: 'Approve the minutes' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => {
      expect(calls.posts).toHaveLength(1)
    })
    expect(calls.posts[0]?.ifMatch).toBe('"4"')
    expect(calls.posts[0]?.key).toMatch(UUID)

    refuse = true
    await user.click(within(card).getByRole('button', { name: 'Approve the minutes' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'Only the chair approves these minutes (the vice-chair when the chair declared an interest).',
    )
  }, 30_000)

  it('the approved minutes: the reader’s copy leaves out their item, the stamps, Check, and corrections with a step-up', async () => {
    const user = userEvent.setup()
    const calls: Calls = { posts: [] }
    let steppedUp = false
    const approved: MinutesResponse = {
      ...base,
      state: 'APPROVED',
      viewerAccess: 'MANAGER',
      mayGenerate: false,
      mayReadCopy: true,
      approvedAt: '2027-01-09T08:00:00Z',
      mayProposeCorrection: true,
    }
    const records: MinutesRecordResponse[] = [
      {
        version: 1,
        approvalId: 'a1',
        state: 'READY',
        current: true,
        createdAt: '2027-01-09T08:00:00Z',
        builtAt: '2027-01-09T08:01:00Z',
        file: {
          sha256: '3f9a00112233445566778899aabbccddeeff00112233445566778899aabc21e',
          compliant: true,
          full: false,
        },
        redacted: [{ itemNumber: '5', kind: 'RECUSED' }],
      },
    ]
    const approvals: MinutesApprovalResponse[] = [
      {
        id: 'a1',
        kind: 'CONFIRMATION',
        by: { id: 'u2', name: 'Dr F. Karim' },
        role: 'CHAIR',
        factors: ['PASSWORD', 'TOTP'],
        stepUpAgeSeconds: 70,
        at: '2027-01-09T08:00:00Z',
        docxSha256: 'aa',
        docxVersionId: 'v3',
        recordHash: 'bb',
      },
    ]
    let corrections: MinutesCorrectionsResponse = { corrections: [], mayDecide: true, mayPropose: true }
    server.use(
      http.get(`${API}/meetings/m1/minutes/records`, () => HttpResponse.json(records)),
      http.get(`${API}/meetings/m1/minutes/approvals`, () => HttpResponse.json(approvals)),
      http.get(`${API}/meetings/m1/minutes/corrections`, () => HttpResponse.json(corrections)),
      http.post(`${API}/meetings/m1/minutes/records/1/check`, () =>
        HttpResponse.json({ version: 1, matches: false, problems: [{ code: 'FILE_CHANGED', detail: 'x' }] }),
      ),
      http.post(`${API}/meetings/m1/minutes/corrections`, async ({ request }) => {
        const body = (await request.json()) as { text: string; reason: string; itemId?: string }
        calls.posts.push({ path: 'corrections', body, key: request.headers.get('Idempotency-Key'), ifMatch: null })
        const proposed = {
          id: 'k1',
          state: 'PROPOSED' as const,
          text: body.text,
          reason: body.reason,
          itemId: body.itemId ?? null,
          itemNumber: '6',
          proposedAt: '2027-01-15T04:00:00Z',
          proposedBy: { id: ME, name: 'Nadia Karim' },
          withheld: false,
        }
        corrections = { ...corrections, corrections: [proposed] }
        return HttpResponse.json(proposed, { status: 201 })
      }),
      http.post(`${API}/meetings/m1/minutes/corrections/k1/decide`, async ({ request }) => {
        if (!steppedUp) {
          return HttpResponse.json(
            { type: 'urn:memo:problem:step-up-required', status: 401, stepUpWindowSeconds: 300 },
            { status: 401 },
          )
        }
        calls.posts.push({
          path: 'decide',
          body: await request.json(),
          key: request.headers.get('Idempotency-Key'),
          ifMatch: null,
        })
        return HttpResponse.json({ ...corrections.corrections[0], state: 'APPROVED' })
      }),
      http.post(`${API}/auth/step-up`, () => {
        steppedUp = true
        return new HttpResponse(null, { status: 204 })
      }),
      ...handlers(calls, [approved], { meeting: { ...meeting, status: 'MINUTES_APPROVED' } }),
    )
    renderRoute('/meetings/m1/minutes')

    expect(await screen.findByText('Approved on January 9, 2027')).toBeInTheDocument()
    expect(await screen.findByText('Record version 1: PDF/A, fingerprint 3f9a00…c21e')).toBeInTheDocument()
    expect(screen.getByText('Your copy leaves out item 5 (declared an interest).')).toBeInTheDocument()
    expect(
      screen.getByText(
        'Approved by Dr F. Karim, Chair — authenticated with password and authenticator, 1 minute before',
      ),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Check' }))
    expect(await screen.findByText('Your copy no longer matches its fingerprint.')).toBeInTheDocument()
    expect(screen.getByText('Corrections: none')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Propose a correction' }))
    const propose = await screen.findByRole('dialog', { name: 'Propose a correction' })
    await user.click(within(propose).getByRole('button', { name: 'Send to the chair' }))
    expect(within(propose).getByText('Write the correction.')).toBeInTheDocument()
    expect(within(propose).getByText('Say why.')).toBeInTheDocument()
    await user.selectOptions(within(propose).getByRole('combobox', { name: 'Which part?' }), 'Item 6 — Restricted item')
    await user.type(within(propose).getByRole('textbox', { name: /The correction/ }), 'The vote was 5 to 1')
    await user.type(within(propose).getByRole('textbox', { name: /Why it is needed/ }), 'Miscounted')
    await user.click(within(propose).getByRole('button', { name: 'Send to the chair' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({
        path: 'corrections',
        body: { itemId: 'i6', text: 'The vote was 5 to 1', reason: 'Miscounted' },
      })
    })
    expect(await screen.findByText('The vote was 5 to 1')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Approve the correction' }))
    const stepUp = await screen.findByRole('dialog', { name: 'Confirm it is you' })
    await user.type(within(stepUp).getByLabelText('Code'), '123456')
    await user.click(within(stepUp).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => {
      expect(calls.posts.at(-1)).toMatchObject({ path: 'decide', body: { decision: 'APPROVE' } })
    })
  }, 30_000)
})

describe('Minutes elsewhere', () => {
  it('“Minutes to read” in My meetings, waiting ones first', async () => {
    const summary = (id: string, myAction: string, state: string): MeetingSummaryResponse => ({
      id,
      forumId: 'f1',
      forumName: 'Board Risk Committee',
      forumCode: 'BRC',
      kind: 'ORDINARY',
      startsAt: '2026-12-12T04:00:00Z',
      endsAt: '2026-12-12T07:00:00Z',
      timeZone: 'Asia/Dhaka',
      localStartsAt: '2026-12-12T10:00:00',
      localEndsAt: '2026-12-12T13:00:00',
      mode: 'IN_PERSON',
      label: `BRC ${id}/2026`,
      status: 'HELD',
      relation: 'MEMBER',
      minutes: { myAction, state },
    })
    server.use(
      sessionHandler(signedInSession()),
      myMeetingsHandler([
        summary('3', 'READ', 'CIRCULATED'),
        summary('2', 'CONFIRM', 'AWAITING_CHAIR'),
        summary('1', 'READ', 'APPROVED'),
      ]),
    )
    renderRoute('/me/meetings')
    const section = await screen.findByRole('region', { name: 'Minutes to read' })
    const cards = within(section).getAllByRole('listitem')
    expect(cards.map((c) => within(c).getByText(/^(Minutes|Draft minutes)/).textContent)).toEqual([
      'Minutes ready for your approval',
      'Draft minutes to read',
    ])
    expect(
      within(section).getByRole('link', { name: 'Open the minutes of Board Risk Committee — BRC 2/2026' }),
    ).toHaveAttribute('href', '/meetings/2/minutes')
  }, 20_000)

  /** The next meeting's agenda with its "Minutes of the previous meeting" item carrying [link]. */
  const previousMinutesAgenda = (link: ItemLink) => [
    http.get(`${API}/meetings/m1/agenda`, () =>
      HttpResponse.json<MemberAgendaResponse>({
        ...agenda,
        sections: [
          {
            id: 's0',
            kind: 'STANDARD',
            title: 'Opening',
            membersOnly: false,
            items: [
              item('i3', '3', 'Minutes of the previous meeting', {
                kind: 'STANDING',
                standingKind: 'PREVIOUS_MINUTES',
                links: [link],
              }),
            ],
          },
        ],
      }),
    ),
    http.get(`${API}/meetings/m1/declarations/mine`, () =>
      HttpResponse.json({ meetingId: 'm1', latestRevision: 1, declarations: [], toReview: [] }),
    ),
    ...handlers({ posts: [] }, [base]),
  ]

  it('the next meeting’s “Minutes of the previous meeting” item links to the minutes', async () => {
    server.use(
      ...previousMinutesAgenda({
        kind: 'MINUTES',
        meetingId: 'm0',
        label: 'Minutes of BRC 2/2026',
        state: 'CIRCULATED',
        meetingLabel: 'BRC 2/2026',
        meetingStartsAt: '2026-11-14T04:00:00Z',
        meetingTimeZone: 'Asia/Dhaka',
      }),
    )
    renderRoute('/meetings/m1')
    expect(await screen.findByRole('link', { name: 'Minutes of BRC 2/2026 (draft) — read your copy' })).toHaveAttribute(
      'href',
      '/meetings/m0/minutes',
    )
    // The officers' card on the meeting page.
    expect(await screen.findByRole('region', { name: 'Minutes' })).toHaveTextContent('The minutes: Draft.')
  }, 20_000)

  it('in Arabic the previous-minutes link is in Arabic words, never the server’s English label', async () => {
    // An unnumbered meeting: the link names it by its date on its own calendar.
    server.use(
      ...previousMinutesAgenda({
        kind: 'MINUTES',
        meetingId: 'm0',
        label: 'Minutes of the meeting of 2026-11-14',
        state: 'APPROVED',
        meetingLabel: null,
        meetingStartsAt: '2026-11-14T04:00:00Z',
        meetingTimeZone: 'Asia/Dhaka',
      }),
    )
    const first = renderRoute('/meetings/m1', { locale: 'ar' })
    const link = await screen.findByRole('link', { name: /^محضر اجتماع / })
    expect(link).toHaveAttribute('href', '/meetings/m0/minutes')
    expect(link.textContent).toMatch(/\(معتمد\) — اقرأ نسختك$/)
    expect(link.textContent).not.toMatch(/[A-Za-z]/)
    first.unmount()

    // A numbered one: the meeting's number, inside Arabic words.
    server.use(
      ...previousMinutesAgenda({
        kind: 'MINUTES',
        meetingId: 'm0',
        label: 'Minutes of BRC 2/2026',
        state: 'CIRCULATED',
        meetingLabel: 'BRC 2/2026',
        meetingStartsAt: '2026-11-14T04:00:00Z',
        meetingTimeZone: 'Asia/Dhaka',
      }),
    )
    renderRoute('/meetings/m1', { locale: 'ar' })
    const numbered = await screen.findByRole('link', { name: 'محضر BRC 2/2026 (مسودة) — اقرأ نسختك' })
    expect(numbered.textContent.replace('BRC 2/2026', '')).not.toMatch(/[A-Za-z]/)
  }, 30_000)

  it('reads right to left in Arabic without accessibility violations (the secretary and a reader)', async () => {
    const calls: Calls = { posts: [] }
    server.use(...handlers(calls, [circulated]))
    const first = renderRoute('/meetings/m1/minutes', { locale: 'ar' })
    expect(
      await screen.findByRole('heading', { name: 'المحضر — Board Risk Committee — BRC 3/2026' }),
    ).toBeInTheDocument()
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    expect(await screen.findByRole('button', { name: 'عرض نسخة…' })).toBeInTheDocument()
    await expectNoA11yViolations(first.container)
    first.unmount()

    server.use(...handlers(calls, [reader], { meeting: { ...meeting, relation: 'MEMBER', canManage: false } }))
    const second = renderRoute('/meetings/m1/minutes', { locale: 'ar' })
    expect(await screen.findByRole('button', { name: 'لا ملاحظات' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'اقتراح تصحيح' })).toBeInTheDocument()
    await expectNoA11yViolations(second.container)
  }, 30_000)

  it('the editor opens the draft through an edit session, and Back waits for the save', async () => {
    const opened: unknown[] = []
    server.use(
      http.post(`${API}/files/file1/edit-sessions`, async ({ request }) => {
        opened.push(await request.json())
        return HttpResponse.json({ sessionId: 's1', scriptUrl: '/office/api.js', config: {} })
      }),
      ...handlers({ posts: [] }, [drafted]),
    )
    renderRoute('/meetings/m1/minutes/edit')
    expect(
      await screen.findByRole('heading', { name: 'Editing the draft minutes of Board Risk Committee — BRC 3/2026' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Back to the minutes' })).toBeInTheDocument()
    await waitFor(() => {
      expect(opened).toEqual([{ mode: 'EDIT', language: 'en' }])
    })
  }, 20_000)
})
