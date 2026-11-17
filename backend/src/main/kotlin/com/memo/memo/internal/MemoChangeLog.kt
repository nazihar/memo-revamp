package com.memo.memo.internal

import com.memo.common.tenant.TenantDsl
import com.memo.memo.MemoChanged
import com.memo.memo.MemoFileChanged
import com.memo.memo.MemoNotePosted
import com.memo.memo.MemoSearchEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Collects what the search index follows (memos, posted notes, memo files) as the memo module writes it, and publishes
 * one [MemoSearchEvent] per item just before the transaction commits (ADR-0054, design E05 §3). Listeners then read the
 * final state once and write the index in the same transaction. Outside a transaction it publishes at once.
 */
@Component
class MemoChangeLog(
    private val events: ApplicationEventPublisher,
    private val tenantDsl: TenantDsl,
) {
    fun memo(memoId: UUID) = record(MemoChanged(tenantDsl.tenant().value, memoId))

    fun note(
        memoId: UUID,
        noteId: UUID,
    ) = record(MemoNotePosted(tenantDsl.tenant().value, memoId, noteId))

    fun file(
        memoId: UUID,
        fileId: UUID,
    ) = record(MemoFileChanged(tenantDsl.tenant().value, memoId, fileId))

    private fun record(event: MemoSearchEvent) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            events.publishEvent(event)
            return
        }
        @Suppress("UNCHECKED_CAST")
        val pending =
            TransactionSynchronizationManager.getResource(this) as? LinkedHashSet<MemoSearchEvent>
                ?: LinkedHashSet<MemoSearchEvent>().also { pending ->
                    TransactionSynchronizationManager.bindResource(this, pending)
                    TransactionSynchronizationManager.registerSynchronization(Flush(this, pending, events))
                }
        pending += event
    }

    /** Publishes memos first, then notes and files (a note's row copies its memo's), and unbinds at the end. */
    private class Flush(
        private val key: Any,
        private val pending: LinkedHashSet<MemoSearchEvent>,
        private val events: ApplicationEventPublisher,
    ) : TransactionSynchronization {
        override fun beforeCommit(readOnly: Boolean) {
            pending.sortedBy { if (it is MemoChanged) 0 else 1 }.forEach(events::publishEvent)
            pending.clear()
        }

        override fun afterCompletion(status: Int) {
            TransactionSynchronizationManager.unbindResourceIfPossible(key)
        }
    }
}
