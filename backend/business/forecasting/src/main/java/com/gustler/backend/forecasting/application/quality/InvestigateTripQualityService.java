package com.gustler.backend.forecasting.application.quality;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.forecasting.api.quality.InvestigateTripQuality;



import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.stereotype.Component;

@Component
public class InvestigateTripQualityService implements InvestigateTripQuality {
    private final TripQualityInvestigationService quality;
    public InvestigateTripQualityService(TripQualityInvestigationService quality) { this.quality = quality; }

    public void investigate() {
        WorkerOperationLog.run("quality_investigation", "all", this::investigateOnce);
    }

    private void investigateOnce() {
        try {
            quality.investigateNext();
            WorkerOperationLog.recovered("quality_investigation", "all");
        } catch (QueryTimeoutException | PessimisticLockingFailureException e) {
            deferred(e);
        } catch (UncategorizedSQLException e) {
            // JDBC 드라이버/예외 변환기에 따라 PostgreSQL lock_timeout이 이 타입으로 전달된다.
            if (!"55P03".equals(e.getSQLException().getSQLState())) { throw e; }
            deferred(e);
        }
    }

    private static void deferred(Exception exception) {
        WorkerOperationLog.warn("quality_investigation", "all", "DB_TIME_BUDGET_" + exception.getClass().getSimpleName());
    }
}
