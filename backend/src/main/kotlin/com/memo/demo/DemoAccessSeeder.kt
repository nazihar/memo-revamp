package com.memo.demo

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccounts
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import com.memo.memo.MemoTemplateSeeding
import com.memo.memo.NotePiece
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Phase 3 E03 demo data (design E03 §12): the auditor reads the Credit Division's memos as a unit reader; Credit facility
 * has a letterhead template; and one memo shows the note sheet (a mention and a correction), a share with a reason, and
 * copies to a distribution list sent at approval, one of them read. Fills gaps only, so it also runs on a tenant seeded
 * before E03. Needs the files service (scanner), like the demo memos.
 */
@Component
class DemoAccessSeeder(
    private val accounts: UserAccounts,
    private val clock: Clock,
    private val transactions: TenantTransactions,
    private val grants: AccessAdministration,
    private val memos: MemoSeeding,
    private val access: MemoAccessSeeding,
    private val templates: MemoTemplateSeeding,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun seed() {
        if (demoPassword.isBlank()) return
        val ids = ActorContext.asSystem("demo-access") { transactions.inTenant(DemoTenant.ID) { people() } }
        ActorContext.asSystem("demo-access") {
            transactions.inTenant(DemoTenant.ID) {
                grants.grantCustom(ids.getValue(AUDITOR), UNIT_READER, setOf(Permission.MEMO_READ_UNIT), DemoOrg.unitId("CRD"))
                // Phase 3 E04: the demo admin keeps final records (a new version with a reason, ADR-0053).
                grants.grantCustom(ids.getValue(ADMIN), RECORDS_KEEPER, setOf(Permission.MEMO_RENDITION_REGENERATE))
            }
        }
        letterhead(ids)
        sheet(ids)
        log.info("Demo notes, access and templates seeded")
    }

    /** Credit facility's letterhead: the memo's number, date, subject and initiator filled in at create. */
    private fun letterhead(ids: Map<String, UUID>) {
        val admin = ids.getValue(ADMIN)
        val type = asUser(admin) { memos.fixedType(DemoWorkflowSeeder.NAME, DemoWorkflowSeeder.KEY) }
        if (asUser(admin) { templates.hasTemplate(type) }) return
        val bytes =
            DemoDocx.of(
                "Demo Bank PLC: Credit facility",
                listOf(
                    "Reference: {{memo.number}}    Date: {{memo.date}}",
                    "Subject: {{memo.subject}}",
                    "From: {{initiator.name}}, {{initiator.position}}, {{initiator.unit}}",
                    "Classification: {{memo.classification}}",
                    "Background, the facility asked for, security offered and the recommendation follow.",
                ),
            )
        TenantContext.call(DemoTenant.ID) { templates.publish(type, "credit-letterhead.docx", bytes, admin, ids.getValue(REVIEWER)) }
    }

    /** The memo whose note sheet, shares and copies the demo shows. */
    private fun sheet(ids: Map<String, UUID>) {
        val author = ids.getValue(AUTHOR)
        if (asUser(author) { access.find(author, SUBJECT) } != null) return
        val credit = ids.getValue(HEAD_CREDIT)
        val list = asUser(ids.getValue(ADMIN)) { access.list(LIST, listOf(ids.getValue(HEAD_FINANCE), ids.getValue(AUDITOR))) }
        val memo =
            asUser(author) {
                val id = memos.draft(SUBJECT)
                memos.document(
                    id,
                    "credit-line-renewal.docx",
                    DemoDocx.of(SUBJECT, listOf("Renewal of a BDT 40 million working capital line.")),
                )
                memos.route(id, listOf(credit, ids.getValue(DIRECTOR)))
                access.copies(id, list, emptyList())
                memos.submit(id, "For your approval.")
                id
            }
        asUser(credit) {
            access.note(
                memo,
                listOf(
                    NotePiece.Text("Please confirm the collateral valuation, "),
                    NotePiece.Mention(author, "Demo User"),
                    NotePiece.Text("."),
                ),
            )
        }
        asUser(author) {
            val first = access.note(memo, listOf(NotePiece.Text("The valuation is on page 3 of the document.")))
            access.note(memo, listOf(NotePiece.Text("The valuation is on page 4 of the document, not page 3.")), corrects = first)
            access.share(
                memo,
                ids.getValue(BRANCH_ADMIN),
                "Check of the branch premises offered as security",
                clock.instant().plus(SHARE_FOR),
            )
        }
        asUser(credit) { memos.act(memo, MemoSeeding.APPROVE, "Valuation confirmed.") }
        asUser(ids.getValue(DIRECTOR)) { memos.act(memo, MemoSeeding.APPROVE, "Approved.") }
        // The copies went to the list at approval; the head of finance has read theirs, the auditor not yet.
        asUser(ids.getValue(HEAD_FINANCE)) { access.open(memo) }
    }

    private fun people(): Map<String, UUID> =
        listOf(AUTHOR, HEAD_CREDIT, HEAD_FINANCE, DIRECTOR, ADMIN, REVIEWER, AUDITOR, BRANCH_ADMIN).associateWith {
            checkNotNull(accounts.findByEmail(it)) { "demo user $it missing" }.id.value
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        const val SUBJECT = "Credit line renewal for Meghna Agro"
        const val LIST = "Credit committee"
        const val UNIT_READER = "Unit reader"
        const val RECORDS_KEEPER = "Records keeper"
        const val AUTHOR = "user@demo.example"
        const val HEAD_CREDIT = "head.credit@demo.example"
        const val HEAD_FINANCE = "head.finance@demo.example"
        const val DIRECTOR = "director@demo.example"
        const val ADMIN = "admin@demo.example"
        const val REVIEWER = "orgadmin@demo.example"
        const val AUDITOR = "auditor@demo.example"
        const val BRANCH_ADMIN = "branchadmin@demo.example"
        private val SHARE_FOR = Duration.ofDays(30)
    }
}
