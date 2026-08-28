package com.gustler.backend.collector;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
@EnableConfigurationProperties(CollectionProperties.class)
public class CollectionConfig {

    private static final Logger log = LoggerFactory.getLogger(CollectionConfig.class);

    /**
     * collection.enabled 가 참일 때만 이 설정이 산다. 빈이 아예 안 생겨 스케줄도 안 걸린다.
     *
     * <p>기본을 꺼두는 이유가 셋이다. 서비스키가 아직 없어 켜두면 안 풀린 자리표시자를 키 삼아
     * 상류를 두드리고, 통합 테스트가 컨텍스트를 띄우는 내내 15초마다 실호출이 나가고,
     * 24시간 돌 서버가 아직 없다.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "collection", name = "enabled", havingValue = "true")
    static class ScheduledCollection implements SchedulingConfigurer {

        private final CollectionProperties properties;
        private final CollectionScheduler scheduler;
        private final Clock clock;
        private final int dailyLimit;

        ScheduledCollection(
            CollectionProperties properties,
            CollectionScheduler scheduler,
            Clock clock,
            GbisProperties gbisProperties
        ) {
            this.properties = properties;
            this.scheduler = scheduler;
            this.clock = clock;
            this.dailyLimit = gbisProperties.dailyLimit();
        }

        @Override
        public void configureTasks(
            ScheduledTaskRegistrar registrar
        ) {
            warnIfOverDailyLimit();
            registrar.addTriggerTask(scheduler::collectAllRoutes, new AdaptiveCollectionTrigger(clock));
        }

        private void warnIfOverDailyLimit() {
            final int routeCount = properties.routeIds().size();
            if (!CollectionSchedule.fitsDailyLimit(routeCount, dailyLimit)) {
                log.error("이 주기로 {}개 노선을 돌면 하루 {}회라 한도 {}회를 넘는다. 주기표나 노선 수를 고쳐라.",
                    routeCount, CollectionSchedule.dailyCallsFor(routeCount), dailyLimit);
            }
        }
    }
}
