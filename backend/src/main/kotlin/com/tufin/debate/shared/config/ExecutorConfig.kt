package com.tufin.debate.shared.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class ExecutorConfig {

    /** Background executor for negotiation runs — bounded so runs cannot exhaust the server. */
    @Bean("negotiationExecutor")
    fun negotiationExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize = 2
        maxPoolSize = 4
        queueCapacity = 20
        setThreadNamePrefix("negotiation-")
        initialize()
    }
}
