import definition from './catalogue-parts.json'

/**
 * A slice of a locale's message catalogue that loads with the routes that need it (catalogue-parts.json; the build
 * splits the catalogues with scripts/catalogue-parts.mjs).
 */
export type CataloguePart =
  | 'core'
  | 'admin'
  | 'builder'
  | 'memoAdmin'
  | 'forumAdmin'
  | 'forums'
  | 'papers'
  | 'meetings'
  | 'agendaBuilder'
  | 'meetingPage'
  | 'meetingReplies'
  | 'meetingAttendance'
  | 'declarations'
  | 'mySecurity'
  | 'notificationKinds'
  | 'platform'
  | 'patterns'
  | 'search'
  | 'inbox'
  | 'dashboard'
  | 'imports'
  | 'legacy'
  | 'decisions'
  | 'resolutions'
  | 'forumMinutes'

const routes = Object.entries(definition)
  .filter(([name]) => !name.startsWith('$'))
  .map(([name, part]) => [name as CataloguePart, (part as { routes: string[] }).routes] as const)

/**
 * Whether a page at [pathname] is [route] or below it. A route segment written `$` matches any one segment
 * (`/meetings/$/agenda` is every meeting's agenda page).
 */
function under(pathname: string, route: string): boolean {
  const want = route.split('/')
  const have = pathname.split('/')
  return want.length <= have.length && want.every((segment, i) => segment === '$' || segment === have[i])
}

/** Every part a page at [pathname] needs besides core (the workflow builder's pages need admin and builder). */
export function partsForPath(pathname: string): CataloguePart[] {
  return routes.filter(([, prefixes]) => prefixes.some((route) => under(pathname, route))).map(([name]) => name)
}

/** The first part a page at [pathname] needs besides core, if any. */
export function partForPath(pathname: string): CataloguePart {
  const found = routes.find(([, prefixes]) => prefixes.some((route) => under(pathname, route)))
  return found?.[0] ?? 'core'
}

/**
 * Every part: core plus each part catalogue-parts.json defines. Derived, not listed, so a new part cannot be left out
 * (a part missing from a hand-kept list kept its English pages on "Loading", found in E05 T7).
 */
export const ALL_PARTS: ReadonlySet<CataloguePart> = new Set<CataloguePart>([
  'core',
  ...Object.keys(definition)
    .filter((name) => !name.startsWith('$'))
    .map((name) => name as CataloguePart),
])

/** The parts catalogue-parts.json defines, as a type: the CataloguePart union must name exactly these and core. */
type DefinedPart = Exclude<keyof typeof definition, `$${string}`> | 'core'
// A compile error here means catalogue-parts.json and the CataloguePart union disagree.
export const partsMatchDefinition: [DefinedPart] extends [CataloguePart]
  ? [CataloguePart] extends [DefinedPart]
    ? true
    : never
  : never = true
