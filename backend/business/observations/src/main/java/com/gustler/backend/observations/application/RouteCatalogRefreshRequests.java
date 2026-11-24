package com.gustler.backend.observations.application;

import com.gustler.backend.observations.api.ObservationRouteStopMismatchDetected;
import com.gustler.backend.routecatalog.api.RouteCatalogPreparationRequested;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 미준비 노선과 저장 중 확인한 정류장 참조 불일치를 별도 노선 준비 작업에 알린다. */
final class RouteCatalogRefreshRequests {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationCollector.class);
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String ROUTE_STOP_CONSTRAINT = "fk_observation_route_stop";

    private final ApplicationEventPublisher eventPublisher;

    RouteCatalogRefreshRequests(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    void requestMissingRoute(String sourceRouteId) {
        eventPublisher.publishEvent(new RouteCatalogPreparationRequested(sourceRouteId));
    }

    void requestAfterFailedSave(String sourceRouteId, final long routeVersionId, RuntimeException failure) {
        if (!isRouteStopMismatch(failure)) {
            return;
        }

        notifyAfterRollback(new ObservationRouteStopMismatchDetected(sourceRouteId, routeVersionId));
    }

    private static boolean isRouteStopMismatch(Throwable failure) {
        Set<Throwable> visitedCauses = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pendingCauses = new ArrayDeque<>();
        pendingCauses.add(failure);

        while (!pendingCauses.isEmpty()) {
            Throwable currentFailure = pendingCauses.removeFirst();
            if (!visitedCauses.add(currentFailure)) {
                continue;
            }

            if (currentFailure instanceof SQLException sqlFailure) {
                if (isRouteStopViolation(sqlFailure)) {
                    return true;
                }

                if (sqlFailure.getNextException() != null) {
                    pendingCauses.addLast(sqlFailure.getNextException());
                }
            }

            if (currentFailure.getCause() != null) {
                pendingCauses.addLast(currentFailure.getCause());
            }
        }
        return false;
    }

    private static boolean isRouteStopViolation(SQLException failure) {
        if (!FOREIGN_KEY_VIOLATION.equals(failure.getSQLState())
            || !(failure instanceof PSQLException postgresFailure)) {
            return false;
        }

        ServerErrorMessage databaseError = postgresFailure.getServerErrorMessage();
        return databaseError != null && ROUTE_STOP_CONSTRAINT.equals(databaseError.getConstraint());
    }

    /** 저장 롤백이 끝난 뒤 요청한다. 상위 트랜잭션이 남아 있으면 그 롤백 완료까지 기다린다. */
    private void notifyAfterRollback(ObservationRouteStopMismatchDetected event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            publishMismatch(event);
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(final int status) {
                if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                    publishMismatch(event);
                }
            }
        });
    }

    private void publishMismatch(ObservationRouteStopMismatchDetected event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (RuntimeException notificationFailure) {
            LOGGER.warn(
                    "event=observation_route_mismatch_notification_failed routeId={} routeVersionId={} "
                            + "exceptionType={}",
                    event.sourceRouteId(),
                    event.routeVersionId(),
                    notificationFailure.getClass().getSimpleName()
            );
        }
    }
}
