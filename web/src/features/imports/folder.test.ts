import { describe, expect, it, vi } from 'vitest'
import type { IntakeEntryView } from '@/api/generated/types.gen'
import {
  chunks,
  intakeKey,
  isSystemFile,
  MAX_ORIGINAL_BYTES,
  relativePath,
  selectFolder,
  toSend,
  validPath,
  type LocalOriginal,
} from './folder'
import { beginIntake, clearIntakeSession } from './intake-session'

/** A File as a folder upload gives it: name, size and the path relative to the chosen folder's parent. */
function file(path: string, size = 10): File {
  const name = path.slice(path.lastIndexOf('/') + 1)
  const f = new File(['x'], name)
  Object.defineProperty(f, 'size', { value: size })
  Object.defineProperty(f, 'webkitRelativePath', { value: path })
  return f
}

describe('folder selection (design E06 §5.1)', () => {
  it('drops the chosen folder’s own name, uses "/" and normalises to NFC', () => {
    expect(relativePath(file('HO-archive/2016/CR-0042/annex.doc'))).toBe('2016/CR-0042/annex.doc')
    expect(relativePath(file('HO-archive\\2016\\a.pdf'))).toBe('2016/a.pdf')
    // "\u00e9" as e + combining acute becomes the single NFC code point.
    expect(relativePath(file('HO/cafe\u0301.pdf'))).toBe('caf\u00e9.pdf')
    expect(relativePath(new File(['x'], 'loose.pdf'))).toBe('loose.pdf')
  })

  it('knows the system files nobody means to import', () => {
    for (const name of ['.DS_Store', 'Thumbs.db', 'thumbs.db', 'desktop.ini', '~$draft.docx']) {
      expect(isSystemFile(name), name).toBe(true)
    }
    expect(isSystemFile('report.pdf')).toBe(false)
  })

  it('refuses paths the server would refuse', () => {
    expect(validPath('2016/a.pdf')).toBe(true)
    for (const bad of [
      '',
      '/a.pdf',
      'a//b.pdf',
      'a/../b.pdf',
      './a.pdf',
      'a\u0007.pdf',
      'a\u202eb.pdf',
      'x'.repeat(1025),
    ]) {
      expect(validPath(bad), JSON.stringify(bad)).toBe(false)
    }
    expect(validPath(Array.from({ length: 33 }, () => 'd').join('/'))).toBe(false)
  })

  it('sorts a folder into files to send, skipped system files, too-large files and unusable paths', () => {
    const selection = selectFolder([
      file('HO/2016/a.pdf', 100),
      file('HO/2016/Thumbs.db', 5),
      file('HO/.DS_Store', 5),
      file('HO/2017/huge.tif', MAX_ORIGINAL_BYTES + 1),
      file('HO/2017/b\u0007.pdf'),
      file('HO/2018/cafe\u0301.pdf', 7),
      file('HO/2018/caf\u00e9.pdf', 7),
    ])
    expect(selection.folder).toBe('HO')
    expect(selection.files.map((f) => f.path)).toEqual(['2016/a.pdf', '2018/caf\u00e9.pdf'])
    expect(selection.bytes).toBe(107)
    expect(selection.skipped).toEqual(['2016/Thumbs.db', '.DS_Store'])
    expect(selection.tooLarge.map((f) => f.path)).toEqual(['2017/huge.tif'])
    // A control character, and a second file with the same path after NFC.
    expect(selection.invalid).toEqual(['2017/b\u0007.pdf', '2018/caf\u00e9.pdf'])
  })

  it('registers paths in chunks of at most 1,000', () => {
    const items = Array.from({ length: 2500 }, (_, i) => i)
    expect(chunks(items).map((c) => c.length)).toEqual([1000, 1000, 500])
    expect(chunks([])).toEqual([])
  })
})

describe('resume (design E06 §5.1)', () => {
  const local = (path: string, size: number): LocalOriginal => ({ path, size, file: file(`HO/${path}`, size) })
  const entry = (path: string, state: IntakeEntryView['state'], size: number): IntakeEntryView => ({
    intakeId: `i-${path}`,
    path,
    state,
    size,
  })

  it('sends only what the server expects or holds at another size', () => {
    const pending = toSend(
      [
        local('new.pdf', 5),
        local('same.pdf', 5),
        local('changed.pdf', 9),
        local('refused.doc', 4),
        local('fixed.doc', 6),
        local('gone.pdf', 3),
      ],
      [
        entry('new.pdf', 'EXPECTED', 5),
        entry('same.pdf', 'RECEIVED', 5),
        entry('changed.pdf', 'RECEIVED', 8),
        entry('refused.doc', 'REFUSED', 4),
        entry('fixed.doc', 'REFUSED', 5),
        entry('gone.pdf', 'PURGED', 1),
      ],
    )
    expect(pending.map((p) => [p.path, p.intakeId])).toEqual([
      ['new.pdf', 'i-new.pdf'],
      ['changed.pdf', 'i-changed.pdf'],
      ['fixed.doc', 'i-fixed.doc'],
    ])
  })

  it('keys each upload by its entry and size, so a retry is the same upload', () => {
    expect(intakeKey('abc', 42)).toBe('imp-abc-42')
  })

  it('registers the folder, then sends only the rest, counting what was already here', async () => {
    const registered: string[][] = []
    const sent: string[] = []
    const selection = selectFolder([file('HO/a.pdf', 5), file('HO/b.pdf', 6), file('HO/c.pdf', 7)])
    const { useIntakeSession } = await import('./intake-session')
    expect(useIntakeSession).toBeTypeOf('function')
    await beginIntake('batch-1', selection, {
      register: (_batch, files) => {
        registered.push(files.map((f) => f.path))
        return Promise.resolve([
          entry('a.pdf', 'RECEIVED', 5),
          entry('b.pdf', 'EXPECTED', 6),
          entry('c.pdf', 'RECEIVED', 1),
        ])
      },
      send: (item) => {
        sent.push(item.path)
        return Promise.resolve({ kind: 'sent' })
      },
    })
    await vi.waitFor(() => {
      expect(sent).toEqual(['b.pdf', 'c.pdf'])
    })
    expect(registered).toEqual([['a.pdf', 'b.pdf', 'c.pdf']])
    clearIntakeSession('batch-1')
  })
})
