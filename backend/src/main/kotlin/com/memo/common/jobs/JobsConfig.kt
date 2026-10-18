package com.memo.common.jobs

import com.github.kagkarlsson.scheduler.boot.autoconfigure.Jackson3Serializer
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer
import com.github.kagkarlsson.scheduler.serializer.Serializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper
import java.util.Optional

@Configuration(proxyBeanMethods = false)
class JobsConfig {
    /** Task data as JSON through the application's mapper (Kotlin data classes; no Java serialization). */
    @Bean
    fun dbSchedulerCustomizer(jsonMapper: JsonMapper): DbSchedulerCustomizer =
        object : DbSchedulerCustomizer {
            override fun serializer(): Optional<Serializer> = Optional.of(Jackson3Serializer(jsonMapper))
        }
}
