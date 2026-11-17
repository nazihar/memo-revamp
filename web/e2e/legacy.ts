import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { zip } from './docx'

/**
 * Legacy-import inputs for the journeys (Phase 3 E06 T10), written by hand at run time so nothing binary is committed and
 * the suite needs no spreadsheet, PDF or OLE2 library: a workbook of text cells, a one-page PDF, a Word 97 compound file,
 * and a folder on disk (a folder upload needs a real directory).
 */

const MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
const RELS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'

function escaped(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

function column(index: number): string {
  let name = ''
  for (let n = index + 1; n > 0; n = Math.floor((n - 1) / 26)) name = String.fromCharCode(65 + ((n - 1) % 26)) + name
  return name
}

/** An .xlsx with [sheets] (name to rows, the first row the header); every cell an inline string, blanks left out. */
export function xlsx(sheets: Record<string, string[][]>): Buffer {
  const names = Object.keys(sheets)
  const sheetXml = (rows: string[][]) =>
    `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="${MAIN}"><sheetData>` +
    rows
      .map(
        (cells, r) =>
          `<row r="${String(r + 1)}">` +
          cells
            .map((value, c) =>
              value === ''
                ? ''
                : `<c r="${column(c)}${String(r + 1)}" t="inlineStr"><is><t>${escaped(value)}</t></is></c>`,
            )
            .join('') +
          '</row>',
      )
      .join('') +
    '</sheetData></worksheet>'
  return zip([
    [
      '[Content_Types].xml',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>${names
        .map(
          (_, i) =>
            `<Override PartName="/xl/worksheets/sheet${String(i + 1)}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`,
        )
        .join('')}</Types>`,
    ],
    [
      '_rels/.rels',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="${RELS}/officeDocument" Target="xl/workbook.xml"/></Relationships>`,
    ],
    [
      'xl/workbook.xml',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="${MAIN}" xmlns:r="${RELS}"><sheets>${names
        .map((name, i) => `<sheet name="${escaped(name)}" sheetId="${String(i + 1)}" r:id="rId${String(i + 1)}"/>`)
        .join('')}</sheets></workbook>`,
    ],
    [
      'xl/_rels/workbook.xml.rels',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${names
        .map(
          (_, i) =>
            `<Relationship Id="rId${String(i + 1)}" Type="${RELS}/worksheet" Target="worksheets/sheet${String(i + 1)}.xml"/>`,
        )
        .join('')}<Relationship Id="rIdStyles" Type="${RELS}/styles" Target="styles.xml"/></Relationships>`,
    ],
    [
      'xl/styles.xml',
      `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="${MAIN}"><fonts count="1"><font/></fonts><fills count="1"><fill/></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf/></cellXfs></styleSheet>`,
    ],
    ...names.map((name, i): [string, string] => [
      `xl/worksheets/sheet${String(i + 1)}.xml`,
      sheetXml(sheets[name] ?? []),
    ]),
  ])
}

/** A one-page PDF showing [text] in Helvetica, with a correct cross-reference table. */
export function pdf(text: string): Buffer {
  const content = `BT /F1 14 Tf 72 770 Td (${text.replace(/[\\()]/g, '\\$&')}) Tj ET`
  const objects = [
    '<</Type/Catalog/Pages 2 0 R>>',
    '<</Type/Pages/Kids[3 0 R]/Count 1>>',
    '<</Type/Page/Parent 2 0 R/MediaBox[0 0 595 842]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>',
    `<</Length ${String(content.length)}>>\nstream\n${content}\nendstream`,
    '<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>',
  ]
  let body = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((object, i) => {
    offsets.push(body.length)
    body += `${String(i + 1)} 0 obj\n${object}\nendobj\n`
  })
  const xref = body.length
  body +=
    `xref\n0 ${String(objects.length + 1)}\n0000000000 65535 f \n` +
    offsets.map((o) => `${String(o).padStart(10, '0')} 00000 n \n`).join('') +
    `trailer\n<</Size ${String(objects.length + 1)}/Root 1 0 R>>\nstartxref\n${String(xref)}\n%%EOF\n`
  return Buffer.from(body, 'latin1')
}

/**
 * A Word 97 `.doc` as the Tika server types it: an OLE2 compound file (version 3, 512-byte sectors) whose only stream is
 * `WordDocument`, made 4,096 bytes long so it sits in ordinary sectors and needs no mini stream. Sector 0 is the FAT,
 * sector 1 the directory, sectors 2 to 9 the stream. No converter can open it; that is not what the journey checks.
 */
export function legacyDoc(text: string): Buffer {
  const SECTOR = 512
  const END = 0xfffffffe
  const FREE = 0xffffffff
  const NONE = 0xffffffff
  const streamSectors = 8
  const out = Buffer.alloc(SECTOR * (2 + streamSectors + 1))
  // Header.
  Buffer.from('d0cf11e0a1b11ae1', 'hex').copy(out, 0)
  out.writeUInt16LE(0x3e, 24)
  out.writeUInt16LE(3, 26)
  out.writeUInt16LE(0xfffe, 28)
  out.writeUInt16LE(9, 30)
  out.writeUInt16LE(6, 32)
  out.writeUInt32LE(1, 44) // FAT sectors
  out.writeUInt32LE(1, 48) // first directory sector
  out.writeUInt32LE(4096, 56) // mini stream cutoff
  out.writeUInt32LE(END, 60) // no mini FAT
  out.writeUInt32LE(END, 68) // no DIFAT sectors
  for (let i = 0; i < 109; i++) out.writeUInt32LE(i === 0 ? 0 : FREE, 76 + i * 4)
  // FAT (sector 0).
  const fat = SECTOR
  for (let i = 0; i < SECTOR / 4; i++) out.writeUInt32LE(FREE, fat + i * 4)
  out.writeUInt32LE(0xfffffffd, fat) // the FAT itself
  out.writeUInt32LE(END, fat + 4) // the directory
  for (let s = 2; s < 2 + streamSectors; s++) out.writeUInt32LE(s === 1 + streamSectors ? END : s + 1, fat + s * 4)
  // Directory (sector 1): the root and the WordDocument stream; two empty entries.
  const entry = (index: number, name: string, type: number, child: number, start: number, size: number) => {
    const at = SECTOR * 2 + index * 128
    Buffer.from(`${name}\0`, 'utf16le').copy(out, at)
    out.writeUInt16LE((name.length + 1) * 2, at + 64)
    out.writeUInt8(type, at + 66)
    out.writeUInt8(1, at + 67)
    out.writeUInt32LE(NONE, at + 68)
    out.writeUInt32LE(NONE, at + 72)
    out.writeUInt32LE(child, at + 76)
    out.writeUInt32LE(start, at + 116)
    out.writeUInt32LE(size, at + 120)
  }
  entry(0, 'Root Entry', 5, 1, END, 0)
  entry(1, 'WordDocument', 2, NONE, 2, streamSectors * SECTOR)
  for (const index of [2, 3]) {
    const at = SECTOR * 2 + index * 128
    out.writeUInt32LE(NONE, at + 68)
    out.writeUInt32LE(NONE, at + 72)
    out.writeUInt32LE(NONE, at + 76)
  }
  // The stream (sectors 2 to 9).
  Buffer.from(`Legacy memo: ${text}`, 'latin1').copy(out, SECTOR * 3)
  return out.subarray(0, SECTOR * (2 + streamSectors + 1))
}

/** Writes [files] (relative path to content) under a new folder named [name] in the temporary directory; returns it. */
export function folder(name: string, files: Record<string, Buffer>): string {
  const root = join(mkdtempSync(join(tmpdir(), 'legacy-')), name)
  for (const [path, content] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true })
    writeFileSync(join(root, path), content)
  }
  return root
}
