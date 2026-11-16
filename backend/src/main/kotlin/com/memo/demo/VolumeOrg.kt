package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.identity.SecuritySettings
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The settings and organisation of synthetic volume V2 (Phase 3 E05 T8): password sign-in only, and one division whose
 * head oversees every approver and initiator, so the team view, the unit dashboard and a unit-wide search each meet all
 * of the volume's tasks and memos. The head reads the division's memos through a unit-scoped grant, as a real head of
 * a division would. Inside the volume tenant's transaction.
 */
@Component
class VolumeOrg(
    private val settings: TenantSettings,
    private val org: OrgSeeding,
    private val grants: AccessAdministration,
    private val clock: Clock,
) {
    fun prepare(
        head: UUID,
        staff: List<UUID>,
    ) {
        // Password sign-in only, so the load test needs no authenticator.
        settings.put(SecuritySettings.MFA_REQUIRED, SecuritySettings.MFA_ADMINS_ONLY)
        val from = LocalDate.now(clock).minusYears(1)
        org.legalEntity(ENTITY, "Synthetic Bank", from)
        org.unit(UNIT, ENTITY, null, "VOL", "Volume Division", from)
        org.position(HEAD_POSITION, UNIT, "Head of Volume Division", head = true, from)
        org.assignment(HEAD_POSITION, head, from)
        staff.forEachIndexed { index, user ->
            val position = id("position:$index")
            org.position(position, UNIT, "Officer ${index + 1}", head = false, from)
            org.assignment(position, user, from)
            org.reportingLine(position, HEAD_POSITION, from)
        }
        grants.grantCustom(head, "Volume division reader", setOf(Permission.MEMO_READ_UNIT), UNIT)
    }

    private companion object {
        private fun id(name: String): UUID = UUID.nameUUIDFromBytes("volume-v2:$name".toByteArray())

        val ENTITY = id("legal-entity")
        val UNIT = id("unit")
        val HEAD_POSITION = id("position:head")
    }
}
