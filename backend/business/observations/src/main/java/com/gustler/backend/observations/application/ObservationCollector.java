package com.gustler.backend.observations.application;

import com.gustler.backend.observations.domain.CollectionPlan;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationClient;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 한 노선의 판본 확인, 한도 확보, HTTP 조회, 결과 저장을 순서대로 진행한다. */
@Component
public class ObservationCollector {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    private final CurrentRouteVersion currentRouteVersion;
    private final CollectionBatchService batchService;
    private final ApiCallQuota callQuota;
    private final GbisObservationClient observationClient;
    private final Clock clock;
    private final RouteCatalogRefreshRequests refreshRequests;

    public ObservationCollector(
        CurrentRouteVersion currentRouteVersion,
        CollectionBatchService batchService,
        ApiCallQuota callQuota,
        GbisObservationClient observationClient,
        Clock clock,
        ApplicationEventPublisher eventPublisher
    ) {
        this.currentRouteVersion = currentRouteVersion;
        this.batchService = batchService;
        this.callQuota = callQuota;
        this.observationClient = observationClient;
        this.clock = clock;
        this.refreshRequests = new RouteCatalogRefreshRequests(eventPublisher);
    }

    /** 미준비 노선은 별도 준비를 요청하고 건너뛴다. 실패한 수집은 기록을 종료한 뒤 원래 예외를 전파한다. */
    public void collectOnce(String sourceRouteId) {
        new CollectionAttempt(sourceRouteId).run();
    }

    /** 노선 ID와 진단 상태를 한 호출에 한정한다. 동시에 수집해도 다른 호출의 상태를 공유하지 않는다. */
    private final class CollectionAttempt {

        private final String sourceRouteId;
        private final CollectionAttemptLog diagnostics;

        private CollectionAttempt(String sourceRouteId) {
            this.sourceRouteId = sourceRouteId;
            this.diagnostics = new CollectionAttemptLog(sourceRouteId);
        }

        private void run() {
            try (diagnostics) {
                OffsetDateTime scheduledAt = now();
                diagnostics.collectionStarted(scheduledAt);

                try {
                    collect(scheduledAt).ifPresent(diagnostics::collectionFinished);
                } catch (RuntimeException collectionFailure) {
                    diagnostics.failed(collectionFailure);
                    diagnostics.closeFailedBatch(batchService::closeUnfinished);
                    throw collectionFailure;
                }
            }
        }

        private Optional<CollectionResult> collect(OffsetDateTime scheduledAt) {
            Optional<RouteReference> routeVersion = findRouteVersion();

            if (routeVersion.isEmpty()) {
                return Optional.empty();
            }

            final long routeVersionId = routeVersion.orElseThrow().routeVersionId();
            OffsetDateTime preparedAt = now();
            CollectionPreparation preparation = prepareCollection(routeVersionId, scheduledAt, preparedAt);

            if (!hasReservedQuota(preparation)) {
                return Optional.empty();
            }

            OffsetDateTime dispatchAt = now();

            if (!reserveForDispatch(preparation, preparedAt, dispatchAt)) {
                return Optional.empty();
            }

            ObservationResponse upstreamResponse = readObservations(preparation.keyAlias());
            CollectionResult collectionResult = saveResponse(preparation.batchId(), routeVersionId, upstreamResponse);

            excludeRejectedKey(preparation.keyAlias(), collectionResult, dispatchAt);
            return Optional.of(collectionResult);
        }

        private Optional<RouteReference> findRouteVersion() {
            Optional<RouteReference> routeVersion = diagnostics.measureRouteVersion(
                () -> currentRouteVersion.findCurrentVersion(sourceRouteId));

            if (routeVersion.isEmpty()) {
                refreshRequests.requestMissingRoute(sourceRouteId);
                diagnostics.routeVersionMissing();
                return routeVersion;
            }

            diagnostics.routeVersionFound(routeVersion.orElseThrow().routeVersionId());
            return routeVersion;
        }

        private CollectionPreparation prepareCollection(
            final long routeVersionId,
            OffsetDateTime scheduledAt,
            OffsetDateTime preparedAt
        ) {
            return diagnostics.measurePreparation(() -> batchService.prepare(
                new CollectionPlan(routeVersionId, scheduledAt, attemptKeyOf(sourceRouteId, scheduledAt)), preparedAt));
        }

        private boolean hasReservedQuota(CollectionPreparation preparation) {
            if (preparation.reserved()) {
                return true;
            }

            diagnostics.quotaUnavailable("DAILY_LIMIT");
            return false;
        }

        private boolean reserveForDispatch(
            CollectionPreparation preparation,
            OffsetDateTime preparedAt,
            OffsetDateTime dispatchAt
        ) {
            if (!reserveNewDayIfNeeded(preparation, preparedAt, dispatchAt)) {
                diagnostics.quotaUnavailable("NEXT_DAY_LIMIT");
                return false;
            }

            diagnostics.quotaAvailable();
            return true;
        }

        private ObservationResponse readObservations(String keyAlias) {
            return diagnostics.measureUpstream(() -> observationClient.read(sourceRouteId, keyAlias));
        }

        private CollectionResult saveResponse(
            final long batchId,
            final long routeVersionId,
            ObservationResponse upstreamResponse
        ) {
            CollectionResult collectionResult = diagnostics.measureSave(upstreamResponse, responseWithDiagnostics -> {
                try {
                    return batchService.conclude(batchId, responseWithDiagnostics, now());
                } catch (RuntimeException saveFailure) {
                    if (diagnostics.hasInterpretedResponse()) {
                        refreshRequests.requestAfterFailedSave(sourceRouteId, routeVersionId, saveFailure);
                    }

                    throw saveFailure;
                }
            });

            diagnostics.observationsCommitted(collectionResult);
            return collectionResult;
        }

        private void excludeRejectedKey(String keyAlias, CollectionResult collectionResult, OffsetDateTime dispatchAt) {
            collectionResult.keyRejectionCode().ifPresent(reasonCode ->
                callQuota.excludeLocationKey(keyAlias, dispatchAt).ifPresent(reservedCallsBefore ->
                    diagnostics.keyRejected(keyAlias, reasonCode, reservedCallsBefore)));
        }
    }

    /** 평소에는 날짜만 비교한다. 준비 직후 한국 자정을 넘었을 때만 새 날짜의 한도를 확보한다. */
    private boolean reserveNewDayIfNeeded(
        CollectionPreparation preparation,
        OffsetDateTime preparedAt,
        OffsetDateTime dispatchAt
    ) {
        if (preparedAt.atZoneSameInstant(KOREA).toLocalDate()
            .equals(dispatchAt.atZoneSameInstant(KOREA).toLocalDate())) {
            return true;
        }

        if (callQuota.ensureLocationReservation(preparation.keyAlias(), preparedAt, dispatchAt)) {
            return true;
        }

        batchService.abandonBeforeSend(preparation.batchId());
        return false;
    }

    /** 같은 초에 계획한 동일 노선의 수집은 같은 배치로 처리한다. */
    private String attemptKeyOf(String sourceRouteId, OffsetDateTime scheduledAt) {
        return "%s-%s".formatted(sourceRouteId, scheduledAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    private OffsetDateTime now() {
        return clock.instant().atZone(clock.getZone()).toOffsetDateTime();
    }
}
