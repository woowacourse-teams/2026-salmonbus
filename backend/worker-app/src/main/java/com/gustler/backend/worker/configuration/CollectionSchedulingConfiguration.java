package com.gustler.backend.worker.configuration;

import com.gustler.backend.gbis.api.GbisKey;
import com.gustler.backend.observations.api.CollectionSchedule;
import com.gustler.backend.worker.scheduling.CollectionScheduleTrigger;
import com.gustler.backend.worker.scheduling.CollectionScheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 수집 활성 여부에 따라 같은 수집 스케줄러와 적응형 또는 고정 주기를 연결한다.
 * 예상 호출량을 하루 한도와 비교한다.
 */
@Configuration
@EnableConfigurationProperties({CollectionProperties.class, GbisProperties.class})
public class CollectionSchedulingConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionSchedulingConfiguration.class);

    /**
     * collection.enabled 가 참일 때만 이 설정을 활성화하고 빈과 스케줄을 등록한다.
     *
     * <p>기본을 꺼두는 이유는 셋이다. 서비스키가 아직 없어 켜두면 해석되지 않은 자리표시자를 키로
     * GBIS를 호출하고, 통합 테스트에서 컨텍스트가 떠 있는 동안 15초마다 실호출이 나가며,
     * 24시간 실행할 서버가 아직 없다.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "collection", name = "enabled", havingValue = "true")
    static class ScheduledCollection {

        private final CollectionProperties properties;
        private final CollectionScheduler scheduler;
        private final Clock clock;
        private final List<String> keyAliases;
        private final int dailyLimit;
        private final TaskScheduler collectionTaskScheduler;

        ScheduledCollection(
            CollectionProperties properties,
            CollectionScheduler scheduler,
            Clock clock,
            GbisProperties gbisProperties,
            @Qualifier("collectionTaskScheduler") TaskScheduler collectionTaskScheduler
        ) {
            this.properties = properties;
            this.scheduler = scheduler;
            this.clock = clock;
            this.keyAliases = gbisProperties.serviceKeys()
                .stream()
                .map(GbisKey::alias)
                .toList();
            this.dailyLimit = keyAliases.stream()
                .mapToInt(gbisProperties::dailyLimitOf)
                .sum();
            this.collectionTaskScheduler = collectionTaskScheduler;
        }

        /**
         * 등록기는 만들어지는 즉시 첫 수집을 예약한다. 첫 관측 행이 그날 파티션에 들어가도록 날짜 파티션 유지 작업이 첫 유지를 끝낸
         * 뒤에 만든다.
         */
        @Bean
        @DependsOn(PartitionConfiguration.PARTITION_JOB)
        ScheduledTaskRegistrar collectionTaskRegistrar() {
            LOGGER.info(
                    "GBIS 키 슬롯 {}개로 수집한다. 슬롯={} 하루 한도 합={}",
                    keyAliases.size(),
                    String.join(",", keyAliases),
                    dailyLimit
            );

            warnIfOverDailyLimit();

            ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
            registrar.setTaskScheduler(collectionTaskScheduler);
            registrar.addTriggerTask(scheduler::collectAllRoutes,
                new CollectionScheduleTrigger(clock, properties.fixedInterval()));

            return registrar;
        }

        private void warnIfOverDailyLimit() {
            final int routeCount = properties.routeIds().size();
            final long estimatedCalls = calculateDailyCallCount(routeCount);
            if (estimatedCalls > dailyLimit) {
                LOGGER.error(
                        "현재 주기로 {}개 노선을 수집하면 하루 {}회 호출해 한도 {}회를 초과한다. 수집 주기 또는 노선 수를 조정해야 한다.",
                        routeCount,
                        estimatedCalls,
                        dailyLimit
                );
            }
        }

        private long calculateDailyCallCount(final int routeCount) {
            if (properties.fixedInterval() == null) {
                return CollectionSchedule.dailyCallsFor(routeCount);
            }
            return (long) routeCount * (Duration.ofDays(1).dividedBy(properties.fixedInterval()) + 1);
        }
    }
}
