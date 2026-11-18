/** What the meeting page's address carries: the revision being read and the paper open in the viewer (flow P). */
export interface MeetingSearch {
  paper?: string
  revision?: number
}

/** `?paper=` and `?revision=` as the page reads them; anything else is dropped (the server checks the paper anyway). */
export function meetingSearch(search: Record<string, unknown>): MeetingSearch {
  const found: MeetingSearch = {}
  if (typeof search.paper === 'string' && /^[0-9A-Za-z-]{1,64}$/.test(search.paper)) found.paper = search.paper
  const revision = typeof search.revision === 'string' ? Number(search.revision) : search.revision
  if (typeof revision === 'number' && Number.isInteger(revision) && revision > 0) found.revision = revision
  return found
}
