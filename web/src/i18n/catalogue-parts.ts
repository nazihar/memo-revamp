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
  | 'platform'
  | 'patterns'
  | 'search'
  | 'inbox'
  | 'dashboard'
  | 'imports'
  | 'legacy'

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
