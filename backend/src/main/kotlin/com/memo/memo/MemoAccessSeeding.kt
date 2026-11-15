package com.memo.memo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.memo.internal.CopyRecipient
import com.memo.memo.internal.DistributionLists
import com.memo.memo.internal.ListMember
import com.memo.memo.internal.MemberKind
import com.memo.memo.internal.MemoCopies
import com.memo.memo.internal.MemoListStore
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoShares
import com.memo.memo.internal.MemoTemplatePurpose
import com.memo.memo.internal.MemoTemplateService
import com.memo.memo.internal.MemoTemplateStore
import com.memo.memo.internal.MemoTypeLifecycle
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.Memos
import com.memo.memo.internal.RecipientKind
import com.memo.memo.internal.SendWhen
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** A piece of a seeded note: plain text, or a mention of a person who reads the memo. */
sealed interface NotePiece {
    data class Text(
        val text: String,
    ) : NotePiece

    data class Mention(
        val id: UUID,
        val label: String,
    ) : NotePiece
}

/**
 * Notes, shares, copies, receipts and distribution lists for seeders (the demo tenant, Phase 3 E03 T9), through the same
 * services as the API, as the user bound to the current unit of work. Never called from requests. Each call is its own
 * tenant transaction.
 */
@Service
class MemoAccessSeeding(
    private val memos: Memos,
    private val notes: MemoNotes,
    private val shares: MemoShares,
    private val copies: MemoCopies,
    private val lists: DistributionLists,
    private val files: Files,
    private val memoLists: MemoListStore,
    private val transactions: TenantTransactions,
) {
    private val json = JsonMapper.builder().build()

    /** [initiator]'s memo with exactly [subject], if any (seeders fill gaps). */
    fun find(
        initiator: UUID,
        subject: String,
    ): UUID? = tx { memoLists.bySubject(initiator, subject) }

    /** Whether [memo] has any note yet (seeders fill gaps only). */
    fun hasNotes(memo: UUID): Boolean = tx { notes.sheet(memo).notes.isNotEmpty() }

    /** A comment on [memo] of one paragraph, or a correction of [corrects]; returns the note's id. */
    fun note(
        memo: UUID,
        pieces: List<NotePiece>,
        corrects: UUID? = null,
    ): UUID = tx { notes.post(memo, doc(pieces), corrects, emptyList()).id }

    /** Shares [memo] with [user] for [reason], until [until] when set. */
    fun share(
        memo: UUID,
        user: UUID,
        reason: String,
        until: Instant? = null,
    ) {
        tx { shares.grant(memo, user, reason, until) }
    }

    /** The active list called [name], created with [members] (people) when there is none; returns its id. */
    fun list(
        name: String,
        members: List<UUID>,
    ): UUID =
        tx {
            lists.all().firstOrNull { it.name == name }?.id
                ?: lists.create(name, members.map { ListMember(MemberKind.USER, it) }).id
        }

    /** Replaces the unsent copies of the draft [memo] with [listId] and [people], all sent after approval. */
    fun copies(
        memo: UUID,
        listId: UUID,
        people: List<UUID>,
    ) {
        tx {
            val recipients =
                listOf(CopyRecipient(RecipientKind.LIST, listId)) + people.map { CopyRecipient(RecipientKind.USER, it) }
            copies.replace(memo, memos.get(memo).version, recipients)
        }
    }

    /** Records that the current user opened [memo] (their receipt, when they read it as a copy). */
    fun open(memo: UUID) {
        tx {
            val read = memos.get(memo)
            val document =
                read.documentFileId
                    ?.let { files.info(it) }
                    ?.currentVersionId
                    ?.let { files.version(it) }
            copies.recordOpen(read, document?.number)
        }
    }

    private fun doc(pieces: List<NotePiece>): JsonNode {
        val content =
            pieces.map {
                when (it) {
                    is NotePiece.Text -> mapOf("type" to "text", "text" to it.text)
                    is NotePiece.Mention -> mapOf("type" to "mention", "attrs" to mapOf("id" to it.id.toString(), "label" to it.label))
                }
            }
        return json.valueToTree(mapOf("type" to "doc", "content" to listOf(mapOf("type" to "paragraph", "content" to content))))
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

/**
 * A memo type's template for seeders (ADR-0048): a new draft version of the published one, the template uploaded, previewed,
 * submitted by the maker and approved by the checker, through the reviewed lifecycle.
 */
@Service
class MemoTemplateSeeding(
    private val types: MemoTypes,
    private val lifecycle: MemoTypeLifecycle,
    private val templates: MemoTemplateService,
    private val store: MemoTemplateStore,
    private val uploads: FileUploads,
    private val transactions: TenantTransactions,
) {
    /** Whether the published version of [typeId] has a template. */
    fun hasTemplate(typeId: UUID): Boolean = tx { types.published(typeId)?.let { store.of(it.id)?.present } == true }

    /** Publishes a version of [typeId] with the template [bytes], written as [author] and approved as [reviewer]. */
    fun publish(
        typeId: UUID,
        name: String,
        bytes: ByteArray,
        author: UUID,
        reviewer: UUID,
    ) {
        val draft = asUser(author) { tx { lifecycle.newDraft(typeId, null) } }
        asUser(author) {
            uploads.upload(MemoTemplatePurpose.KEY, draft.id.toString(), name, bytes, UUID.randomUUID().toString())
            templates.preview(typeId, draft.number)
            tx {
                val current = lifecycle.get(typeId, draft.number)
                lifecycle.submit(typeId, current.number, current.revision)
            }
        }
        asUser(reviewer) { tx { lifecycle.approve(typeId, draft.number, null) } }
    }

    private fun <T> tx(block: () -> T): T = transactions.inTenant(TenantContext.current(), block)
}

private fun <T> asUser(
    user: UUID,
    block: () -> T,
): T = ActorContext.call(ActorScope(Actor.User(user), null), block)
