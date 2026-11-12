package com.memo.demo

import java.time.LocalDate
import java.util.UUID

/**
 * The demo tenant's org directory (design E04 §14): one bank, 33 units, a head position per unit plus officers,
 * reporting lines from every position to the head above it, and named staff in most positions. Defined as code with
 * fixed ids, so seeding is idempotent.
 */
object DemoOrg {
    val START: LocalDate = LocalDate.of(2026, 1, 1)
    val ENTITY: UUID = id("entity", "demo-bank")
    const val ENTITY_NAME = "Demo Bank PLC"

    data class DemoUnit(
        val code: String,
        val name: String,
        val parent: String?,
        val headTitle: String,
    ) {
        val id: UUID get() = unitId(code)
        val headPosition: UUID get() = id("head", code)
    }

    data class DemoPosition(
        val id: UUID,
        val unit: String,
        val title: String,
        val head: Boolean,
    )

    val UNITS =
        listOf(
            DemoUnit("BOD", "Board of Directors", null, "Chairman"),
            DemoUnit("SEC", "Company Secretariat", "BOD", "Company Secretary"),
            DemoUnit("AUD", "Internal Audit", "BOD", "Head of Internal Audit"),
            DemoUnit("CEO", "Office of the Managing Director", "BOD", "Managing Director and CEO"),
            DemoUnit("CRD", "Credit Division", "CEO", "Head of Credit"),
            DemoUnit("CRD-CORP", "Corporate Credit", "CRD", "Head of Corporate Credit"),
            DemoUnit("CRD-RTL", "Retail Credit", "CRD", "Head of Retail Credit"),
            DemoUnit("CRD-ADM", "Credit Administration", "CRD", "Head of Credit Administration"),
            DemoUnit("OPS", "Operations Division", "CEO", "Head of Operations"),
            DemoUnit("OPS-TRD", "Trade Services", "OPS", "Head of Trade Services"),
            DemoUnit("OPS-PAY", "Payments", "OPS", "Head of Payments"),
            DemoUnit("FIN", "Finance Division", "CEO", "Chief Financial Officer"),
            DemoUnit("FIN-REP", "Financial Reporting", "FIN", "Head of Financial Reporting"),
            DemoUnit("FIN-TRS", "Treasury", "FIN", "Head of Treasury"),
            DemoUnit("HR", "Human Resources", "CEO", "Head of Human Resources"),
            DemoUnit("HR-TAL", "Talent and Learning", "HR", "Head of Talent and Learning"),
            DemoUnit("HR-PAY", "Payroll and Benefits", "HR", "Head of Payroll"),
            DemoUnit("IT", "Information Technology", "CEO", "Chief Information Officer"),
            DemoUnit("IT-INF", "Infrastructure", "IT", "Head of Infrastructure"),
            DemoUnit("IT-APP", "Applications", "IT", "Head of Applications"),
            DemoUnit("IT-SEC", "Information Security", "IT", "Chief Information Security Officer"),
            DemoUnit("RSK", "Risk Management", "CEO", "Chief Risk Officer"),
            DemoUnit("RSK-CR", "Credit Risk", "RSK", "Head of Credit Risk"),
            DemoUnit("RSK-OR", "Operational Risk", "RSK", "Head of Operational Risk"),
            DemoUnit("CMP", "Compliance", "CEO", "Chief Compliance Officer"),
            DemoUnit("CMP-AML", "Anti-Money Laundering", "CMP", "Head of Anti-Money Laundering"),
            DemoUnit("REG", "Branch Network", "CEO", "Head of Branch Network"),
            DemoUnit("REG-N", "North Region", "REG", "Head of North Region"),
            DemoUnit("BR-GUL", "Gulshan Branch", "REG-N", "Manager, Gulshan Branch"),
            DemoUnit("BR-UTT", "Uttara Branch", "REG-N", "Manager, Uttara Branch"),
            DemoUnit("REG-S", "South Region", "REG", "Head of South Region"),
            DemoUnit("BR-AGR", "Agrabad Branch", "REG-S", "Manager, Agrabad Branch"),
            DemoUnit("BR-KHU", "Khulna Branch", "REG-S", "Manager, Khulna Branch"),
        )

    private val OFFICER_TITLES =
        listOf(
            "SEC" to "Board Affairs Officer",
            "AUD" to "Internal Auditor",
            "CRD-CORP" to "Relationship Manager",
            "CRD-CORP" to "Credit Analyst",
            "CRD-RTL" to "Credit Officer",
            "CRD-ADM" to "Documentation Officer",
            "OPS-TRD" to "Trade Officer",
            "OPS-PAY" to "Payments Officer",
            "FIN-REP" to "Accountant",
            "FIN-TRS" to "Dealer",
            "HR-TAL" to "Learning Officer",
            "HR-PAY" to "Payroll Officer",
            "IT-INF" to "Systems Engineer",
            "IT-APP" to "Software Engineer",
            "IT-SEC" to "Security Analyst",
            "RSK-CR" to "Credit Risk Analyst",
            "RSK-OR" to "Operational Risk Analyst",
            "CMP-AML" to "AML Analyst",
            "BR-GUL" to "Customer Service Officer",
            "BR-GUL" to "Cash Officer",
            "BR-UTT" to "Customer Service Officer",
            "BR-AGR" to "Customer Service Officer",
            "BR-KHU" to "Customer Service Officer",
        )

    /** Every position: unit heads first (in tree order), then officers. */
    val POSITIONS: List<DemoPosition> =
        UNITS.map { DemoPosition(it.headPosition, it.code, it.headTitle, head = true) } +
            OFFICER_TITLES.mapIndexed { n, (unit, title) -> DemoPosition(id("officer", "$unit:$n"), unit, title, head = false) }

    /** Who reports to whom: a head to the head of the parent unit, an officer to the head of their own unit. */
    val REPORTING_LINES: List<Pair<UUID, UUID>> =
        POSITIONS.mapNotNull { p ->
            val unit = UNITS.first { it.code == p.unit }
            val to = if (p.head) unit.parent?.let { parent -> unitByCode(parent).headPosition } else unit.headPosition
            to?.let { p.id to it }
        }

    /** Demo accounts' seats (by email), as the e2e journeys and the demo script expect them. */
    val SEATS: Map<String, UUID> =
        mapOf(
            "admin@demo.example" to unitByCode("IT").headPosition,
            "orgadmin@demo.example" to unitByCode("HR").headPosition,
            "branchadmin@demo.example" to unitByCode("REG-N").headPosition,
            "auditor@demo.example" to unitByCode("AUD").headPosition,
            "user@demo.example" to POSITIONS.first { it.title == "Credit Officer" }.id,
            // The demo approvers hold the seats the delegation of authority names (E04 T10).
            "head.credit@demo.example" to unitByCode("CRD").headPosition,
            "head.finance@demo.example" to unitByCode("FIN").headPosition,
            "director@demo.example" to unitByCode("CEO").headPosition,
        )

    /** Named staff without sign-in (INVITED), for the tree and the people picker. */
    val STAFF =
        listOf(
            "Farhana Rahman",
            "Tanvir Ahmed",
            "Nusrat Jahan",
            "Imran Hossain",
            "Sadia Islam",
            "Rafiq Uddin",
            "Ayesha Siddiqua",
            "Kamal Hasan",
            "Shirin Akter",
            "Mahmudul Karim",
            "Rumana Chowdhury",
            "Arif Mahmud",
            "Nasrin Sultana",
            "Zahid Hasan",
            "Tahmina Begum",
            "Shafiqul Alam",
            "Laila Arjumand",
            "Habibur Rahman",
            "Moushumi Das",
            "Sajid Khan",
            "Priya Saha",
            "Mizanur Rahman",
            "Fatema Khatun",
            "Rezaul Karim",
            "Jannatul Ferdous",
            "Anisur Rahman",
            "Sharmin Nahar",
            "Faisal Ahmed",
            "Rokeya Parvin",
            "Nazmul Huda",
            "Sumaiya Hoque",
            "Delwar Hossain",
            "Ishrat Jahan",
            "Asif Iqbal",
            "Marium Begum",
            "Golam Mostafa",
            "Nadia Karim",
            "Omar Faruk",
            "Salma Akhter",
            "Tariq Aziz",
            "Bilkis Banu",
            "Jamal Uddin",
            "Lubna Yasmin",
            "Sohel Rana",
        )

    /** The last few staff hold no position yet (new joiners), so journeys can place someone without moving anyone. */
    const val UNSEATED = 3

    /** A few seats stay vacant, so the screens show vacancies too. */
    val VACANT: Set<UUID> = setOf(unitByCode("RSK-OR").headPosition, unitByCode("BR-KHU").headPosition)

    fun staffEmail(name: String) = name.lowercase().replace(' ', '.') + "@demo.example"

    fun unitByCode(code: String): DemoUnit = UNITS.first { it.code == code }

    fun unitId(code: String): UUID = id("unit", code)

    private fun id(
        kind: String,
        key: String,
    ): UUID = UUID.nameUUIDFromBytes("demo:$kind:$key".toByteArray())
}
