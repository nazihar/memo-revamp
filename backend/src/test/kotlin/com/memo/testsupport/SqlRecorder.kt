package com.memo.testsupport

import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.ExecuteListenerProvider
import org.springframework.stereotype.Component

/**
 * Records the SQL that jOOQ runs on the calling thread inside [record], with the bind values inlined, so a performance
 * test can `EXPLAIN` exactly what a read path runs rather than a hand-written copy of it. Picked up by Spring Boot's jOOQ
 * configuration like any `ExecuteListenerProvider` bean (test sources are scanned); outside [record] it does nothing.
 */
@Component
class SqlRecorder : ExecuteListenerProvider {
    private val recording = ThreadLocal<MutableList<String>?>()

    private val listener =
        object : ExecuteListener {
            override fun executeStart(ctx: ExecuteContext) {
                val statements = recording.get() ?: return
                val query = ctx.query()
                statements += if (query != null) ctx.dsl().renderInlined(query) else ctx.sql().orEmpty()
            }
        }

    override fun provide(): ExecuteListener = listener

    /** Runs [block] and answers its result with the statements it ran, in order. */
    fun <T> record(block: () -> T): Pair<T, List<String>> {
        val statements = mutableListOf<String>()
        recording.set(statements)
        try {
            return block() to statements.toList()
        } finally {
            recording.remove()
        }
    }
}
