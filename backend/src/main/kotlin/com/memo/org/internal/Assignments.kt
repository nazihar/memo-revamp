package com.memo.org.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.web.refuseIf
import com.memo.identity.UserDeactivated
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.org.OrgChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * Primary assignments of people to positions over date ranges (ADR-0026). At any date a position has at most one
 * active holder and a user at most one position: checked under a lock on the position row and on the user. A mistaken
 * record is cancelled, never deleted; every change is audited. Every method runs inside a tenant transaction.
 */
@Service
class Assignments(
    private val store: AssignmentStore,
    private val positions: PositionStore,
    private val users: UserSummaries,
    private val calendar: OrgCalendar,
    private val policy: Policy,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: UUID): Assignment = store.find(id) ?: throw OrgNotFoundException("assignment")

    fun assign(
        positionId: UUID,
        userId: UUID,
        period: Period,
    ): Assignment {
        val position = positions.lock(positionId) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        val user = users.of(userId) ?: throw OrgNotFoundException("user")
        refuseIf(user.status != UserStatus.ACTIVE && user.status != UserStatus.INVITED, "USER_INACTIVE") {
            "Only active or invited users can hold a position."
        }
        store.lockUser(userId)
        checkFits(position, userId, period, except = null)
        val created = store.insert(userId, positionId, period)
        audit.record("org.assignment.created", OBJECT_TYPE, created.id.toString(), after = created.audited())
        changed(created.id)
        return created
    }

    /** Ends or corrects the dates of an active assignment. */
    fun changeDates(
        id: UUID,
        expectedVersion: Long,
        period: Period,
    ): Assignment {
        val before = get(id)
        refuseIf(before.status != RecordStatus.ACTIVE, "CANCELLED") { "A cancelled assignment cannot change." }
        val position = positions.lock(before.positionId) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        store.lockUser(before.userId)
        checkFits(position, before.userId, period, except = id)
        return save(before, expectedVersion, period, RecordStatus.ACTIVE, "updated")
    }

    /** Marks an assignment recorded in error; it stays in the history. */
    fun cancel(
        id: UUID,
        expectedVersion: Long,
    ): Assignment {
        val before = get(id)
        refuseIf(before.status != RecordStatus.ACTIVE, "CANCELLED") { "The assignment is already cancelled." }
        val position = positions.find(before.positionId) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        return save(before, expectedVersion, before.period, RecordStatus.CANCELLED, "cancelled")
    }

    /**
     * A deactivated user leaves their positions (design E04 §2): an assignment that has started ends today, one that
     * has not is cancelled. Runs in the deactivating transaction, as part of the administrator's action.
     */
    @EventListener
    fun onUserDeactivated(event: UserDeactivated) {
        val today = calendar.today()
        store.lockUser(event.userId)
        store
            .ofUser(event.userId)
            .filter { it.status == RecordStatus.ACTIVE && (it.period.to == null || it.period.to.isAfter(today)) }
            .forEach {
                if (it.period.from.isBefore(today)) {
                    save(it, it.version, Period(it.period.from, today), RecordStatus.ACTIVE, "ended")
                } else {
                    save(it, it.version, it.period, RecordStatus.CANCELLED, "cancelled")
                }
            }
    }

    private fun checkFits(
        position: OrgPosition,
        userId: UUID,
        period: Period,
        except: UUID?,
    ) {
        refuseIf(!period.within(Period(position.validFrom, position.validTo)), "DATES") { "The dates must lie within the position's." }
        val active = { list: List<Assignment> -> list.filter { it.status == RecordStatus.ACTIVE && it.id != except } }
        refuseIf(active(store.ofPosition(position.id)).any { it.period.overlaps(period) }, "OVERLAP") {
            "Someone else holds this position in that period."
        }
        refuseIf(active(store.ofUser(userId)).any { it.period.overlaps(period) }, "USER_OVERLAP") {
            "This person holds another position in that period."
        }
    }

    private fun save(
        before: Assignment,
        expectedVersion: Long,
        period: Period,
        status: RecordStatus,
        action: String,
    ): Assignment {
        if (!store.update(
                before.id,
                expectedVersion,
                period,
                status,
            )
        ) {
            throw ConcurrentModificationException("assignment ${before.id}", expectedVersion)
        }
        val after = get(before.id)
        audit.record("org.assignment.$action", OBJECT_TYPE, before.id.toString(), before.audited(), after.audited())
        changed(before.id)
        return after
    }

    private fun changed(id: UUID) = events.publishEvent(OrgChanged(TenantContext.current().value, "assignment", id))

    private fun Assignment.audited() =
        mapOf(
            "userId" to userId.toString(),
            "positionId" to positionId.toString(),
            "validFrom" to period.from.toString(),
            "validTo" to period.to?.toString(),
            "status" to status.name,
        )

    companion object {
        const val OBJECT_TYPE = "org.assignment"

        fun holderOn(
            history: List<Assignment>,
            date: LocalDate,
        ) = history.firstOrNull { it.status == RecordStatus.ACTIVE && it.period.contains(date) }
    }
}
