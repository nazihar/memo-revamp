import type { ReactNode } from 'react'
import { LoadingState } from '@/components/page/States'
import type { CataloguePart } from './catalogue-parts'
import { useCataloguePart } from './LocaleProvider'

/** Shows [children] once [part] of the catalogue has loaded, so a page never flashes in the source language. */
export function CatalogueGate({ part, children }: { part: CataloguePart; children: ReactNode }) {
  return useCataloguePart(part) ? children : <LoadingState />
}
