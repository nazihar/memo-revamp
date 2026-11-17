import type { IntakeEntryView } from '@/api/generated/types.gen'

/**
 * The originals' folder upload (design E06 §5.1, ux-review L4), as plain logic the screen drives: which files of a
 * chosen folder are sent, the path each is registered under, the chunks of the registration, and which entries a resume
 * still has to send. The server applies the same path rules (`PathRules`) and is the judge; these only spare a round
 * trip and keep the counts honest.
 */

/** Largest original the `imports.intake` purpose takes (design §10). */
export const MAX_ORIGINAL_BYTES = 100 * 1024 * 1024
/** Paths registered per call (`POST …/files`, 1–1,000). */
export const REGISTER_CHUNK = 1000
/** Uploads in flight at once (design §5.1 step 3). */
export const PARALLEL = 3
const MAX_PATH = 1024
const MAX_SEGMENTS = 32

/** Operating-system files nobody means to import (design §5.1 step 1): counted as skipped, never sent. */
export function isSystemFile(name: string): boolean {
  const lower = name.toLowerCase()
  return lower === '.ds_store' || lower === 'thumbs.db' || lower === 'desktop.ini' || name.startsWith('~$')
}

/**
 * The path an original is registered under: relative to the chosen folder (its own name dropped), `/` as separator,
 * NFC. A file chosen without a folder keeps its name.
 */
export function relativePath(file: Pick<File, 'name'> & { webkitRelativePath?: string }): string {
  const full = (
    file.webkitRelativePath === undefined || file.webkitRelativePath === '' ? file.name : file.webkitRelativePath
  ).replace(/\\/g, '/')
  const parts = full.split('/')
  const inside = parts.length > 1 ? parts.slice(1) : parts
  return inside.join('/').normalize('NFC')
}

/** The folder's own name, from the first file's relative path. */
export function folderName(files: readonly (Pick<File, 'name'> & { webkitRelativePath?: string })[]): string {
  const first = files[0]?.webkitRelativePath?.replace(/\\/g, '/') ?? ''
  return first.includes('/') ? first.slice(0, first.indexOf('/')) : ''
}

/** Control and format characters, line and paragraph separators (PathRules refuses them too). */
function forbidden(path: string): boolean {
  for (const ch of path) {
    const c = ch.codePointAt(0) ?? 0
    if (
      c <= 0x1f ||
      (c >= 0x7f && c <= 0x9f) ||
      (c >= 0x200b && c <= 0x200f) ||
      (c >= 0x2028 && c <= 0x202e) ||
      (c >= 0x2060 && c <= 0x2064) ||
      c === 0xfeff
    ) {
      return true
    }
  }
  return false
}

/** Whether the server's path rules (design §5.1) would take [path]. */
export function validPath(path: string): boolean {
  if (!path || path.length > MAX_PATH || path.startsWith('/') || path.includes('\\') || forbidden(path)) {
    return false
  }
  const segments = path.split('/')
  return segments.length <= MAX_SEGMENTS && segments.every((s) => s !== '' && s !== '.' && s !== '..')
}

export interface LocalOriginal {
  path: string
  size: number
  file: File
}

export interface FolderSelection {
  folder: string
  /** What will be registered and sent. */
  files: LocalOriginal[]
  bytes: number
  /** System files left out. */
  skipped: string[]
  /** Larger than the purpose takes: neither registered nor sent. */
  tooLarge: LocalOriginal[]
  /** Paths the server would refuse, and a second file with the same path after NFC. */
  invalid: string[]
}

/** Sorts the files of a chosen folder into what is sent and what is left out, and why. */
export function selectFolder(chosen: readonly File[]): FolderSelection {
  const selection: FolderSelection = {
    folder: folderName(chosen),
    files: [],
    bytes: 0,
    skipped: [],
    tooLarge: [],
    invalid: [],
  }
  const seen = new Set<string>()
  for (const file of chosen) {
    const path = relativePath(file)
    if (isSystemFile(file.name)) {
      selection.skipped.push(path)
    } else if (!validPath(path) || seen.has(path)) {
      selection.invalid.push(path)
    } else if (file.size > MAX_ORIGINAL_BYTES) {
      seen.add(path)
      selection.tooLarge.push({ path, size: file.size, file })
    } else {
      seen.add(path)
      selection.files.push({ path, size: file.size, file })
      selection.bytes += file.size
    }
  }
  return selection
}

/** [items] in consecutive groups of at most [size]. */
export function chunks<T>(items: readonly T[], size = REGISTER_CHUNK): T[][] {
  const out: T[][] = []
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size))
  return out
}

export interface Pending extends LocalOriginal {
  intakeId: string
  /** The server's state before this upload. */
  state: IntakeEntryView['state']
}

/**
 * What still has to be sent once the paths are registered (design §5.1 resume): a path the server expects, and a path
 * it holds at another size (a changed file, sent again to replace it). A path received at the same size is already
 * here; one refused at the same size would be refused again (it is listed with its reason instead); a purged one can
 * never take an upload.
 */
export function toSend(local: readonly LocalOriginal[], entries: readonly IntakeEntryView[]): Pending[] {
  const byPath = new Map(entries.map((e) => [e.path, e]))
  const out: Pending[] = []
  for (const original of local) {
    const entry = byPath.get(original.path)
    if (!entry || entry.state === 'PURGED') continue
    const changed = entry.size !== original.size
    if (entry.state === 'EXPECTED' || changed) {
      out.push({ ...original, intakeId: entry.intakeId, state: entry.state })
    }
  }
  return out
}

/** The `Idempotency-Key` of an original's upload (design §5.1 step 3): one per entry and size. */
export function intakeKey(intakeId: string, size: number): string {
  return `imp-${intakeId}-${String(size)}`
}
