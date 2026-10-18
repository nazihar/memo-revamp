package com.memo.common.context

import com.memo.common.tenant.PlatformScope
import com.memo.common.tenant.TenantAwareTransactionManager
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import org.slf4j.MDC
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import javax.sql.DataSource

/**
 * The scopes bound to the current unit of work, captured so that work handed to another thread runs with the same
 * tenant, actor and log context (ADR-0017). Platform scope travels too.
 */
class ContextSnapshot private constructor(
    private val tenant: TenantId?,
    private val platform: Boolean,
    private val actor: ActorScope?,
    private val mdc: Map<String, String>?,
) {
    fun <T> call(block: () -> T): T {
        val withActor: () -> T = if (actor != null) ({ ActorContext.call(actor, block) }) else block
        val withScope: () -> T =
            when {
                tenant != null -> ({ TenantContext.call(tenant, withActor) })
                platform -> ({ PlatformScope.call(withActor) })
                else -> withActor
            }
        val previous = MDC.getCopyOfContextMap()
        if (mdc != null) MDC.setContextMap(mdc) else MDC.clear()
        try {
            return withScope()
        } finally {
            if (previous != null) MDC.setContextMap(previous) else MDC.clear()
        }
    }

    companion object {
        fun capture() =
            ContextSnapshot(
                TenantContext.currentOrNull(),
                PlatformScope.isActive(),
                ActorContext.currentOrNull(),
                MDC.getCopyOfContextMap(),
            )
    }
}

/** Applied by Spring Boot to its task executors (including the virtual-thread one): workers inherit the submitter's scopes. */
class ContextTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable {
        val snapshot = ContextSnapshot.capture()
        return Runnable { snapshot.call { runnable.run() } }
    }
}

@Configuration(proxyBeanMethods = false)
class ContextConfig {
    @Bean
    fun contextTaskDecorator(): TaskDecorator = ContextTaskDecorator()

    /** Replaces Boot's default `JdbcTransactionManager` (ADR-0017). */
    @Bean
    fun transactionManager(
        dataSource: DataSource,
        customizers: ObjectProvider<TransactionManagerCustomizers>,
    ): TenantAwareTransactionManager = TenantAwareTransactionManager(dataSource).also { tm -> customizers.ifAvailable { it.customize(tm) } }
}
