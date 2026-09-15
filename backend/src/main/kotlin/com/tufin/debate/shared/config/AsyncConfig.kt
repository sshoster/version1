package com.tufin.debate.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/** Executor for fire-and-forget side effects (email/SMS) so request threads never block on them. */
@Configuration
@EnableAsync
class AsyncConfig {

    @Bean("notificationExecutor")
    fun notificationExecutor(): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 200
        setThreadNamePrefix("notify-")
        initialize()
    }
}
