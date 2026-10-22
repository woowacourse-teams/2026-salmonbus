package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrivals.api.RetainArrivalSnapshots;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보존 기간을 지난 도착정보 수신 자료를 정리하는 기존 Worker 작업을 실행한다.
 */
@Component
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalRetentionJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(ArrivalRetentionJob.class);
    private final RetainArrivalSnapshots store;
    private final ArrivalProperties properties;
    private final Clock clock;

    public ArrivalRetentionJob(RetainArrivalSnapshots store, ArrivalProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${arrivals.cleanup-interval:60s}", initialDelayString = "60s")
    public void purge() {
        try {
            store.purgeExpiredBefore(clock.instant().minus(properties.retention()), 500);
        }
        catch (RuntimeException failure) {
            LOGGER.warn("event=arrival_retention_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
