package com.memorevamp.common.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class ClockConfig {
    /** The single source of time (TESTING_STRATEGY §3.1): inject [Clock], never call `Instant.now()` in domain code. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
