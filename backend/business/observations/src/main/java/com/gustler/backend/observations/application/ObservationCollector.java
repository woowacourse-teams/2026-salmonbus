package com.gustler.backend.observations.application;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.api.CollectObservations;
import com.gustler.backend.observations.api.ObservationRouteMismatchDetected;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationSource;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 한 노선의 예약, HTTP 호출, 수집 결과 저장을 순서대로 진행한다. */
@Component
public class ObservationCollector implements CollectObservations {
    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationCollector.class);

    private final CurrentRouteVersion currentRouteVersion;
    private final ObservationBatchLedger batchLedger;
    private final ApiCallQuota callQuota;
    private final ObservationSource observationSource;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public ObservationCollector(
        CurrentRouteVersion currentRouteVersion,
        ObservationBatchLedger batchLedger,
        ApiCallQuota callQuota,
        ObservationSource observationSource,
        Clock clock
    ) {
        this(currentRouteVersion, batchLedger, callQuota, observationSource, clock, ignored -> {});
    }

    @Autowired
    public ObservationCollector(CurrentRouteVersion currentRouteVersion, ObservationBatchLedger batchLedger,
            ApiCallQuota callQuota, ObservationSource observationSource, Clock clock,
            ApplicationEventPublisher events) {
        this.currentRouteVersion = currentRouteVersion;
        this.batchLedger = batchLedger;
        this.callQuota = callQuota;
        this.observationSource = observationSource;
        this.clock = clock;
        this.events = events;
    }

    /**
     * 한 노선의 판본 확인·예약·수집·저장을 조율한다. 처리 중 발생한 RuntimeException은 실패 단계를 기록한 뒤 전파한다.
     */
    public void collectOnce(
        String sourceRouteId
    ) {
        try (CollectionAttemptLog attempt = new CollectionAttemptLog(sourceRouteId)) {
            OffsetDateTime scheduledAt = now();
            WorkerOperationLog.collectionAttempted(sourceRouteId, scheduledAt.toEpochSecond());
            try {
                attempt.stage("ROUTE_VERSION");
                Optional<RouteReference> reference =
                    WorkerOperationLog.measure("collection_route_version", sourceRouteId,
                        () -> currentRouteVersion.currentVersionOf(sourceRouteId, scheduledAt));
                if (reference.isEmpty()) {
                    WorkerOperationLog.warn("collection_route_version", sourceRouteId, "NO_ROUTE_VERSION");
                    attempt.finish("DEFERRED", "NO_ROUTE_VERSION");
                    return;
                }
                WorkerOperationLog.recovered("collection_route_version", sourceRouteId);
                attempt.routeVersion(reference.orElseThrow().routeVersionId());
                collectOn(reference.orElseThrow().routeVersionId(), sourceRouteId, scheduledAt, attempt);
            } catch (RuntimeException failure) {
                attempt.failed(failure);
                closeUnfinished(attempt.batchId(), sourceRouteId);
                throw failure;
            }
        }
    }

    private void closeUnfinished(final long batchId, String sourceRouteId) {
        if (batchId < 1) {
            return;
        }
        try {
            final int closed = WorkerOperationLog.measure("collection_failure_close", sourceRouteId,
                () -> batchLedger.closeUnfinished(batchId));
            if (closed > 0) {
                LOGGER.warn("event=collection_batch_closed reason=COLLECTION_FAILED batchId={} routeId={}",
                    batchId, sourceRouteId);
            }
        } catch (RuntimeException closeFailure) {
            LOGGER.error("event=collection_batch_close_failed batchId={} routeId={} exceptionType={}",
                batchId, sourceRouteId, closeFailure.getClass().getSimpleName());
        }
    }

    private void collectOn(
        final long routeVersionId,
        String sourceRouteId,
        OffsetDateTime scheduledAt,
        CollectionAttemptLog attempt
    ) {
        attempt.stage("RESERVE");
        ObservationBatchReservation reservation = WorkerOperationLog.measure("collection_reserve", sourceRouteId,
            () -> batchLedger.reserve(
                new CollectionPlan(routeVersionId, scheduledAt, attemptKeyOf(sourceRouteId, scheduledAt)),
                scheduledAt));
        attempt.batch(reservation.batchId());
        if (!reservation.reserved()) {
            WorkerOperationLog.warn("collection_quota", sourceRouteId, "DAILY_LIMIT");
            attempt.finish("DEFERRED", "DAILY_LIMIT");
            return;
        }
        OffsetDateTime requestedAt = now();
        attempt.stage("DISPATCH");
        if (!WorkerOperationLog.measure("collection_dispatch", sourceRouteId,
            () ->
            batchLedger.markDispatching(reservation.batchId(), scheduledAt, requestedAt, reservation.keyAlias()))) {
            WorkerOperationLog.warn("collection_quota", sourceRouteId, "NEXT_DAY_LIMIT");
            attempt.finish("DEFERRED", "NEXT_DAY_LIMIT");
            return;
        }
        WorkerOperationLog.recovered("collection_quota", sourceRouteId);
        attempt.stage("UPSTREAM");
        attempt.resetUpstream();
        ObservationReply reply = observationSource.read(sourceRouteId, reservation.keyAlias());
        attempt.reply(reply);
        var normalized = new AtomicBoolean();
        ObservationReply diagnosed = receivedAt -> {
            attempt.stage("NORMALIZE");
            ObservationResponse response = reply.interpret(receivedAt);
            attempt.response(response);
            normalized.set(true);
            attempt.stage("SAVE_AND_COMMIT");
            return response;
        };
        attempt.stage("SAVE_AND_COMMIT");
        ObservationResponse response = WorkerOperationLog.measure("collection_save_and_commit", sourceRouteId, () -> {
            try {
                return batchLedger.conclude(reservation.batchId(), diagnosed, now());
            } catch (RuntimeException failure) {
                if (normalized.get() && isRouteStopMismatch(failure)) {
                    mismatchAfterRollback(new ObservationRouteMismatchDetected(sourceRouteId, routeVersionId));
                }
                throw failure;
            }
        });
        attempt.committed();
        response.observations().filter(rows -> rows.providerRows() == 0 || !rows.storableRows().isEmpty())
            .ifPresent(rows -> WorkerOperationLog.collectionCommitted(sourceRouteId,
                response.receivedAt().toEpochSecond(), rows.storableRows().size()));
        attempt.stage("KEY_EXCLUSION");
        excludeKeyIfRejected(reservation.keyAlias(), response, requestedAt);
        if (response.observations().isPresent()) {
            attempt.stage("COMPLETE");
            attempt.finish("SUCCESS", response.conclusion().outcome().name());
            return;
        }
        attempt.stage("UPSTREAM_RESULT");
        attempt.finish("FAILED", response.conclusion().outcome().name());
    }

    private static boolean isRouteStopMismatch(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var remaining = new ArrayDeque<Throwable>();
        remaining.add(failure);
        while (!remaining.isEmpty()) {
            Throwable current = remaining.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof SQLException sql) {
                if ("23503".equals(sql.getSQLState()) && sql instanceof PSQLException postgres
                        && postgres.getServerErrorMessage() != null
                        && "fk_observation_route_stop".equals(postgres.getServerErrorMessage().getConstraint())) {
                    return true;
                }
                if (sql.getNextException() != null) remaining.addLast(sql.getNextException());
            }
            if (current.getCause() != null) remaining.addLast(current.getCause());
        }
        return false;
    }

    private void mismatchAfterRollback(ObservationRouteMismatchDetected event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) publishMismatch(event);
                    }
                });
            }
            return;
        }
        // conclude()'s transaction interceptor has finished its rollback before reaching this catch.
        publishMismatch(event);
    }

    private void publishMismatch(ObservationRouteMismatchDetected event) {
        try { events.publishEvent(event); }
        catch (RuntimeException notificationFailure) {
            LOGGER.warn("event=observation_route_mismatch_notification_failed routeId={} routeVersionId={} exceptionType={}",
                event.sourceRouteId(), event.routeVersionId(), notificationFailure.getClass().getSimpleName());
        }
    }

    private void excludeKeyIfRejected(
        String keyAlias,
        ObservationResponse response,
        OffsetDateTime requestedAt
    ) {
        response.keyRejectionCode().ifPresent(reasonCode ->
            callQuota.excludeLocationKey(keyAlias, requestedAt).ifPresent(reservedCallsBefore ->
                LOGGER.warn("포털이 GBIS 키를 거절해 그 키를 한국 자정까지 쓰지 않는다. 슬롯={} 사유={} 채우기 전 사용량={}",
                    keyAlias, reasonCode, reservedCallsBefore)));
    }

    /** 같은 초에 계획한 동일 노선의 수집은 같은 배치로 처리한다. */
    private String attemptKeyOf(
        String sourceRouteId,
        OffsetDateTime scheduledAt
    ) {
        return "%s-%s".formatted(sourceRouteId, scheduledAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    private OffsetDateTime now() {
        return clock.instant().atZone(clock.getZone()).toOffsetDateTime();
    }
}
