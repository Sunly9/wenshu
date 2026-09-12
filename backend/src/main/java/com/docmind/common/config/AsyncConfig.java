package com.docmind.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /** 离线链路（解析/索引）执行器：CPU 密集，核心 2 线程排队执行 */
    @Bean("ingestExecutor")
    public ThreadPoolTaskExecutor ingestExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ingest-");
        executor.initialize();
        return executor;
    }
}
