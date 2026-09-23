package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;

import com.gustler.backend.forecasting.infrastructure.quality.TripQualityRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;

@Component
public class InvestigateTripQualityService implements InvestigateTripQuality {
    private static final Logger log = LoggerFactory.getLogger(InvestigateTripQualityService.class);
    private final TripQualityRepository quality;
    public InvestigateTripQualityService(TripQualityRepository quality) { this.quality = quality; }

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
