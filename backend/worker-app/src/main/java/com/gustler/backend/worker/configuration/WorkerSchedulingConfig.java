package com.gustler.backend.worker.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 수집·실시간 처리·예보 줍기·날짜 파티션 유지를 각각 단일 스레드로 실행한다. DB 풀 크기는 유지한다.
 *
 * <p>컨텍스트가 닫히기 시작하면 스프링은 스케줄러가 새 일을 받지 않게 하지만, 다음 수집처럼 실행 시각을 기다리는 한 번짜리 예약은
 * 남겨 두어 종료 중에도 시각이 되면 실행한다. 여기서 만드는 스케줄러는 그런 예약도 실행하지 않는다. 돌던 회차는 스프링이 스케줄러의
 * 정지 단계에서 끝나기를 기다린다.
 */
@Configuration(proxyBeanMethods = false)
public class WorkerSchedulingConfig {
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler processingScheduler() {
        return scheduler("processing-");
    }

    @Bean(name = "collectionTaskScheduler")
    ThreadPoolTaskScheduler collectionScheduler() {
        return scheduler("collection-");
    }

    @Bean(name = "forecastTaskScheduler")
    ThreadPoolTaskScheduler forecastScheduler() {
        return scheduler("forecast-");
    }

    @Bean(name = "calibrationTaskScheduler")
    ThreadPoolTaskScheduler calibrationScheduler() {
        return scheduler("calibration-");
    }

    private static ThreadPoolTaskScheduler scheduler(String prefix) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return scheduler;
    }
}
