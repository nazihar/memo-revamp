/** The meeting page's tabs, for the officers and the committee's chair or vice-chair. */
export const MEETING_TABS = ['agenda', 'attendance', 'decisions', 'replies', 'declarations'] as const
export type MeetingTab = (typeof MEETING_TABS)[number]

/**
 * What the meeting page's address carries: the revision being read and the paper open in the viewer (flow P), and the
 * tab chosen when it is not the one the page opens on, so a reload, Back and a change of language keep it.
 */
export interface MeetingSearch {
  paper?: string
  revision?: number
  tab?: MeetingTab
}

/** `?paper=`, `?revision=` and `?tab=` as the page reads them; anything else is dropped (the server checks the paper anyway). */
export function meetingSearch(search: Record<string, unknown>): MeetingSearch {
  const found: MeetingSearch = {}
  if (typeof search.paper === 'string' && /^[0-9A-Za-z-]{1,64}$/.test(search.paper)) found.paper = search.paper
  const revision = typeof search.revision === 'string' ? Number(search.revision) : search.revision
  if (typeof revision === 'number' && Number.isInteger(revision) && revision > 0) found.revision = revision
  const tab = MEETING_TABS.find((t) => t === search.tab)
  if (tab) found.tab = tab
  return found
}

/** [search] with the tab [tab], or with none (the tab the page opens on). */
export function withTab(search: MeetingSearch, tab: MeetingTab | undefined): MeetingSearch {
  const { paper, revision } = search
  return {
    ...(paper === undefined ? {} : { paper }),
    ...(revision === undefined ? {} : { revision }),
    ...(tab === undefined ? {} : { tab }),
  }
}

/** The tab part of [search], kept by the addresses that change the paper or the revision. */
export function keepTab(search: MeetingSearch): Pick<MeetingSearch, 'tab'> {
  return search.tab === undefined ? {} : { tab: search.tab }
}
