import { createHmac, randomUUID } from 'node:crypto'
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { join } from 'node:path'
import { inflateRawSync } from 'node:zlib'
import { expect, test, type Page } from '@playwright/test'

/**
 * Phase 4 E06 T1 spike (ADR-0066): do ONLYOFFICE 10.0.0.1's editor and its save keep the locked, tagged block-level
 * content controls the minutes draft has one per item? And does review mode record tracked changes?
 *
 * Not part of any project of the main config: run it on purpose with its own config against a document server, with the
 * draft the backend writes (`MinutesControlsRoundTripIT`, `MEMO_SPIKE_OUT`):
 *   SPIKE_DOCSERVER=http://127.0.0.1:8091 SPIKE_JWT=<secret> SPIKE_DOCX=<draft.docx> SPIKE_OUT=<dir> \
 *     pnpm exec playwright test -c e2e/spike/playwright.config.ts
 * The document server fetches the file from, and calls back to, a small server this spec starts on SPIKE_PORT (8092),
 * reached from the container as SPIKE_HOST (host.docker.internal). Saved files land in SPIKE_OUT for the JVM checks.
 */
const docserver = process.env.SPIKE_DOCSERVER ?? ''
const secret = process.env.SPIKE_JWT ?? ''
const input = process.env.SPIKE_DOCX ?? ''
const out = process.env.SPIKE_OUT ?? ''
const port = Number(process.env.SPIKE_PORT ?? '8092')
const host = process.env.SPIKE_HOST ?? 'host.docker.internal'

interface Saved {
  status: number
  file: string
}
const files = new Map<string, Buffer>()
const saved = new Map<string, Saved[]>()
let server: Server | undefined

function base64url(data: Buffer | string): string {
  return Buffer.from(data).toString('base64url')
}

function sign(payload: object): string {
  const head = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }))
  const body = base64url(JSON.stringify(payload))
  const signature = createHmac('sha256', secret).update(`${head}.${body}`).digest('base64url')
  return `${head}.${body}.${signature}`
}

/** The entries of a ZIP archive (stored or deflated), read from its central directory. */
function unzip(zip: Buffer): Map<string, Buffer> {
  const entries = new Map<string, Buffer>()
  const end = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]))
  let at = zip.readUInt32LE(end + 16)
  const count = zip.readUInt16LE(end + 10)
  for (let i = 0; i < count; i++) {
    const method = zip.readUInt16LE(at + 10)
    const size = zip.readUInt32LE(at + 20)
    const nameLength = zip.readUInt16LE(at + 28)
    const extra = zip.readUInt16LE(at + 30)
    const comment = zip.readUInt16LE(at + 32)
    const local = zip.readUInt32LE(at + 42)
    const name = zip.toString('utf8', at + 46, at + 46 + nameLength)
    const start = local + 30 + zip.readUInt16LE(local + 26) + zip.readUInt16LE(local + 28)
    const data = zip.subarray(start, start + size)
    entries.set(name, method === 0 ? data : inflateRawSync(data))
    at += 46 + nameLength + extra + comment
  }
  return entries
}

/** What the saved document's body says about its controls. */
function controls(docx: Buffer) {
  const xml = unzip(docx).get('word/document.xml')?.toString('utf8') ?? ''
  const tags = [...xml.matchAll(/<w:tag w:val="([^"]*)"/g)].map((m) => m[1])
  return {
    sdt: (xml.match(/<w:sdt>/g) ?? []).length,
    tags,
    locks: [...xml.matchAll(/<w:lock w:val="([^"]*)"/g)].map((m) => m[1]),
    insertions: (xml.match(/<w:ins /g) ?? []).length,
    deletions: (xml.match(/<w:del /g) ?? []).length,
    xml,
  }
}

function editorPage(key: string, review: boolean): string {
  const config = {
    documentType: 'word',
    width: '100%',
    height: '100%',
    document: {
      fileType: 'docx',
      key,
      title: 'minutes.docx',
      url: `http://${host}:${String(port)}/file/${key}`,
      permissions: { edit: !review, review, download: false, print: true, comment: false, chat: false },
    },
    editorConfig: {
      mode: 'edit',
      lang: 'en',
      callbackUrl: `http://${host}:${String(port)}/callback/${key}`,
      user: { id: review ? 'chair' : 'secretary', name: review ? 'Demo Chair' : 'Demo Secretary' },
      coEditing: { mode: 'fast', change: false },
      customization: {
        forcesave: true,
        plugins: false,
        macros: false,
        macrosMode: 'disable',
        help: false,
        feedback: false,
      },
    },
  }
  const signed = { ...config, token: sign(config) }
  return `<!doctype html><html style="height:100%"><head><meta charset="utf-8"><title>spike</title>
<script src="${docserver}/web-apps/apps/api/documents/api.js"></script></head>
<body style="margin:0;height:100vh"><div id="editor"></div><script>
window.ready = false; window.events = [];
const config = ${JSON.stringify(signed)};
config.events = {
  onDocumentReady: () => { window.ready = true },
  onError: (e) => window.events.push('error ' + JSON.stringify(e.data)),
  onWarning: (e) => window.events.push('warning ' + JSON.stringify(e.data)),
};
window.editor = new DocsAPI.DocEditor('editor', config);
</script></body></html>`
}

async function waitForSave(key: string, count: number): Promise<Saved> {
  await expect.poll(() => saved.get(key)?.length ?? 0, { timeout: 90_000 }).toBeGreaterThanOrEqual(count)
  const list = saved.get(key) ?? []
  const last = list[count - 1]
  if (!last) throw new Error(`no save ${String(count)} for ${key}`)
  return last
}

async function forceSave(key: string): Promise<number> {
  const payload = { c: 'forcesave', key }
  const response = await fetch(`${docserver}/command`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ ...payload, token: sign(payload) }),
  })
  const answer = (await response.json()) as { error: number }
  return answer.error
}

async function open(page: Page, docx: Buffer, review = false): Promise<string> {
  const key = randomUUID()
  files.set(key, docx)
  await page.goto(`http://127.0.0.1:${String(port)}/editor/${key}?review=${String(review)}`)
  await page.waitForFunction(() => (window as unknown as { ready: boolean }).ready, undefined, { timeout: 120_000 })
  await page.waitForTimeout(2_000)
  return key
}

const editor = (page: Page) => page.frameLocator('iframe[name="frameEditor"]')

/** Selects the first match of [text] with the editor's search, then closes the search so typing goes to the document. */
async function find(page: Page, text: string) {
  await editor(page)
    .locator('#editor_sdk')
    .click({ position: { x: 400, y: 300 } })
  await page.keyboard.press('Control+F')
  await page.waitForTimeout(800)
  await page.keyboard.press('Control+A')
  await page.keyboard.type(text)
  await page.keyboard.press('Enter')
  await page.waitForTimeout(800)
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: join(out, `${name}.png`) })
}

function report(name: string, docx: Buffer) {
  const found = controls(docx)
  const line = `${name}: sdt=${String(found.sdt)} tags=${found.tags.join(',')} locks=${found.locks.join(',')} ins=${String(found.insertions)} del=${String(found.deletions)}`
  console.log(line)
  writeFileSync(join(out, `${name}.txt`), `${line}\n`)
  return found
}

test.describe('ONLYOFFICE keeps the minutes item controls', () => {
  test.skip(
    !docserver || !secret || !input || !out,
    'spike only: set SPIKE_DOCSERVER, SPIKE_JWT, SPIKE_DOCX and SPIKE_OUT',
  )
  test.describe.configure({ mode: 'serial' })
  test.setTimeout(600_000)

  test.beforeAll(async () => {
    mkdirSync(out, { recursive: true })
    server = createServer((request, response) => {
      const url = new URL(request.url ?? '/', 'http://localhost')
      const [, kind, key = ''] = url.pathname.split('/')
      if (kind === 'editor') {
        response
          .writeHead(200, { 'Content-Type': 'text/html' })
          .end(editorPage(key, url.searchParams.get('review') === 'true'))
      } else if (kind === 'file') {
        response.writeHead(200, { 'Content-Type': 'application/octet-stream' }).end(files.get(key))
      } else if (kind === 'callback') {
        let body = ''
        request.on('data', (chunk: Buffer) => (body += chunk.toString('utf8')))
        request.on('end', () => {
          void (async () => {
            const callback = JSON.parse(body) as { status: number; url?: string }
            console.log(`callback ${key} status ${String(callback.status)}`)
            if (callback.url && (callback.status === 2 || callback.status === 6)) {
              const fetched = Buffer.from(await (await fetch(callback.url)).arrayBuffer())
              const list = saved.get(key) ?? []
              const file = join(out, `${key}-${String(list.length + 1)}-status${String(callback.status)}.docx`)
              writeFileSync(file, fetched)
              list.push({ status: callback.status, file })
              saved.set(key, list)
            }
            response.writeHead(200, { 'Content-Type': 'application/json' }).end('{"error":0}')
          })()
        })
      } else {
        response.writeHead(404).end()
      }
    })
    await new Promise<void>((resolve) => server?.listen(port, '0.0.0.0', resolve))
  })

  test.afterAll(() => {
    server?.close()
  })

  test('edit inside and outside the controls, try to delete one, undo, save, reopen', async ({ page }) => {
    const draft = readFileSync(input)
    report('0-draft', draft)
    const key = await open(page, draft)
    await shot(page, '1-opened')

    // Inside item 5's control: after its discussion.
    await find(page, 'borrower')
    await page.keyboard.press('End')
    await page.keyboard.type(' EDITED-INSIDE-5')
    // Outside every control: the first line and the closing line.
    await page.keyboard.press('Control+Home')
    await page.keyboard.type('EDITED-TOP ')
    await page.keyboard.press('Control+End')
    await page.keyboard.type(' EDITED-CLOSING')
    await shot(page, '2-edited')
    const editSave = await forceSave(key)
    console.log(`force save after the edits: error ${String(editSave)}`)
    if (editSave === 0) report('2-edited', readFileSync((await waitForSave(key, 1)).file))

    // A selection from item 6's heading to inside item 7, deleted.
    await find(page, 'Agenda item 6')
    await page.keyboard.press('Home')
    for (let i = 0; i < 12; i++) await page.keyboard.press('Shift+ArrowDown')
    await shot(page, '3-selected')
    await page.keyboard.press('Delete')
    await page.waitForTimeout(1_000)
    await shot(page, '3-after-delete')
    let count = (saved.get(key) ?? []).length
    const deleteSave = await forceSave(key)
    console.log(`force save after the deletion: error ${String(deleteSave)}`)
    if (deleteSave === 0) report('3-after-delete', readFileSync((await waitForSave(key, count + 1)).file))

    // Everything selected and deleted.
    await page.keyboard.press('Control+A')
    await page.keyboard.press('Delete')
    await page.waitForTimeout(1_000)
    await shot(page, '4-after-delete-all')
    count = (saved.get(key) ?? []).length
    const allSave = await forceSave(key)
    console.log(`force save after deleting all: error ${String(allSave)}`)
    if (allSave === 0) report('4-after-delete-all', readFileSync((await waitForSave(key, count + 1)).file))

    // Undo both deletions.
    for (let i = 0; i < 2; i++) {
      await page.keyboard.press('Control+Z')
      await page.waitForTimeout(500)
    }
    await shot(page, '5-after-undo')
    count = (saved.get(key) ?? []).length
    const undoSave = await forceSave(key)
    console.log(`force save after undo: error ${String(undoSave)}`)
    if (undoSave === 0) report('5-after-undo', readFileSync((await waitForSave(key, count + 1)).file))

    // Close: the document server saves the result (status 2).
    count = (saved.get(key) ?? []).length
    await page.goto('about:blank')
    const closed = await waitForSave(key, count + 1)
    const final = readFileSync(closed.file)
    writeFileSync(join(out, 'saved-edit.docx'), final)
    const found = report('6-closed', final)
    expect(found.xml).toContain('EDITED-INSIDE-5')

    // Reopen the saved file, type once more, save again.
    const again = await open(page, final)
    await shot(page, '7-reopened')
    await editor(page)
      .locator('#editor_sdk')
      .click({ position: { x: 400, y: 300 } })
    await page.keyboard.press('Control+End')
    await page.keyboard.type(' REOPENED')
    await page.waitForTimeout(3_000)
    await page.goto('about:blank')
    const reopened = readFileSync((await waitForSave(again, 1)).file)
    writeFileSync(join(out, 'saved-reopened.docx'), reopened)
    report('8-reopened-saved', reopened)
  })

  test('review mode records the chair suggestions as tracked changes', async ({ page }) => {
    const key = await open(page, readFileSync(input), true)
    await shot(page, '9-review-opened')
    await find(page, 'borrower')
    await page.keyboard.press('End')
    await page.keyboard.type(' SUGGESTED-BY-CHAIR')
    await find(page, 'paper on item 3')
    await page.keyboard.press('Delete')
    await page.waitForTimeout(1_000)
    // Try to delete item 6 in review mode too.
    await find(page, 'Agenda item 6')
    await page.keyboard.press('Home')
    for (let i = 0; i < 12; i++) await page.keyboard.press('Shift+ArrowDown')
    await page.keyboard.press('Delete')
    await page.waitForTimeout(1_000)
    await shot(page, '10-review-edited')
    await page.goto('about:blank')
    const reviewed = readFileSync((await waitForSave(key, 1)).file)
    writeFileSync(join(out, 'saved-review.docx'), reviewed)
    const found = report('11-review-saved', reviewed)
    expect(found.insertions).toBeGreaterThan(0)
  })

  test('at the boundaries: an item emptied, a backspace into the item before, undo', async ({ page }) => {
    const key = await open(page, readFileSync(input))
    // Item 6's content selected up to its outcome, inside its control only.
    await find(page, 'Agenda item 6')
    await page.keyboard.press('Home')
    for (let i = 0; i < 7; i++) await page.keyboard.press('Shift+ArrowDown')
    await page.keyboard.press('Shift+End')
    await page.keyboard.press('Delete')
    await page.waitForTimeout(1_000)
    await shot(page, '12-item6-emptied')
    // Backspace at the start of item 7 (towards item 6's control).
    await find(page, 'Agenda item 7')
    await page.keyboard.press('Home')
    for (let i = 0; i < 3; i++) await page.keyboard.press('Backspace')
    await shot(page, '13-backspace')
    let count = (saved.get(key) ?? []).length
    await page.waitForTimeout(3_000)
    if ((await forceSave(key)) === 0) report('13-boundaries', readFileSync((await waitForSave(key, count + 1)).file))
    for (let i = 0; i < 6; i++) {
      await page.keyboard.press('Control+Z')
      await page.waitForTimeout(400)
    }
    await shot(page, '14-undone')
    // Everything undone: closing sends status 4 (no changes since the force save) or saves the undone document.
    count = (saved.get(key) ?? []).length
    await page.waitForTimeout(3_000)
    await page.goto('about:blank')
    await page.waitForTimeout(20_000)
    const final = (saved.get(key) ?? [])[count]
    if (final) report('15-boundaries-saved', readFileSync(final.file))
    else console.log('15-boundaries: closed without a new save')
  })
})
