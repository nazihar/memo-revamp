package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
import com.memo.forum.ForumSeeding
import com.memo.forum.SeedMember
import com.memo.forum.SeedPreset
import com.memo.identity.UserAccounts
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Representative bank committees in the demo tenant (Phase 4 E01 T6; no pilot bank, Phase 4 A1): three committee types
 * from the presets and six committees, with the company secretary as secretary of each. Board members are a demo
 * director with sign-in, the chairman's seat and the managing director; management committees are held by position
 * where a seat exists; Demo Director's risk committee seat has Demo Head of Credit as its alternate (Phase 4 E05 T10).
 * Seeded as the demo administrator; existing committees and members are left as they are (an alternate is only added).
 */
@Component
class DemoForumSeeder(
    private val forums: ForumSeeding,
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
) {
    fun seed() {
        val admin = ActorContext.asSystem("demo-forums") { transactions.inTenant(DemoTenant.ID) { person(ADMIN) } }
        asUser(admin) {
            transactions.inTenant(DemoTenant.ID) {
                val board = forums.ensureType("BOARD", "Board", SeedPreset.BOARD)
                val boardCommittee = forums.ensureType("BC", "Board committee", SeedPreset.BOARD_COMMITTEE)
                val management = forums.ensureType("MC", "Management committee", SeedPreset.MANAGEMENT_COMMITTEE)
                forums.ensureForum(BOARD, "BOD", "Board of Directors", "Board", board)
                forums.ensureForum(RISK, "BRC", "Board Risk Committee", "Risk", boardCommittee, BOARD)
                forums.ensureForum(AUDIT, "BAC", "Board Audit Committee", "Audit", boardCommittee, BOARD)
                forums.ensureForum(CREDIT, "MCC", "Management Credit Committee", "Credit", management)
                forums.ensureForum(ALCO, "ALCO", "Asset-Liability Committee", "ALCO", management)
                forums.ensureForum(EXCO, "EXCO", "Executive Committee", "ExCo", management)
                members().forEach { (key, member) -> forums.ensureMember(key, member) }
                // Phase 4 E05 T10: Demo Director's seat on the risk committee has an alternate, who stands in when they
                // send apologies (the demo risk review and the journey meeting-replies.spec).
                forums.ensureAlternate(RISK, person(DIRECTOR), person(HEAD_CREDIT))
            }
        }
    }

    private fun members(): List<Pair<String, SeedMember>> {
        val secretary = person(SECRETARY)
        val boardMember = person(BOARD_MEMBER)
        val director = person(DIRECTOR)
        val credit = person(HEAD_CREDIT)
        val finance = person(HEAD_FINANCE)
        val chairman = seat("BOD")
        val cro = seat("RSK")
        val cio = seat("IT")
        val audit = seat("AUD")
        val independent = setOf("independent", "nonExecutive")

        fun p(
            userId: UUID,
            role: ForumRole = ForumRole.MEMBER,
            attributes: Set<String> = emptySet(),
        ) = SeedMember(userId = userId, role = role, attributes = attributes, startsOn = START)

        fun s(
            positionId: UUID,
            role: ForumRole = ForumRole.MEMBER,
            attributes: Set<String> = emptySet(),
        ) = SeedMember(positionId = positionId, role = role, attributes = attributes, startsOn = START)
        val management = setOf("management")
        return listOf(
            BOARD to s(chairman, ForumRole.CHAIR, independent),
            BOARD to p(boardMember, ForumRole.MEMBER, independent),
            BOARD to p(director),
            BOARD to p(secretary, ForumRole.SECRETARY),
            RISK to p(boardMember, ForumRole.CHAIR, independent),
            RISK to s(chairman, ForumRole.MEMBER, independent),
            RISK to p(director),
            RISK to s(cro, ForumRole.STANDING_INVITEE),
            RISK to p(secretary, ForumRole.SECRETARY),
            AUDIT to p(boardMember, ForumRole.CHAIR, independent),
            AUDIT to s(chairman, ForumRole.MEMBER, independent),
            AUDIT to s(audit, ForumRole.STANDING_INVITEE),
            AUDIT to p(secretary, ForumRole.SECRETARY),
            CREDIT to p(director, ForumRole.CHAIR, management),
            CREDIT to p(credit, ForumRole.VICE_CHAIR, management),
            CREDIT to p(finance, ForumRole.MEMBER, management),
            CREDIT to s(cro, ForumRole.MEMBER, management),
            CREDIT to p(secretary, ForumRole.SECRETARY),
            ALCO to p(director, ForumRole.CHAIR, management),
            ALCO to p(finance, ForumRole.VICE_CHAIR, management),
            ALCO to p(credit, ForumRole.MEMBER, management),
            ALCO to s(cro, ForumRole.MEMBER, management),
            ALCO to p(secretary, ForumRole.SECRETARY),
            EXCO to p(director, ForumRole.CHAIR, management),
            EXCO to p(finance, ForumRole.MEMBER, management),
            EXCO to p(credit, ForumRole.MEMBER, management),
            EXCO to s(cro, ForumRole.MEMBER, management),
            EXCO to s(cio, ForumRole.MEMBER, management),
            EXCO to p(secretary, ForumRole.SECRETARY),
        )
    }

    private fun person(email: String): UUID = checkNotNull(accounts.findByEmail(email)) { "demo user $email missing" }.id.value

    private fun seat(unit: String): UUID = DemoOrg.unitByCode(unit).headPosition

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        const val BOARD = "board"
        const val RISK = "board-risk"
        const val AUDIT = "board-audit"
        const val CREDIT = "credit-committee"
        const val ALCO = "alco"
        const val EXCO = "exco"
        const val ADMIN = "admin@demo.example"
        const val SECRETARY = "secretary@demo.example"
        const val BOARD_MEMBER = "board.member@demo.example"
        const val DIRECTOR = "director@demo.example"
        const val HEAD_CREDIT = "head.credit@demo.example"
        const val HEAD_FINANCE = "head.finance@demo.example"
        val START: LocalDate = LocalDate.of(2026, 1, 1)
    }
}

/** After the demo tenant and its users; committees need nothing else, so they are seeded at start-up. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty("memo.demo.seed", havingValue = "true")
class DemoForumSeedOnStartup(
    private val seeder: DemoForumSeeder,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A demo that cannot be seeded never stops the app from starting. */
    @Suppress("TooGenericExceptionCaught")
    override fun run(args: ApplicationArguments) {
        try {
            seeder.seed()
        } catch (e: RuntimeException) {
            log.warn("Demo committees not seeded", e)
        }
    }
}
