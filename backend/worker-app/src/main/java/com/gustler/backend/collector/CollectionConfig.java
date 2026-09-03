package com.gustler.backend.collector;

import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
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
    static class ScheduledCollection implements InitializingBean, DisposableBean, ScheduledTaskHolder {

        private final CollectionProperties properties;
        private final CollectionScheduler scheduler;
        private final Clock clock;
        private final int dailyLimit;
        private final ThreadPoolTaskScheduler taskScheduler;
        private ScheduledTaskRegistrar registrar;

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
            this.taskScheduler = new ThreadPoolTaskScheduler();
            taskScheduler.setPoolSize(1);
            taskScheduler.setThreadNamePrefix("salmonbus-collection-");
            taskScheduler.setRemoveOnCancelPolicy(true);
        }

        @Override
        public void afterPropertiesSet() {
            warnIfOverDailyLimit();
            taskScheduler.initialize();
            registrar = new ScheduledTaskRegistrar();
            registrar.setTaskScheduler(taskScheduler);
            registrar.addTriggerTask(scheduler::collectAllRoutes, new AdaptiveCollectionTrigger(clock));
            registrar.afterPropertiesSet();
        }

        @Override
        public Set<ScheduledTask> getScheduledTasks() {
            return registrar == null ? Set.of() : registrar.getScheduledTasks();
        }

        @Override
        public void destroy() {
            if (registrar != null) {
                registrar.destroy();
            }
            taskScheduler.shutdown();
        }

        String threadNamePrefix() {
            return taskScheduler.getThreadNamePrefix();
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
