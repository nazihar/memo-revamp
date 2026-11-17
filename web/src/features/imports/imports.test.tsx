import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type {
  ClassificationLevel,
  ImportBatchPage,
  ImportBatchRequest,
  ImportBatchView,
  ImportMappingRequest,
  ImportMappingsView,
  ImportProblemPage,
  ImportReportView,
  ImportRowPage,
  IntakeEntryView,
  IntakePage,
  IntakeRegistrationRequest,
} from '@/api/generated/types.gen'
import { navigationGroups } from '@/app/navigation'
import { ToastProvider } from '@/components/ui/toast'
import { StepUpContext } from '@/features/auth/step-up-context'
import { partsForPath } from '@/i18n/catalogue-parts'
import { accessHandler, API_ORIGIN, csrfHandler, server, sessionHandler, signedInSession } from '@/test/msw'
import { expectNoA11yViolations, renderRoute, renderWithProviders } from '@/test/render'
import { DryRunStep } from './DryRunStep'
import { clearIntakeSession } from './intake-session'
import { ImportStep } from './ImportStep'
import { MappingsStep } from './MappingsStep'
import { OriginalsStep } from './OriginalsStep'
import { ReportStep } from './ReportStep'

const batch: ImportBatchView = {
  id: 'b1',
  name: 'Head office 2015–2019',
  sourceCode: 'LEGACY-DMS',
  timeZone: 'Asia/Dhaka',
  state: 'OPEN',
  version: 4,
  allowedActions: ['EDIT', 'ATTACH_SOURCE', 'REGISTER_ORIGINALS', 'MAP', 'DRY_RUN', 'ABANDON'],
  createdAt: '2026-11-16T08:00:00Z',
  createdBy: 'u1',
  updatedAt: '2026-11-16T09:00:00Z',
  originals: { expected: 0, expectedBytes: 0, purged: 0, received: 0, receivedBytes: 0, refused: 0 },
  problems: {},
  rows: {},
  sources: [],
}

const validated: ImportBatchView = {
  ...batch,
  state: 'VALIDATED',
  version: 9,
  allowedActions: ['EDIT', 'ATTACH_SOURCE', 'REGISTER_ORIGINALS', 'MAP', 'DRY_RUN', 'START', 'ABANDON'],
  dryRunAt: '2026-11-16T11:40:00Z',
  maxRank: 1,
  sourceKind: 'XLSX',
  sources: [
    {
      table: 'WORKBOOK',
      fileId: 'f1',
      versionId: 'v1',
      name: 'legacy-export.xlsx',
      sizeBytes: 4_400_000,
      sha256: 'ab',
      uploadedAt: '2026-11-16T10:02:00Z',
    },
  ],
  originals: { expected: 0, expectedBytes: 0, purged: 0, received: 3, receivedBytes: 3_000, refused: 1 },
  rows: { READY: 2, REJECTED: 1 },
  problems: { ERROR: 1, WARNING: 2, FILE: 1 },
}

const levels: ClassificationLevel[] = [
  { id: 'l0', name: 'Internal', rank: 0 } as ClassificationLevel,
  { id: 'l1', name: 'Confidential', rank: 1 } as ClassificationLevel,
  { id: 'l2', name: 'Secret', rank: 2 } as ClassificationLevel,
]

function base(permissions = ['memo.import']) {
  return [
    sessionHandler(signedInSession(true)),
    accessHandler(permissions),
    csrfHandler(),
    http.get(`${API_ORIGIN}/api/v1/authz/classification-levels`, () => HttpResponse.json(levels)),
  ]
}

/** Renders a step as the batch page does, with toasts and a step-up that always succeeds. */
function renderStep(ui: React.ReactElement, options: { locale?: 'ar' } = {}) {
  const request = vi.fn(() => Promise.resolve(true))
  const result = renderWithProviders(
    <ToastProvider>
      <StepUpContext.Provider value={{ request }}>{ui}</StepUpContext.Provider>
    </ToastProvider>,
    options,
  )
  return Object.assign(result, { request })
}

describe('Administration → Import (Phase 3 E06 T9, flow L1–L2)', () => {
  it('is in the menu for holders of memo.import only', () => {
    const items = (permissions: string[]) =>
      navigationGroups({ signedIn: true, permissions }).flatMap((g) => g.items.map((i) => i.id))
    expect(items(['memo.import'])).toEqual(expect.arrayContaining(['imports', 'admin']))
    expect(items(['memo.create'])).not.toContain('imports')
    expect(partsForPath('/admin/imports/b1')).toEqual(expect.arrayContaining(['admin', 'imports']))
    expect(partsForPath('/memos/m1')).toContain('legacy')
  })

  it('lists the batches with their state in words, and creates a batch from the dialog', async () => {
    const user = userEvent.setup()
    const bodies: ImportBatchRequest[] = []
    const keys: (string | null)[] = []
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports`, () =>
        HttpResponse.json<ImportBatchPage>({
          batches: [
            { ...batch, id: 'b0', name: 'Branch archive (rehearsal)', state: 'VALIDATED' },
            { ...batch, id: 'b9', name: 'Head office 2010–2014', state: 'COMPLETED' },
          ],
        }),
      ),
      http.post(`${API_ORIGIN}/api/v1/admin/imports`, async ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        bodies.push((await request.json()) as ImportBatchRequest)
        return HttpResponse.json<ImportBatchView>(batch)
      }),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1`, () => HttpResponse.json<ImportBatchView>(batch)),
    )
    const { container, router } = renderRoute('/admin/imports')

    const row = (await screen.findByRole('link', { name: 'Branch archive (rehearsal)' }, { timeout: 5000 })).closest(
      'tr',
    )!
    expect(within(row).getByText('Dry run done')).toBeInTheDocument()
    expect(within(row).getByText('LEGACY-DMS')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download template' })).toHaveAttribute(
      'href',
      '/api/v1/admin/imports/template',
    )
    await expectNoA11yViolations(container)

    await user.click(screen.getByRole('button', { name: 'New batch' }))
    const dialog = await screen.findByRole('dialog', { name: 'New import batch' })
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(within(dialog).getAllByText('Required.')).toHaveLength(2)
    await user.type(within(dialog).getByRole('textbox', { name: /^Name/ }), 'Head office 2015–2019')
    await user.type(within(dialog).getByRole('textbox', { name: /Source code/ }), 'legacy-dms')
    await user.selectOptions(within(dialog).getByRole('combobox', { name: /Default classification/ }), 'l1')
    await user.type(within(dialog).getByRole('textbox', { name: /Number prefix/ }), 'LEG-')
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/admin/imports/b1')
    })
    expect(bodies[0]).toMatchObject({
      name: 'Head office 2015–2019',
      sourceCode: 'LEGACY-DMS',
      defaultClassificationId: 'l1',
      numberPrefix: 'LEG-',
    })
    expect(bodies[0]?.timeZone).toBeTruthy()
    expect(keys[0]).toMatch(/^[0-9a-f-]{36}$/)
  }, 20_000)

  it('says a refused source code and a full house in words', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports`, () => HttpResponse.json<ImportBatchPage>({ batches: [] })),
      http.post(`${API_ORIGIN}/api/v1/admin/imports`, () => {
        calls += 1
        return calls === 1
          ? HttpResponse.json(
              { status: 422, errors: [{ field: 'sourceCode', code: 'SOURCE_CODE_INVALID', message: 'x' }] },
              { status: 422 },
            )
          : HttpResponse.json({ status: 409, reason: 'TOO_MANY_BATCHES' }, { status: 409 })
      }),
    )
    renderRoute('/admin/imports')
    expect(await screen.findByText('No import batches yet.', {}, { timeout: 5000 })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New batch' }))
    const dialog = await screen.findByRole('dialog', { name: 'New import batch' })
    await user.type(within(dialog).getByRole('textbox', { name: /^Name/ }), 'X')
    await user.type(within(dialog).getByRole('textbox', { name: /Source code/ }), 'A B')
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(await within(dialog).findByText(/Use 1 to 20 capital letters/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(
      await within(dialog).findByText('Five batches are open already. Close or abandon one first.'),
    ).toBeInTheDocument()
  }, 20_000)
})

describe('the batch stepper (flow L3)', () => {
  it('shows the steps as links, the current one marked, done steps in words, and the need for a new dry run', async () => {
    const stale: ImportBatchView = { ...validated, state: 'OPEN', allowedActions: batch.allowedActions }
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1`, () => HttpResponse.json(stale)),
    )
    const { container } = renderRoute('/admin/imports/b1?step=metadata')

    const steps = await screen.findByRole('navigation', { name: 'Steps of the import' }, { timeout: 5000 })
    expect(
      within(steps)
        .getAllByRole('listitem')
        .map((l) => l.textContent),
    ).toEqual(['1Metadata (done)', '2Originals (done)', '3Mappings', '4Dry run', '5Import', '6Report'])
    expect(screen.getByTestId('step-metadata')).toHaveAttribute('aria-current', 'step')
    expect(within(steps).getAllByRole('link')).toHaveLength(5)
    expect(within(steps).getByRole('link', { name: /Dry run/ })).toHaveAttribute(
      'href',
      '/admin/imports/b1?step=dryRun',
    )
    expect(screen.getByRole('note')).toHaveTextContent('Something changed since the last dry run.')
    expect(screen.getByTestId('batch-state')).toHaveTextContent('State: Open')
    expect(screen.getByTestId('source-WORKBOOK')).toHaveTextContent(/Workbook: legacy-export\.xlsx · 4\.2\sMB/)
    await expectNoA11yViolations(container)
  }, 20_000)

  it('reads in Arabic, right to left, with no axe findings', async () => {
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1`, () => HttpResponse.json(validated)),
    )
    const { container } = renderRoute('/admin/imports/b1?step=metadata', { locale: 'ar' })
    const steps = await screen.findByRole('navigation', { name: 'خطوات الاستيراد' }, { timeout: 5000 })
    expect(
      within(steps)
        .getByText(/البيانات الوصفية/)
        .closest('[aria-current]'),
    ).toHaveAttribute('aria-current', 'step')
    expect(screen.getByTestId('batch-state')).toHaveTextContent('الحالة: اكتمل التشغيل التجريبي')
    expect(document.documentElement).toHaveAttribute('dir', 'rtl')
    await expectNoA11yViolations(container)
  }, 20_000)
})

/** A File as a folder upload gives it. */
function folderFile(path: string, size: number): File {
  const f = new File(['x'.repeat(size)], path.slice(path.lastIndexOf('/') + 1))
  Object.defineProperty(f, 'webkitRelativePath', { value: path })
  return f
}

describe('originals (flow L4)', () => {
  it('registers the folder’s paths, sends each file to its entry, and shows progress and refusals', async () => {
    const registered: IntakeRegistrationRequest[] = []
    const uploads: { owner: string | null; purpose: string | null; key: string | null }[] = []
    server.use(
      ...base(),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/files`, async ({ request }) => {
        const body = (await request.json()) as IntakeRegistrationRequest
        registered.push(body)
        return HttpResponse.json<IntakeEntryView[]>(
          body.files.map((f, i) => ({
            intakeId: `i${String(i)}`,
            path: f.path,
            size: f.size,
            state: f.path === 'old/kept.pdf' ? 'RECEIVED' : 'EXPECTED',
          })),
        )
      }),
      http.post(`${API_ORIGIN}/api/v1/files`, ({ request }) => {
        const url = new URL(request.url)
        uploads.push({
          owner: url.searchParams.get('owner'),
          purpose: url.searchParams.get('purpose'),
          key: request.headers.get('Idempotency-Key'),
        })
        return url.searchParams.get('owner') === 'i2'
          ? HttpResponse.json({ status: 422, reason: 'MALWARE' }, { status: 422 })
          : HttpResponse.json({ fileId: 'f', versionId: 'v' })
      }),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/files`, () =>
        HttpResponse.json<IntakePage>({
          files: [{ intakeId: 'i2', path: '2016/CR-0042/annex.doc', size: 4, state: 'REFUSED', refusal: 'MALWARE' }],
        }),
      ),
    )
    const user = userEvent.setup()
    const onChanged = vi.fn()
    const { container } = renderStep(<OriginalsStep batch={batch} onChanged={onChanged} />)
    expect(screen.getByText('Use a computer to send a folder.')).toBeInTheDocument()

    await user.upload(screen.getByTestId('folder-input'), [
      folderFile('HO/2016/CR-0042/main.pdf', 3),
      folderFile('HO/old/kept.pdf', 5),
      folderFile('HO/2016/CR-0042/annex.doc', 4),
      folderFile('HO/Thumbs.db', 2),
    ])
    expect(await screen.findByText('Every file of the folder was sent.', {}, { timeout: 5000 })).toBeInTheDocument()
    expect(registered).toEqual([
      {
        files: [
          { path: '2016/CR-0042/main.pdf', size: 3 },
          { path: 'old/kept.pdf', size: 5 },
          { path: '2016/CR-0042/annex.doc', size: 4 },
        ],
      },
    ])
    expect(uploads).toEqual([
      { owner: 'i0', purpose: 'imports.intake', key: 'imp-i0-3' },
      { owner: 'i2', purpose: 'imports.intake', key: 'imp-i2-4' },
    ])
    const session = screen.getByTestId('intake-session')
    expect(session).toHaveTextContent('Folder: HO · 3 files')
    expect(session).toHaveTextContent('Skipped: 1 system file (Thumbs.db, .DS_Store)')
    expect(session).toHaveTextContent('1 file was already here and not sent again.')
    expect(screen.getByTestId('intake-progress')).toHaveTextContent(/^1 of 2 files/)
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow')
    const refused = await screen.findByRole('table', { name: 'Refused originals' })
    expect(within(refused).getByText('2016/CR-0042/annex.doc')).toHaveAttribute('dir', 'ltr')
    expect(within(refused).getByText('Contains macros or malware')).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalled()
    await expectNoA11yViolations(container)
    clearIntakeSession('b1')
  }, 20_000)

  it('after a reload says how many files are here and to choose the same folder', () => {
    server.use(...base())
    renderStep(
      <OriginalsStep
        batch={{ ...validated, originals: { ...validated.originals, refused: 0 } }}
        onChanged={() => undefined}
      />,
    )
    expect(screen.getByText('3 files are already here. Choose the same folder to send the rest.')).toBeInTheDocument()
  })
})

const mappings: ImportMappingsView = {
  batchVersion: 9,
  mappings: [
    { kind: 'STATUS', legacyValue: 'Sanctioned', rowCount: 6102, target: 'APPROVED', proposed: true, confirmed: false },
    { kind: 'STATUS', legacyValue: 'Pending with GM', rowCount: 42, proposed: false, confirmed: false },
    {
      kind: 'CLASSIFICATION',
      legacyValue: 'Strictly private',
      rowCount: 3,
      legacyOrder: 2,
      target: 'l2',
      proposed: false,
      confirmed: true,
    },
    {
      kind: 'CLASSIFICATION',
      legacyValue: 'Internal',
      rowCount: 9,
      legacyOrder: 1,
      target: 'l0',
      proposed: true,
      confirmed: true,
    },
    {
      kind: 'PERSON',
      legacyValue: 'email:a.rahman@bank.example',
      rowCount: 4,
      target: 'u7',
      proposed: true,
      confirmed: false,
    },
    { kind: 'PERSON', legacyValue: 'id:EMP-7', rowCount: 2, proposed: false, confirmed: false },
  ],
  targets: [
    { kind: 'CLASSIFICATION', id: 'l0', label: 'Internal', rank: 0 },
    { kind: 'CLASSIFICATION', id: 'l1', label: 'Confidential', rank: 1 },
    { kind: 'CLASSIFICATION', id: 'l2', label: 'Secret', rank: 2 },
    { kind: 'PERSON', id: 'u7', label: 'Demo Head of Credit', detail: 'head.credit@demo.example' },
  ],
}

describe('mappings (flow M1)', () => {
  it('confirms all proposals of a kind behind the batch version, and says to run the dry run again', async () => {
    const saved: { kind: string; ifMatch: string | null; body: ImportMappingRequest[] }[] = []
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/mappings`, () => HttpResponse.json(mappings)),
      http.put(`${API_ORIGIN}/api/v1/admin/imports/b1/mappings/:kind`, async ({ request, params }) => {
        const body = (await request.json()) as ImportMappingRequest[]
        saved.push({ kind: String(params.kind), ifMatch: request.headers.get('If-Match'), body })
        return HttpResponse.json<ImportMappingsView>({
          batchVersion: 10,
          mappings: mappings.mappings
            .filter((m) => m.kind === params.kind)
            .map((m) => (m.legacyValue === 'Sanctioned' ? { ...m, confirmed: true } : m)),
          targets: [],
        })
      }),
    )
    const user = userEvent.setup()
    const onChanged = vi.fn()
    const { container } = renderStep(<MappingsStep batch={validated} onChanged={onChanged} />)
    const row = await screen.findByTestId('mapping-STATUS-Sanctioned')
    expect(within(row).getByText('6,102')).toBeInTheDocument()
    expect(within(row).getByRole('combobox', { name: 'Ours for “Sanctioned”' })).toHaveValue('APPROVED')
    expect(within(row).getByText('Proposed')).toBeInTheDocument()
    expect(
      within(screen.getByTestId('mapping-STATUS-Pending with GM')).getByRole('option', {
        name: 'Not imported (in flight)',
      }),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByTestId('confirm-all-STATUS'))
    await vi.waitFor(() => {
      expect(saved).toHaveLength(1)
    })
    expect(saved[0]).toEqual({
      kind: 'STATUS',
      ifMatch: '"9"',
      body: [{ legacyValue: 'Sanctioned', target: 'APPROVED', confirmed: true, legacyOrder: null }],
    })
    expect(await screen.findByText('Saved. Run the dry run again before importing.')).toBeInTheDocument()
    expect(onChanged).toHaveBeenCalled()
  }, 20_000)

  it('orders the classification markings with Up and Down, and names the pair that breaks the rule', async () => {
    const saved: ImportMappingRequest[][] = []
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/mappings`, () => HttpResponse.json(mappings)),
      http.put(`${API_ORIGIN}/api/v1/admin/imports/b1/mappings/CLASSIFICATION`, async ({ request }) => {
        saved.push((await request.json()) as ImportMappingRequest[])
        return HttpResponse.json<ImportMappingsView>({ ...mappings, batchVersion: 10 })
      }),
    )
    const user = userEvent.setup()
    renderStep(<MappingsStep batch={validated} onChanged={() => undefined} />)
    await user.click(await screen.findByRole('tab', { name: 'Classification' }))
    const rows = () => screen.getAllByTestId(/^mapping-CLASSIFICATION-/).map((r) => r.dataset.testid)
    expect(rows()).toEqual(['mapping-CLASSIFICATION-Internal', 'mapping-CLASSIFICATION-Strictly private'])
    expect(screen.getByText(/Order the legacy markings from least to most strict/)).toBeInTheDocument()

    // Moving the stricter marking first breaks the rule: Secret then Internal.
    await user.click(screen.getByRole('button', { name: 'Move “Strictly private” up (less strict)' }))
    expect(rows()).toEqual(['mapping-CLASSIFICATION-Strictly private', 'mapping-CLASSIFICATION-Internal'])
    expect(screen.getByTestId('mapping-error')).toHaveTextContent(
      '“Internal” is stricter than “Strictly private” but maps to a lower level.',
    )
    await user.click(screen.getByRole('button', { name: 'Save mappings' }))
    expect(saved).toHaveLength(0)

    // Down again, by keyboard, puts it right; the order is saved with every row.
    screen.getByRole('button', { name: 'Move “Strictly private” down (stricter)' }).focus()
    await user.keyboard('{Enter}')
    expect(screen.queryByTestId('mapping-error')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByRole('combobox', { name: 'Ours for “Strictly private”' }), 'l1')
    await user.click(screen.getByRole('button', { name: 'Save mappings' }))
    await vi.waitFor(() => {
      expect(saved).toEqual([[{ legacyValue: 'Strictly private', target: 'l1', confirmed: true, legacyOrder: 2 }]])
    })
  }, 20_000)

  it('shows people by email or legacy id, the linked user by name, and can unlink', async () => {
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/mappings`, () => HttpResponse.json(mappings)),
    )
    const user = userEvent.setup()
    renderStep(<MappingsStep batch={validated} onChanged={() => undefined} />)
    await user.click(await screen.findByRole('tab', { name: 'People' }))
    const linked = screen.getByTestId('mapping-PERSON-email:a.rahman@bank.example')
    expect(linked).toHaveTextContent('Email a.rahman@bank.example')
    expect(linked).toHaveTextContent('Linked: Demo Head of Credit')
    const byId = screen.getByTestId('mapping-PERSON-id:EMP-7')
    expect(byId).toHaveTextContent('Legacy id EMP-7')
    expect(byId).toHaveTextContent('Not linked: keep the name as text')
    expect(within(byId).getByRole('combobox', { name: 'Link to' })).toBeInTheDocument()
    await user.click(within(linked).getByRole('button', { name: 'Not linked: keep the name as text' }))
    expect(linked).toHaveTextContent('Not linked: keep the name as text')
  }, 20_000)
})

const problemPage: ImportProblemPage = {
  byLevel: { ERROR: 1, WARNING: 2 },
  byCode: { NUMBER_IN_USE: 1, PERSON_NOT_LINKED: 2, ALREADY_IMPORTED: 0, FILE_UNREFERENCED: 1 },
  problems: [
    {
      id: 'p1',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'NUMBER_IN_USE',
      sheet: 'MEMOS',
      rowNumber: 214,
      column: 'legacy_number',
      params: {},
    },
    {
      id: 'p2',
      stage: 'DRY_RUN',
      level: 'ERROR',
      code: 'FILE_NOT_RECEIVED',
      sheet: 'FILES',
      rowNumber: 1322,
      column: 'path',
      params: { path: '2017/CR-0108/main.pdf' },
    },
  ],
  next: 'c2',
}

describe('dry run (flow M2)', () => {
  it('shows the totals, filters the problems by level, code, sheet and row, and pages them', async () => {
    const queries: URLSearchParams[] = []
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/problems`, ({ request }) => {
        const query = new URL(request.url).searchParams
        queries.push(query)
        return HttpResponse.json<ImportProblemPage>(
          query.get('cursor') ? { ...problemPage, problems: [], next: null } : problemPage,
        )
      }),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/rows`, () =>
        HttpResponse.json<ImportRowPage>({
          rows: [
            {
              rowId: 'r1',
              rowNumber: 2,
              legacyNumber: 'CR/2016/0108',
              result: 'READY',
              errors: 0,
              warnings: 1,
              codes: ['PERSON_NOT_LINKED'],
              restricted: false,
              subject: 'Working capital line',
            },
            {
              rowId: 'r2',
              rowNumber: 3,
              legacyNumber: 'CR/2016/0109',
              result: 'READY',
              errors: 0,
              warnings: 0,
              codes: [],
              restricted: true,
            },
          ],
        }),
      ),
    )
    const user = userEvent.setup()
    const { container } = renderStep(
      <DryRunStep batch={validated} onChanged={() => undefined} onRefused={() => undefined} />,
    )
    expect(screen.getByTestId('dry-run-status')).toHaveTextContent(/^Last run .* · nothing was imported$/)
    expect(await screen.findByText('2 memos ready · 1 rejected · 2 warnings · 0 already imported')).toBeInTheDocument()
    expect(await screen.findByText('3 originals received · 1 not used by any row · 1 refused')).toBeInTheDocument()

    const table = await screen.findByTestId('problem-table')
    expect(await within(table).findByText('A memo with this number already exists.')).toBeInTheDocument()
    const fileRow = within(table).getByText('2017/CR-0108/main.pdf').closest('tr')!
    expect(within(fileRow).getByText('1,322')).toBeInTheDocument()
    expect(fileRow).toHaveTextContent('File 2017/CR-0108/main.pdf was not received.')
    await expectNoA11yViolations(container)

    await user.selectOptions(screen.getByRole('combobox', { name: 'Level' }), 'ERROR')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Code' }), 'NUMBER_IN_USE')
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sheet' }), 'MEMOS')
    await user.type(screen.getByRole('textbox', { name: 'Row' }), '214')
    await vi.waitFor(() => {
      const last = queries.at(-1)
      expect(last?.getAll('level')).toEqual(['ERROR'])
      expect(last?.getAll('code')).toEqual(['NUMBER_IN_USE'])
      expect(last?.get('sheet')).toBe('MEMOS')
      expect(last?.get('row')).toBe('214')
    })
    await user.click(await screen.findByRole('button', { name: 'Load more' }))
    await vi.waitFor(() => {
      expect(queries.some((q) => q.get('cursor') === 'c2')).toBe(true)
    })

    await user.click(screen.getByRole('tab', { name: 'Rows' }))
    const rows = await screen.findByTestId('row-table')
    expect(await within(rows).findByText('Working capital line')).toBeInTheDocument()
    expect(within(rows).getByText('Restricted')).toBeInTheDocument()
  }, 20_000)

  it('runs the dry run and says while it runs that nothing is imported', async () => {
    let posted = 0
    server.use(
      ...base(),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/dry-run`, () => {
        posted += 1
        return HttpResponse.json<ImportBatchView>({ ...batch, state: 'DRY_RUN' }, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    const onChanged = vi.fn()
    const { unmount } = renderStep(<DryRunStep batch={batch} onChanged={onChanged} onRefused={() => undefined} />)
    expect(screen.getByText('No dry run yet.')).toBeInTheDocument()
    await user.click(screen.getByTestId('run-dry-run'))
    await vi.waitFor(() => {
      expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ state: 'DRY_RUN' }))
    })
    expect(posted).toBe(1)
    unmount()
    renderStep(
      <DryRunStep
        batch={{ ...batch, state: 'DRY_RUN', allowedActions: [] }}
        onChanged={onChanged}
        onRefused={() => undefined}
      />,
    )
    expect(screen.getByTestId('dry-run-status')).toHaveTextContent('The dry run is running. Nothing is imported.')
  })
})

describe('import (flow N1, N2)', () => {
  it('confirms with the counts, asks for the step-up, and sends the rows it showed', async () => {
    const starts: { ifMatch: string | null; body: unknown }[] = []
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/problems`, () => HttpResponse.json(problemPage)),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/start`, async ({ request }) => {
        starts.push({ ifMatch: request.headers.get('If-Match'), body: await request.json() })
        return starts.length === 1
          ? HttpResponse.json({ type: 'urn:memo:problem:step-up-required', status: 401 }, { status: 401 })
          : HttpResponse.json<ImportBatchView>({ ...validated, state: 'IMPORTING' }, { status: 202 })
      }),
    )
    const user = userEvent.setup()
    const onChanged = vi.fn()
    const { request, container } = renderStep(
      <ImportStep batch={validated} onChanged={onChanged} onRefused={() => undefined} />,
    )
    await user.click(screen.getByTestId('start-import'))
    const dialog = await screen.findByRole('dialog', { name: 'Import 2 memos?' })
    expect(dialog).toHaveTextContent(
      '2 memos will be imported with their originals (3 originals received). 1 rejected row will not.',
    )
    expect(await within(dialog).findByText(/for example 2 people kept as text/)).toBeInTheDocument()
    expect(dialog).toHaveTextContent('They get no approvals, tasks, notifications or final record.')
    expect(dialog).toHaveTextContent('This cannot be undone.')
    await expectNoA11yViolations(container)
    await user.click(within(dialog).getByRole('button', { name: 'Confirm and verify identity' }))
    await vi.waitFor(() => {
      expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ state: 'IMPORTING' }))
    })
    expect(request).toHaveBeenCalledTimes(1)
    expect(starts).toEqual([
      { ifMatch: '"9"', body: { expectedRows: 2 } },
      { ifMatch: '"9"', body: { expectedRows: 2 } },
    ])
  }, 20_000)

  it.each([
    [
      422,
      { errors: [{ field: 'clearance', code: 'IMPORTER_NOT_CLEARED', message: 'x' }] },
      /not cleared to the highest classification/,
    ],
    [422, { errors: [{ field: 'problems', code: 'BATCH_PROBLEMS', message: 'x' }] }, /problems that stop the import/],
    [
      422,
      { errors: [{ field: 'expectedRows', code: 'NOTHING_TO_IMPORT', message: 'x' }] },
      /No memo is ready to import/,
    ],
    [409, { reason: 'DRY_RUN_STALE' }, /Something changed since the dry run/],
    [409, { reason: 'ANOTHER_IMPORT_RUNNING' }, /Another batch is importing/],
  ])('says the start’s refusal %s in words', async (status, body, words) => {
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/problems`, () => HttpResponse.json(problemPage)),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/start`, () =>
        HttpResponse.json({ status, ...body }, { status }),
      ),
    )
    const user = userEvent.setup()
    renderStep(<ImportStep batch={validated} onChanged={() => undefined} onRefused={() => undefined} />)
    await user.click(screen.getByTestId('start-import'))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Confirm and verify identity' }))
    expect(await within(dialog).findByTestId('start-error')).toHaveTextContent(words)
  })

  it('shows progress with Stop, the throttle, and a stopped import’s Resume (step-up) and Finish', async () => {
    const posted: string[] = []
    server.use(
      ...base(),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/:action`, ({ params }) => {
        posted.push(String(params.action))
        return HttpResponse.json<ImportBatchView>(validated, { status: 202 })
      }),
    )
    const progress = {
      rows: 9812,
      done: 4120,
      imported: 4102,
      skipped: 0,
      rejected: 12,
      failed: 6,
      notStarted: 0,
      left: 5692,
      ratePerMinute: 142,
      throttled: true,
      throttledSince: '2026-11-16T12:00:00Z',
    }
    const importing: ImportBatchView = { ...validated, state: 'IMPORTING', allowedActions: ['STOP'], progress }
    const user = userEvent.setup()
    const { container, unmount } = renderStep(
      <ImportStep batch={importing} onChanged={() => undefined} onRefused={() => undefined} />,
    )
    const box = screen.getByTestId('import-progress')
    expect(box).toHaveTextContent('Importing · 4,120 of 9,812 memos · about 40 minutes left')
    expect(box).toHaveTextContent('Imported 4,102 · Skipped 0 · Rejected 12 · Failed 6 · Not started 0')
    expect(box).toHaveTextContent(/Waiting for previews to catch up .* The import resumes by itself\./)
    await expectNoA11yViolations(container)
    await user.click(screen.getByRole('button', { name: 'Stop' }))
    await vi.waitFor(() => {
      expect(posted).toEqual(['stop'])
    })
    unmount()

    const stopped: ImportBatchView = { ...importing, state: 'STOPPED', allowedActions: ['RESUME', 'FINISH'] }
    renderStep(<ImportStep batch={stopped} onChanged={() => undefined} onRefused={() => undefined} />)
    expect(screen.getByTestId('import-status')).toHaveTextContent('Stopped. Resume the import')
    await user.click(screen.getByRole('button', { name: 'Resume' }))
    await user.click(screen.getByRole('button', { name: 'Finish without the rest' }))
    const confirm = await screen.findByRole('alertdialog', { name: 'Finish without the rest?' })
    await user.click(within(confirm).getByRole('button', { name: 'Finish without the rest' }))
    await vi.waitFor(() => {
      expect(posted).toEqual(['stop', 'resume', 'finish'])
    })
  }, 20_000)
})

const report: ImportReportView = {
  batchId: 'b1',
  state: 'COMPLETED',
  completedAt: '2026-11-16T14:00:00Z',
  balanced: true,
  complete: true,
  valid: true,
  failing: [],
  totals: {
    rows: { read: 9959, imported: 9794, rejected: 159, skipped: 0, failed: 6, notStarted: 0, rejectedByCode: {} },
    files: { listed: 31000, matched: 31000, notReceived: 0, ambiguous: 0 },
    originals: {
      registered: { files: 31204, bytes: 1 },
      stored: { files: 30964, bytes: 1 },
      imported: { files: 30880, bytes: 1 },
      heldBack: { files: 84, bytes: 1 },
      unreferenced: { files: 233, bytes: 1 },
      refused: { files: 7, bytes: 1 },
      neverUploaded: { files: 0, bytes: 0 },
    },
    renditions: { PDF: { originals: 30880, done: 30402, notPossible: 478, notNeeded: 0, failed: 0, pending: 0 } },
    fixity: { verified: 30880, importedOriginals: 30880, mismatches: 0, promotionMismatches: 0, verifyProblems: 0 },
    problems: {},
    storedNotBound: 0,
  },
  parts: [
    { kind: 'SUMMARY', fileId: 'rf1', versionId: 'rv1', name: 'report-summary.pdf', sha256: 'a', size: 10 },
    { kind: 'DETAIL', fileId: 'rf2', versionId: 'rv2', name: 'report-detail.xlsx', sha256: 'b', size: 10 },
    { kind: 'MANIFEST', fileId: 'rf3', versionId: 'rv3', name: 'manifest-sha256.txt', sha256: 'c', size: 10 },
  ],
}

describe('report (flow N3)', () => {
  it('shows balanced, complete and valid, the totals, the downloads, and closes with a reason', async () => {
    let closed: unknown
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/report`, () => HttpResponse.json(report)),
      http.post(`${API_ORIGIN}/api/v1/admin/imports/b1/close`, async ({ request }) => {
        closed = await request.json()
        return HttpResponse.json<ImportBatchView>({ ...validated, state: 'CLOSED' })
      }),
    )
    const completed: ImportBatchView = { ...validated, state: 'COMPLETED', allowedActions: ['CLOSE'] }
    const user = userEvent.setup()
    const onChanged = vi.fn()
    const { container } = renderStep(<ReportStep batch={completed} onChanged={onChanged} onRefused={() => undefined} />)
    const box = await screen.findByTestId('report')
    expect(within(box).getByText('Balanced')).toBeInTheDocument()
    expect(within(box).getByText('Complete')).toBeInTheDocument()
    expect(within(box).getByText('Valid')).toBeInTheDocument()
    expect(box).toHaveTextContent(
      'Rows read 9,959 = imported 9,794 + rejected 159 + skipped 0 + failed 6 + not started 0',
    )
    expect(box).toHaveTextContent('Fixity: 30,880 originals re-checked · 0 mismatches')
    expect(within(box).getByRole('link', { name: 'Download Summary (PDF)' })).toHaveAttribute(
      'href',
      '/api/v1/files/rf1/versions/rv1/content',
    )
    expect(
      await within(box).findByText('Classified Confidential: only people cleared to it can open these files.'),
    ).toBeInTheDocument()
    await expectNoA11yViolations(container)

    await user.click(screen.getByTestId('close-batch'))
    const dialog = await screen.findByRole('dialog', { name: 'Close this batch?' })
    expect(dialog).toHaveTextContent('Staged copies of the originals are deleted. The report and imported memos stay.')
    await user.click(within(dialog).getByRole('button', { name: 'Close batch' }))
    expect(within(dialog).getByText('Required.')).toBeInTheDocument()
    await user.type(within(dialog).getByRole('textbox', { name: /Reason/ }), 'Rehearsal done.')
    await user.click(within(dialog).getByRole('button', { name: 'Close batch' }))
    await vi.waitFor(() => {
      expect(closed).toEqual({ reason: 'Rehearsal done.' })
    })
    expect(onChanged).toHaveBeenCalledWith(expect.objectContaining({ state: 'CLOSED' }))
  }, 20_000)

  it('names the lines that do not balance, and waits for previews before the report exists', async () => {
    server.use(
      ...base(),
      http.get(`${API_ORIGIN}/api/v1/admin/imports/b1/report`, () =>
        HttpResponse.json<ImportReportView>({ ...report, balanced: false, valid: false, failing: ['FIXITY'] }),
      ),
    )
    const { unmount } = renderStep(
      <ReportStep
        batch={{ ...validated, state: 'COMPLETED', allowedActions: [] }}
        onChanged={() => undefined}
        onRefused={() => undefined}
      />,
    )
    expect(await screen.findByText('Unbalanced')).toBeInTheDocument()
    expect(screen.getByText('Not valid')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Lines that do not balance: FIXITY')
    unmount()
    renderStep(
      <ReportStep
        batch={{ ...validated, state: 'VERIFYING', allowedActions: [] }}
        onChanged={() => undefined}
        onRefused={() => undefined}
      />,
    )
    expect(screen.getByText('The report is made when the previews are done.')).toBeInTheDocument()
  })
})
