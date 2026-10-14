// Fixtures for .semgrep/rules.yml (`semgrep --test .semgrep/`). Not compiled.
package fixtures

class Fixtures(private val log: org.slf4j.Logger, private val clock: java.time.Clock) {
    fun presign(presigner: software.amazon.awssdk.services.s3.presigner.S3Presigner) {
        // ruleid: memo-no-presigned-urls
        presigner.presignGetObject(request)
    }

    fun logs(password: String, userId: String) {
        // ruleid: memo-no-secrets-in-logs
        log.info("login {}", password)
        // ok: memo-no-secrets-in-logs
        log.info("login {}", userId)
    }

    fun time() {
        // ruleid: memo-no-wall-clock
        val bad = Instant.now()
        // ok: memo-no-wall-clock
        val good = Instant.now(clock)
    }

    fun tenant(dsl: org.jooq.DSLContext) {
        // ruleid: memo-no-session-level-set
        dsl.execute("SET app.tenant_id = 'x'")
        // ruleid: memo-no-session-level-set
        dsl.execute("select set_config('app.tenant_id', ?, false)")
        // ok: memo-no-session-level-set
        dsl.execute("select set_config('app.tenant_id', ?, true)")
    }
}
