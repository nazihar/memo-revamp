import { useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import { useRef, useState } from 'react'
import { useIntl } from 'react-intl'
import { listSavedSearchesOptions, listSavedSearchesQueryKey } from '@/api/generated/@tanstack/react-query.gen'
import { deleteSavedSearch, replaceSavedSearch } from '@/api/generated/sdk.gen'
import type { SavedSearchView } from '@/api/generated/types.gen'
import { PageHeader } from '@/components/page/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '@/components/page/States'
import { Button } from '@/components/ui/button'
import { AlertDialog } from '@/components/ui/dialog'
import { useToast } from '@/components/ui/toast-context'
import { NameDialog } from './SaveSearchDialog'
import { sm, smv } from './search-messages'
import { filterCount, locationOf } from './search-params'
import { saveProblem } from './search-problems'

/** `/search/saved` (flow H3): the person's own saved searches; open, rename or delete each. */
export function SavedSearchesPage() {
  const intl = useIntl()
  const saved = useQuery(listSavedSearchesOptions())
  const [renaming, setRenaming] = useState<SavedSearchView>()
  const [deleting, setDeleting] = useState<SavedSearchView>()
  const items = saved.data ?? []

  return (
    <section aria-labelledby="saved-searches-title">
      <PageHeader
        title={<span id="saved-searches-title">{intl.formatMessage(sm.savedTitle)}</span>}
        description={intl.formatMessage(sm.savedLead)}
        actions={
          <Button variant="outline" asChild>
            <Link to="/search">
              <Search aria-hidden="true" />
              {intl.formatMessage(sm.title)}
            </Link>
          </Button>
        }
      />
      {saved.isPending ? (
        <LoadingState />
      ) : saved.isError ? (
        <ErrorState onRetry={() => void saved.refetch()} />
      ) : items.length === 0 ? (
        <EmptyState title={intl.formatMessage(sm.savedEmpty)} />
      ) : (
        <ul className="flex flex-col divide-y rounded-md border">
          {items.map((s) => {
            const target = locationOf(s.criteria)
            const filters = filterCount(target.search)
            const summary = [
              s.criteria.q ? intl.formatMessage(smv.words, { q: s.criteria.q }) : undefined,
              filters > 0 ? intl.formatMessage(smv.filterCount, { count: filters }) : undefined,
            ]
            return (
              <li key={s.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3">
                <div className="flex min-w-0 flex-[1_1_16rem] flex-col">
                  <Link
                    to="/search"
                    search={target.search}
                    hash={target.hash}
                    className="font-medium text-primary underline-offset-4 hover:underline"
                  >
                    {s.name}
                  </Link>
                  <span className="text-xs text-muted-foreground">{summary.filter(Boolean).join(' · ')}</span>
                </div>
                <span className="flex gap-1">
                  <Button
                    variant="ghost"
                    aria-label={intl.formatMessage(smv.renameFor, { name: s.name })}
                    onClick={() => {
                      setRenaming(s)
                    }}
                  >
                    {intl.formatMessage(sm.rename)}
                  </Button>
                  <Button
                    variant="ghost"
                    aria-label={intl.formatMessage(smv.deleteFor, { name: s.name })}
                    onClick={() => {
                      setDeleting(s)
                    }}
                  >
                    {intl.formatMessage(sm.delete)}
                  </Button>
                </span>
              </li>
            )
          })}
        </ul>
      )}
      {renaming ? (
        <RenameDialog
          saved={renaming}
          onClose={() => {
            setRenaming(undefined)
          }}
        />
      ) : null}
      {deleting ? (
        <DeleteDialog
          saved={deleting}
          onClose={() => {
            setDeleting(undefined)
          }}
        />
      ) : null}
    </section>
  )
}

function RenameDialog({ saved, onClose }: { saved: SavedSearchView; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  return (
    <NameDialog
      title={intl.formatMessage(sm.renameTitle)}
      initial={saved.name}
      submitLabel={intl.formatMessage(sm.save)}
      onSave={async (name) => {
        await replaceSavedSearch({
          path: { id: saved.id },
          headers: { 'If-Match': `"${String(saved.version)}"` },
          body: { name, criteria: saved.criteria },
          throwOnError: true,
        })
        toast.show(intl.formatMessage(sm.renamed))
        await queryClient.invalidateQueries({ queryKey: listSavedSearchesQueryKey() })
      }}
      onClose={onClose}
    />
  )
}

function DeleteDialog({ saved, onClose }: { saved: SavedSearchView; onClose: () => void }) {
  const intl = useIntl()
  const toast = useToast()
  const queryClient = useQueryClient()
  const confirmed = useRef(false)
  return (
    <AlertDialog
      open
      onOpenChange={(open) => {
        if (!open && !confirmed.current) onClose()
      }}
      title={intl.formatMessage(smv.deleteTitle, { name: saved.name })}
      description={intl.formatMessage(sm.deleteLead)}
      confirmLabel={intl.formatMessage(sm.delete)}
      cancelLabel={intl.formatMessage(sm.keep)}
      destructive
      onConfirm={() => {
        confirmed.current = true
        deleteSavedSearch({
          path: { id: saved.id },
          headers: { 'If-Match': `"${String(saved.version)}"` },
          throwOnError: true,
        })
          .then(async () => {
            toast.show(intl.formatMessage(sm.deleted))
            await queryClient.invalidateQueries({ queryKey: listSavedSearchesQueryKey() })
          })
          .catch((error: unknown) => {
            toast.show(intl.formatMessage(saveProblem(error)), 'error')
          })
          .finally(onClose)
      }}
    />
  )
}
