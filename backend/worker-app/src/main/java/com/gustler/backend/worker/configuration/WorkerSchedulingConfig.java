package com.gustler.backend.worker.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** 수집·실시간 처리·통계/이관을 각각 단일 스레드로 실행한다. DB 풀 크기는 유지한다. */
@Configuration(proxyBeanMethods=false)
public class WorkerSchedulingConfig {
    @Bean(name="taskScheduler")
    ThreadPoolTaskScheduler processingScheduler() { return scheduler("processing-"); }

    @Bean(name="collectionTaskScheduler")
    ThreadPoolTaskScheduler collectionScheduler() { return scheduler("collection-"); }

    @Bean(name="calibrationTaskScheduler")
    ThreadPoolTaskScheduler calibrationScheduler() { return scheduler("calibration-"); }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler=new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
