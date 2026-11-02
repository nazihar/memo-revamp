package com.memo.authz

import com.memo.common.tenant.TenantTransactions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AuthzMatrix
import com.memo.testsupport.AuthzSubjects
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MatrixCell
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Generated from the matrix files (TESTING_STRATEGY §3.4, design E04 §7): every allowed cell is a positive test,
 * every denied cell a negative one. Path variables get random ids and bodies are `{}` unless the entry gives a sample:
 * an allowed request may then fail validation or find nothing (400, 404, 428), which still proves it passed
 * authorisation.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationMatrixTest : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var json: JsonMapper

    private val subjects by lazy { AuthzSubjects(mvc, registry, settings, transactions, accounts, mfaSeeding) }

    @TestFactory
    fun matrix(): List<DynamicTest> = AuthzMatrix.cells.map { cell -> DynamicTest.dynamicTest(cell.toString()) { check(cell) } }

    private fun check(cell: MatrixCell) {
        val uri = cell.path.replace(PATH_VARIABLE) { UUID.randomUUID().toString() }
        val body = cell.body?.let { randomised(json.writeValueAsString(it)) } ?: "{}"
        val headers = cell.headers.mapValues { randomised(it.value) }
        val result = mvc.sendJson(HttpMethod.valueOf(cell.method), subjects.tenant, uri, subjects.session(cell.subject), body, headers)
        val status = result.response.status
        val describe = "$cell (body: ${result.response.contentAsString})"
        if (cell.expected == "allow") {
            assertThat(status).describedAs(describe).isLessThan(500).isNotEqualTo(403)
            // A wrong code or password is not a denial; any other 401 is.
            if (status == 401) assertThat(result.response.contentAsString).describedAs(describe).contains(INVALID_CREDENTIALS)
        } else {
            assertThat(status).describedAs(describe).isEqualTo(cell.expected.toInt())
        }
    }

    private fun randomised(value: String) = value.replace("{random}", UUID.randomUUID().toString())

    private companion object {
        val PATH_VARIABLE = Regex("\\{[^}]+}")
        const val INVALID_CREDENTIALS = "urn:memo:problem:invalid-credentials"
    }
}
