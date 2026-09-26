package com.gustler.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 수집 1개와 처리 1개만 허용한다. DB 연결 풀 크기를 함께 늘리지 않는다. */
@Configuration(proxyBeanMethods=false)
public class WorkerSchedulingConfig {
    @Bean(name="taskScheduler")
    ThreadPoolTaskScheduler processingScheduler() { return scheduler("processing-"); }

    @Bean(name="collectionTaskScheduler")
    ThreadPoolTaskScheduler collectionScheduler() { return scheduler("collection-"); }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
