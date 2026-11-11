package com.memo.notification

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.identity.recipient
import com.memo.notification.internal.NotificationPartitionCallback
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.testsupport.sendJson
import com.memo.testsupport.signIn
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.json.JsonMapper
import java.sql.DriverManager

/** In-app notifications, preferences and their API (ADR-0030; test plan T1, T5). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationsIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvcTester

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var notifications: Notifications

    @Autowired lateinit var json: JsonMapper

    private val tenant: TestTenant by lazy { registry.aTestTenant(transactions, settings, "notify", mfaRequired = false) }

    private fun aUser(password: String = aPassword()): Pair<UserAccount, String> =
        accounts.anActiveUser(transactions, tenant, password) to password

    private fun notify(
        user: UserAccount,
        kind: NotificationKind,
        params: Map<String, String> = mapOf("organisation" to "Bank"),
    ) = transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { notifications.notify(user.recipient(), kind, params) } }

    private fun count(sql: String) = AppRoleJdbc.inTransaction(tenant.id) { it.long(sql) }

    private fun items(user: UserAccount) = count("select count(*) from notification_item where user_id = '${user.id.value}'")

    private fun mails(user: UserAccount) = count("select count(*) from notification_mail_outbox where to_address = '${user.email}'")

    private fun session(
        user: UserAccount,
        password: String,
    ): Cookie = mvc.signIn(tenant, user.email, password)

    private fun get(
        uri: String,
        cookie: Cookie,
    ): MvcTestResult =
        mvc
            .get()
            .uri(uri)
            .with(onHost(tenant.slug))
            .cookie(cookie)
            .exchange()

    @Suppress("UNCHECKED_CAST")
    private fun MvcTestResult.json(): Map<String, Any?> = json.readValue(response.contentAsString, Map::class.java) as Map<String, Any?>

    @Test
    fun `channels follow preferences, and security notices ignore opt-outs`() {
        val (user, password) = aUser()
        mvc.sendJson(
            HttpMethod.PUT,
            tenant,
            "/api/v1/me/notification-preferences",
            session(user, password),
            """[{"category":"ADMINISTRATION","channel":"EMAIL","enabled":false}]""",
        )

        notify(user, NotificationKind.ROLE_GRANTED, mapOf("organisation" to "Bank", "role" to "Auditor"))
        notify(user, NotificationKind.SIGN_IN_ALERT, mapOf("organisation" to "Bank", "device" to "Firefox", "time" to "now"))

        assertThat(items(user)).isEqualTo(2)
        assertThat(mails(user)).isEqualTo(1)
    }

    @Test
    fun `a rolled-back producer notifies nobody`() {
        val (user, _) = aUser()

        assertThatThrownBy {
            transactions.inTenant(tenant.id) {
                ActorContext.asSystem("test") {
                    notifications.notify(
                        user.recipient(),
                        NotificationKind.PASSWORD_CHANGED,
                        mapOf("organisation" to "Bank"),
                    )
                }
                error("business change failed")
            }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(items(user)).isZero()
        assertThat(mails(user)).isZero()
    }

    @Test
    fun `I see only my own notifications, page by page, and can mark them read`() {
        val (me, password) = aUser()
        val (other, _) = aUser()
        repeat(5) { notify(me, NotificationKind.ROLE_GRANTED, mapOf("organisation" to "Bank", "role" to "R$it")) }
        notify(other, NotificationKind.ROLE_GRANTED, mapOf("organisation" to "Bank", "role" to "Secret"))
        val cookie = session(me, password)

        assertThat(get("/api/v1/me/notifications/unread-count", cookie).json()["count"]).isEqualTo(5)
        val first = get("/api/v1/me/notifications?limit=3", cookie).json()
        val firstItems = (first["items"] as List<*>).map { it as Map<*, *> }
        assertThat(firstItems).hasSize(3)
        assertThat((firstItems.first()["params"] as Map<*, *>)["role"]).isEqualTo("R4")
        val second = get("/api/v1/me/notifications?limit=3&cursor=${first["nextCursor"]}", cookie).json()
        assertThat(second["items"] as List<*>).hasSize(2)
        assertThat(second["nextCursor"]).isNull()

        val otherItem =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select id from notification_item where user_id = ?").use { st ->
                    st.setObject(1, other.id.value)
                    st.executeQuery().use {
                        it.next()
                        it.getString(1)
                    }
                }
            }
        assertThat(
            mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/me/notifications/$otherItem/read", cookie),
        ).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/me/notifications/${firstItems.first()["id"]}/read", cookie))
            .hasStatus(HttpStatus.NO_CONTENT)
        assertThat(get("/api/v1/me/notifications/unread-count", cookie).json()["count"]).isEqualTo(4)
        mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/me/notifications/read-all", cookie)
        assertThat(get("/api/v1/me/notifications/unread-count", cookie).json()["count"]).isEqualTo(0)
        assertThat(count("select count(*) from notification_item where user_id = '${other.id.value}' and read_at is null")).isEqualTo(1)
    }

    @Test
    fun `a notification keeps its link to an app route, up to 500 characters`() {
        val (user, _) = aUser()
        val link = "/memos/" + "a".repeat(493)
        transactions.inTenant(tenant.id) {
            ActorContext.asSystem(
                "test",
            ) { notifications.notify(user.recipient(), NotificationKind.ROLE_GRANTED, mapOf("role" to "R"), link) }
        }
        assertThat(count("select count(*) from notification_item where user_id = '${user.id.value}' and link = '$link'")).isEqualTo(1)
        assertThatThrownBy {
            transactions.inTenant(tenant.id) {
                ActorContext.asSystem("test") {
                    notifications.notify(
                        user.recipient(),
                        NotificationKind.ROLE_GRANTED,
                        mapOf("role" to "R"),
                        "https://elsewhere.example/x",
                    )
                }
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `security notices cannot be turned off`() {
        val (user, password) = aUser()
        val cookie = session(user, password)

        val refused =
            mvc.sendJson(
                HttpMethod.PUT,
                tenant,
                "/api/v1/me/notification-preferences",
                cookie,
                """[{"category":"SECURITY","channel":"EMAIL","enabled":false}]""",
            )

        assertThat(refused).hasStatus(HttpStatus.CONFLICT)
        assertThat(refused.response.contentAsString).contains("MANDATORY")
        val prefs = json.readValue(get("/api/v1/me/notification-preferences", cookie).response.contentAsString, List::class.java)
        assertThat(prefs.map { it as Map<*, *> }.filter { it["category"] == "SECURITY" }).allMatch {
            it["enabled"] == true &&
                it["mandatory"] == true
        }
    }

    @Test
    fun `partitions exist months ahead, and expired ones are dropped by the migrator`() {
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use {
                it.execute("set search_path = memo")
                it.execute(
                    "create table if not exists notification_item_y2020m01 partition of notification_item " +
                        "for values from ('2020-01-01 00:00:00+00') to ('2020-02-01 00:00:00+00')",
                )
            }
            NotificationPartitionCallback.ensurePartitions(c, 3, 13)
            val names =
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid " +
                                "join pg_class p on p.oid = i.inhparent where p.relname = 'notification_item'",
                        ).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            assertThat(names).doesNotContain("notification_item_y2020m01")
            assertThat(names.count { it.matches(Regex("notification_item_y\\d{4}m\\d{2}")) }).isGreaterThanOrEqualTo(4)
        }
    }
}
