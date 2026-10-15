package com.memorevamp.system.info

import com.memorevamp.testsupport.IntegrationTest
import com.memorevamp.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.DriverManager
import java.sql.SQLException

/** Migrations apply to an empty database; the application role is DML-only and cannot touch the platform marker. */
class SystemInfoRepositoryIT : IntegrationTest() {
    @Autowired
    lateinit var repository: SystemInfoRepository

    private fun <T> asApp(block: (java.sql.Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_app", TestDatabase.appPassword).use(block)

    @Test
    fun `marker row is readable through jooq`() {
        assertThat(repository.schemaMarker()).isEqualTo(SystemInfoRepository.EXPECTED_MARKER)
    }

    @Test
    fun `app role cannot modify the platform marker`() {
        asApp { c ->
            assertThatThrownBy { c.createStatement().executeUpdate("update system_info set schema_marker = 'x'") }
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `app role cannot run DDL`() {
        asApp { c ->
            assertThatThrownBy { c.createStatement().execute("create table evil (id int)") }
                .isInstanceOf(SQLException::class.java)
                .hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `app role is neither owner, superuser nor allowed to bypass RLS`() {
        asApp { c ->
            val rs =
                c.createStatement().executeQuery(
                    "select rolsuper, rolbypassrls, (select count(*) from pg_tables where tableowner = current_user) " +
                        "from pg_roles where rolname = current_user",
                )
            rs.next()
            assertThat(rs.getBoolean(1)).describedAs("superuser").isFalse()
            assertThat(rs.getBoolean(2)).describedAs("bypassrls").isFalse()
            assertThat(rs.getLong(3)).describedAs("owned tables").isZero()
        }
    }
}
