package com.gustler.backend.observations.application;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.domain.ObservationSource;
import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 한 노선의 예약, HTTP 호출, 수집 결과 저장을 순서대로 진행한다. */
@Component
public class ObservationCollector implements com.gustler.backend.observations.api.CollectObservations {

    private static final Logger log = LoggerFactory.getLogger(ObservationCollector.class);

    private final CurrentRouteVersion currentRouteVersion;
    private final ObservationBatchLedger batchLedger;
    private final ApiCallQuota callQuota;
    private final ObservationSource observationSource;
    private final Clock clock;

    public ObservationCollector(
        CurrentRouteVersion currentRouteVersion,
        ObservationBatchLedger batchLedger,
        ApiCallQuota callQuota,
        ObservationSource observationSource,
        Clock clock
    ) {
        this.currentRouteVersion = currentRouteVersion;
        this.batchLedger = batchLedger;
        this.callQuota = callQuota;
        this.observationSource = observationSource;
        this.clock = clock;
    }

    public void collectOnce(
        String sourceRouteId
    ) {
        try (CollectionAttemptLog attempt = new CollectionAttemptLog(sourceRouteId)) {
            OffsetDateTime scheduledAt = now();
            WorkerOperationLog.collectionAttempted(sourceRouteId, scheduledAt.toEpochSecond());
            try {
                attempt.stage("ROUTE_VERSION");
                Optional<RouteReference> reference = WorkerOperationLog.measure("collection_route_version", sourceRouteId,
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
                throw failure;
            }
        }
    }

    private void collectOn(long routeVersionId, String sourceRouteId, OffsetDateTime scheduledAt,
                           CollectionAttemptLog attempt) {
        attempt.stage("RESERVE");
        ObservationBatchReservation reservation = WorkerOperationLog.measure("collection_reserve", sourceRouteId,
            () -> batchLedger.reserve(
                new CollectionPlan(routeVersionId, scheduledAt, attemptKeyOf(sourceRouteId, scheduledAt)), scheduledAt));
        attempt.batch(reservation.batchId());
        if (!reservation.reserved()) {
            WorkerOperationLog.warn("collection_quota", sourceRouteId, "DAILY_LIMIT");
            attempt.finish("DEFERRED", "DAILY_LIMIT");
            return;
        }
        OffsetDateTime requestedAt = now();
        attempt.stage("DISPATCH");
        if (!WorkerOperationLog.measure("collection_dispatch", sourceRouteId,
            () -> batchLedger.markDispatching(reservation.batchId(), scheduledAt, requestedAt, reservation.keyAlias()))) {
            WorkerOperationLog.warn("collection_quota", sourceRouteId, "NEXT_DAY_LIMIT");
            attempt.finish("DEFERRED", "NEXT_DAY_LIMIT");
            return;
        }
        WorkerOperationLog.recovered("collection_quota", sourceRouteId);
        attempt.stage("UPSTREAM");
        attempt.resetUpstream();
        ObservationReply reply = observationSource.read(sourceRouteId, reservation.keyAlias());
        attempt.reply(reply);
        ObservationReply diagnosed = receivedAt -> {
            attempt.stage("NORMALIZE");
            ObservationResponse response = reply.interpret(receivedAt);
            attempt.response(response);
            attempt.stage("SAVE_AND_COMMIT");
            return response;
        };
        attempt.stage("SAVE_AND_COMMIT");
        ObservationResponse response = WorkerOperationLog.measure("collection_save_and_commit", sourceRouteId,
            () -> batchLedger.conclude(reservation.batchId(), diagnosed, now()));
        attempt.committed();
        response.observations().filter(rows -> rows.providerRows() == 0 || !rows.storableRows().isEmpty())
            .ifPresent(rows -> WorkerOperationLog.collectionCommitted(sourceRouteId,
                response.receivedAt().toEpochSecond(), rows.storableRows().size()));
        attempt.stage("KEY_EXCLUSION");
        excludeKeyIfRejected(reservation.keyAlias(), response, requestedAt);
        attempt.stage(response.observations().isPresent() ? "COMPLETE" : "UPSTREAM_RESULT");
        attempt.finish(response.observations().isPresent() ? "SUCCESS" : "FAILED",
            response.conclusion().outcome().name());
    }

    private void excludeKeyIfRejected(
        String keyAlias,
        ObservationResponse response,
        OffsetDateTime requestedAt
    ) {
        response.keyRejectionCode().ifPresent(reasonCode ->
            callQuota.excludeLocationKey(keyAlias, requestedAt).ifPresent(reservedCallsBefore ->
                log.error("포털이 GBIS 키를 거절해 그 키를 한국 자정까지 쓰지 않는다. 슬롯={} 사유={} 채우기 전 사용량={}",
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
