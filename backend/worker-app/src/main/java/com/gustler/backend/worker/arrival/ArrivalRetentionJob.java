package com.gustler.backend.worker.arrival;

import com.gustler.backend.arrivals.api.RetainArrivalSnapshots;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "arrivals", name = "enabled", havingValue = "true")
public class ArrivalRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(ArrivalRetentionJob.class);
    private final RetainArrivalSnapshots store;
    private final ArrivalProperties properties;
    private final Clock clock;

    public ArrivalRetentionJob(RetainArrivalSnapshots store, ArrivalProperties properties, Clock clock) {
        this.store = store; this.properties = properties; this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${arrivals.cleanup-interval:60s}", initialDelayString = "60s")
    public void purge() {
        try { store.purgeExpiredBefore(clock.instant().minus(properties.retention()), 500); }
        catch (RuntimeException failure) {
            log.warn("event=arrival_retention_failed exceptionType={}", failure.getClass().getSimpleName());
        }
    }
}
