package com.gustler.backend.processor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 예보 시작 전에 과거를 조사하지 않는다. 등록된 이상 차량 하나를 제한된 양만 처리한다. */
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
            // JDBC 드라이버/예외 변환기에 따라 PostgreSQL lock_timeout이 이 타입으로 전달된다.
            if (!"55P03".equals(e.getSQLException().getSQLState())) { throw e; }
            deferred(e);
        }
    }

    private static void deferred(Exception exception) {
        log.warn("event=forecast_trip_investigation_deferred reason=DB_TIME_BUDGET exception={}", exception.getClass().getSimpleName());
    }
}
