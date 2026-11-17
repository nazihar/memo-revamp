package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A person a copy reached or would reach, and through which position or list (null: named directly). */
data class CopyReach(
    val userId: UUID,
    val via: UUID?,
)

/**
 * Copy-to (ADR-0050, design E03 §5). The initiator or a co-author keeps the list while the memo is a draft or returned;
 * after approval, holders of `memo.copy.add` may add to it. Copies go at submit or at final approval, in that
 * transaction: each is resolved to people (a position's holder, a list's members), each person gets one delivery, the
 * cleared and active ones become `COPY` participants and are notified once, however many copies reach them. Nothing is
 * removed once sent. Inside a tenant transaction.
 */
@Service
class MemoCopies(
    private val memos: Memos,
    private val store: MemoStore,
    private val copies: MemoCopyStore,
    private val recipients: CopyRecipients,
    private val shares: MemoShareStore,
    private val coauthors: MemoCoauthorStore,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val policy: Policy,
    private val notifier: MemoNotifier,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** The copies of [memoId] that stand, for its readers. */
    fun list(memoId: UUID): List<MemoCopy> = copies.standing(memos.get(memoId).id)

    /** Whether the actor may change [memo]'s copy list now (initiator or co-author of a draft or returned memo). */
    fun mayEdit(memo: Memo): Boolean {
        val actor = signedInOrNull() ?: return false
        return memo.status.editable && (memo.initiatorId == actor || coauthors.isCoauthor(memo.id, actor))
    }

    /** Whether the actor may add copies to [memo] after its approval. */
    fun mayAdd(memo: Memo): Boolean = memo.status in AFTER_APPROVAL_STATES && policy.allows(Permission.MEMO_COPY_ADD)

    /**
     * Replaces the copies not yet sent with [recipients]; [version] is the memo's `If-Match`. Sent copies stay; a
     * recipient already copied keeps its copy, with the new timing if it is not sent yet.
     */
    fun replace(
        memoId: UUID,
        version: Long,
        recipients: List<CopyRecipient>,
    ): List<MemoCopy> {
        val memo = locked(memoId)
        if (!mayEdit(memo)) throw ForbiddenException(null)
        if (memo.version != version) throw ConcurrentModificationException("memo $memoId", version)
        this.recipients.check(recipients)
        val actor = signedIn()
        val now = now()
        val standing = copies.standing(memo.id)
        val wanted = recipients.distinctBy { it.kind to it.id }
        val stale =
            standing.filter { c ->
                c.sentAt == null &&
                    wanted.none { it.kind == c.kind && it.id == c.recipientId && it.sendWhen == c.sendWhen }
            }
        stale.forEach { copies.remove(it.id, actor, now) }
        val kept = standing - stale.toSet()
        val added = wanted.filter { w -> kept.none { it.kind == w.kind && it.recipientId == w.id } }
        added.forEach { copies.insert(memo.id, it, actor, now) }
        if (stale.isNotEmpty() || added.isNotEmpty()) {
            copies.touchMemo(memo.id, now)
            audit.record(
                "memo.copy.changed",
                Memos.OBJECT_TYPE,
                memo.id.toString(),
                after =
                    mapOf(
                        "added" to added.size,
                        "removed" to stale.size,
                    ),
            )
        }
        return copies.standing(memo.id)
    }

    /** Adds copies to an approved memo and sends them at once (`memo.copy.add`, for example a registry clerk). */
    fun addAfterApproval(
        memoId: UUID,
        recipients: List<CopyRecipient>,
    ): List<MemoCopy> {
        val memo = memos.get(memoId)
        store.lock(memo.id)
        refuseIf(memo.status !in AFTER_APPROVAL_STATES, "WRONG_STATE") { "Copies are added this way only once the memo is approved." }
        if (!policy.allows(Permission.MEMO_COPY_ADD)) throw ForbiddenException(Permission.MEMO_COPY_ADD)
        this.recipients.check(recipients)
        val actor = signedIn()
        val now = now()
        val standing = copies.standing(memo.id)
        val added = recipients.distinctBy { it.kind to it.id }.filter { r -> standing.none { it.kind == r.kind && it.recipientId == r.id } }
        added.forEach { copies.insert(memo.id, it.copy(sendWhen = SendWhen.AFTER_APPROVAL), actor, now) }
        audit.record("memo.copy.added_after_approval", Memos.OBJECT_TYPE, memo.id.toString(), after = mapOf("added" to added.size))
        send(memo, SendWhen.AFTER_APPROVAL)
        return copies.standing(memo.id)
    }

    /** At submit: the people "at submit" copies would reach who are not cleared for the memo, as one submit problem. */
    fun problemsAtSubmit(memo: Memo): List<FieldProblem> {
        val reach = copies.standing(memo.id).filter { it.sentAt == null && it.sendWhen == SendWhen.AT_SUBMIT }.flatMap(recipients::resolve)
        val people = reach.map { it.userId }.toSet()
        val ranks = clearances.rankOf(people)
        val refused = people.filter { (ranks[it] ?: 0) < memo.classificationRank }
        if (refused.isEmpty()) return emptyList()
        val names =
            users
                .of(refused)
                .values
                .map { it.displayName }
                .sorted()
        return listOf(FieldProblem("copies", "NOT_CLEARED", "Not cleared for this memo's classification: ${names.joinToString()}."))
    }

    /** Sends [memo]'s unsent copies due [moment]; a re-run sends nothing twice. */
    fun send(
        memo: Memo,
        moment: SendWhen,
    ) {
        val due = copies.standing(memo.id).filter { it.sentAt == null && it.sendWhen == moment }
        if (due.isEmpty()) return
        val now = now()
        val reach = due.associateWith(recipients::resolve)
        val everyone =
            reach.values
                .flatten()
                .map { it.userId }
                .toSet()
        val people = users.of(everyone)
        val ranks = clearances.rankOf(everyone)
        val readers = everyone.filter { shares.activeKind(memo.id, it, now) != null }.toMutableSet()
        var sent = 0
        val refused = mutableSetOf<UUID>()
        due.forEach { copy ->
            reach.getValue(copy).map { it.userId }.distinct().forEach { user ->
                val outcome =
                    when {
                        people[user]?.status != UserStatus.ACTIVE -> DeliveryOutcome.INACTIVE
                        (ranks[user] ?: 0) < memo.classificationRank -> DeliveryOutcome.NOT_CLEARED
                        else -> DeliveryOutcome.SENT
                    }
                copies.deliver(copy, user, outcome, now)
                if (outcome == DeliveryOutcome.SENT) {
                    store.addParticipant(memo.id, user, ParticipantKind.COPY, now, grantedBy = copy.addedBy)
                    if (readers.add(user)) notifier.tell(user, NotificationKind.MEMO_COPIED, memo.id)
                    sent++
                } else {
                    refused += user
                }
            }
            copies.markSent(copy.id, now)
        }
        audit.record(
            "memo.copy.sent",
            Memos.OBJECT_TYPE,
            memo.id.toString(),
            after = mapOf("copies" to due.size, "sent" to sent, "refused" to refused.map(UUID::toString).sorted(), "moment" to moment.name),
        )
    }

    /** Records the first time a copy recipient or an approver opens [memo], with the document version it showed. */
    fun recordOpen(
        memo: Memo,
        documentVersion: Int?,
    ) {
        val user = signedInOrNull() ?: return
        val now = now()
        if (shares.activeKind(memo.id, user, now) in RECEIPTS_FOR) copies.receipt(memo.id, user, documentVersion, now)
    }

    private fun locked(memoId: UUID): Memo {
        memos.get(memoId)
        return store.lock(memoId) ?: throw MemoNotFoundException()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val MAX_RECIPIENTS = 100
        val AFTER_APPROVAL_STATES = setOf(MemoStatus.APPROVED, MemoStatus.CLOSED)

        /** Receipts are kept for those who receive the memo to read or to decide (design E03 §5). */
        private val RECEIPTS_FOR = setOf(ParticipantKind.COPY, ParticipantKind.APPROVER)
    }
}

private fun signedInOrNull(): UUID? = (ActorContext.currentOrNull()?.actor as? Actor.User)?.userId

private fun signedIn(): UUID = checkNotNull(signedInOrNull()) { "copies need a signed-in user" }

/** Resolves copies to people and checks that the recipients a client names exist. Inside a tenant transaction. */
@org.springframework.stereotype.Component
class CopyRecipients(
    private val lists: DistributionListStore,
    private val directory: OrgDirectory,
    private val users: UserSummaries,
) {
    /** Who [copy] reaches today: the person, the position's holder, or the list's people and position holders. */
    fun resolve(copy: MemoCopy): List<CopyReach> =
        when (copy.kind) {
            RecipientKind.USER -> {
                listOf(CopyReach(copy.recipientId, null))
            }

            RecipientKind.POSITION -> {
                directory.holders(listOf(copy.recipientId)).values.map { CopyReach(it, copy.recipientId) }
            }

            RecipientKind.LIST -> {
                val members = lists.members(copy.recipientId)
                val holders = directory.holders(members.filter { it.kind == MemberKind.POSITION }.map { it.id }).values
                (members.filter { it.kind == MemberKind.USER }.map { it.id } + holders).distinct().map { CopyReach(it, copy.recipientId) }
            }
        }

    /** Refuses recipients that do not exist (people must be active, lists active), and more than [MemoCopies.MAX_RECIPIENTS]. */
    fun check(recipients: List<CopyRecipient>) {
        refuseIf(recipients.size > MemoCopies.MAX_RECIPIENTS, "TOO_MANY") { "At most $MemoCopies.MAX_RECIPIENTS recipients." }
        val people = users.of(recipients.filter { it.kind == RecipientKind.USER }.map { it.id })
        val positions = directory.positions(recipients.filter { it.kind == RecipientKind.POSITION }.map { it.id })
        val active = lists.activeIds(recipients.filter { it.kind == RecipientKind.LIST }.map { it.id })
        val problems =
            recipients.mapIndexedNotNull { i, r ->
                val known =
                    when (r.kind) {
                        RecipientKind.USER -> people[r.id]?.status == UserStatus.ACTIVE
                        RecipientKind.POSITION -> r.id in positions
                        RecipientKind.LIST -> r.id in active
                    }
                FieldProblem("copies[$i].id", "UNKNOWN", "Choose an active person, a position or a list.").takeIf { !known }
            }
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
    }
}
