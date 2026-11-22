/**
 * The committee page's tabs; `?tab=meetings` opens the meetings, `?tab=submissions` the papers (notifications, a paper's
 * page). The tab chosen goes in the address too, so a reload, Back and a change of language keep it.
 */
export const FORUM_TABS = ['members', 'meetings', 'submissions', 'resolutions', 'history', 'policy'] as const
export type ForumTab = (typeof FORUM_TABS)[number]
