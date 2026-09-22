package com.gustler.backend.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TripQualityInvestigationJob {
    private static final Logger log = LoggerFactory.getLogger(TripQualityInvestigationJob.class);
    private final TripQualityRepository quality;
    public TripQualityInvestigationJob(TripQualityRepository quality) { this.quality = quality; }

    @Scheduled(fixedDelayString = "${forecast.quality-investigation-interval:10s}")
    public void investigate() {
        try {
            quality.investigateNext();
        } catch (QueryTimeoutException | PessimisticLockingFailureException e) {
            deferred(e);
        } catch (UncategorizedSQLException e) {
            if (!"55P03".equals(e.getSQLException().getSQLState())) { throw e; }
            deferred(e);
        }
    }

    private static void deferred(Exception exception) {
        log.warn("event=forecast_trip_investigation_deferred reason=DB_TIME_BUDGET exception={}", exception.getClass().getSimpleName());
    }
}
