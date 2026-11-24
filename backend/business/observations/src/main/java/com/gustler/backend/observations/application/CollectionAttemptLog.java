package com.gustler.backend.observations.application;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationRows;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongToIntFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 수집 시도당 요약 한 줄. 응답 본문, URL, 키, 예외 메시지는 기록하지 않는다. */
final class CollectionAttemptLog implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionAttemptLog.class);
    private static final Logger COLLECTION_LOGGER = LoggerFactory.getLogger(ObservationCollector.class);

    /** 알려진 정류장 참조 오류의 숫자 필드만 추출한다. 그 밖의 SQL 상세는 기록하지 않는다. */
    private static final Pattern STOP_REFERENCE = Pattern.compile(
        "Key \\(route_version_id, stop_order, stop_id\\)=\\(([0-9]{1,19}), ([0-9]{1,9}), ([0-9]{1,20})\\)");

    private final Map<String, String> previousContext = MDC.getCopyOfContextMap();
    private final String attemptId = UUID.randomUUID().toString();
    private final String routeId;
    private final long startedAtNanos = System.nanoTime();

    private String stage = "START";
    private long routeVersionId = -1;
    private long batchId = -1;
    private CollectionResult collectionResult;
    private boolean responseInterpreted;
    private boolean committed;
    private ObservationResponse upstreamResponse;

    CollectionAttemptLog(String routeId) {
        this.routeId = routeId;
        MDC.put("collectionAttemptId", attemptId);
        resetUpstream();
    }

    void resetUpstream() {
        for (
            String key : new String[]{"collectionHttpStatus", "collectionTransport", "collectionUpstreamResult",
                "collectionRouteMismatchRows", "collectionTransportCause"}) {
            MDC.remove(key);
        }
    }

    void recordUpstreamResponse(ObservationResponse upstreamResponse) {
        this.upstreamResponse = upstreamResponse;
    }

    void stage(String stage) {
        this.stage = stage;
    }

    void recordRouteVersionId(final long routeVersionId) {
        this.routeVersionId = routeVersionId;
    }

    void recordBatchId(final long batchId) {
        this.batchId = batchId;
    }

    void recordCollectionResult(CollectionResult collectionResult) {
        this.collectionResult = collectionResult;
    }

    void collectionStarted(OffsetDateTime scheduledAt) {
        WorkerOperationLog.collectionAttempted(routeId, scheduledAt.toEpochSecond());
    }

    <T> T measureRouteVersion(Supplier<T> lookup) {
        stage("ROUTE_VERSION");
        return WorkerOperationLog.measure("collection_route_version", routeId, lookup);
    }

    void routeVersionMissing() {
        WorkerOperationLog.warn("collection_route_version", routeId, "NO_ROUTE_VERSION");
        finish("DEFERRED", "NO_ROUTE_VERSION");
    }

    void routeVersionFound(final long routeVersionId) {
        WorkerOperationLog.recovered("collection_route_version", routeId);
        recordRouteVersionId(routeVersionId);
    }

    CollectionPreparation measurePreparation(Supplier<CollectionPreparation> prepare) {
        stage("RESERVE");
        CollectionPreparation preparation = WorkerOperationLog.measure("collection_reserve", routeId, prepare);
        recordBatchId(preparation.batchId());

        return preparation;
    }

    void quotaUnavailable(String reason) {
        WorkerOperationLog.warn("collection_quota", routeId, reason);
        finish("DEFERRED", reason);
    }

    void quotaAvailable() {
        WorkerOperationLog.recovered("collection_quota", routeId);
    }

    ObservationResponse measureUpstream(Supplier<ObservationResponse> request) {
        stage("UPSTREAM");
        resetUpstream();
        ObservationResponse receivedResponse = request.get();
        recordUpstreamResponse(receivedResponse);

        return receivedResponse;
    }

    /** 응답 해석은 저장 측에서 트랜잭션 안에서 호출할 때까지 실행하지 않는다. */
    CollectionResult measureSave(
        ObservationResponse receivedResponse,
        Function<ObservationResponse, CollectionResult> save
    ) {
        ObservationResponse responseWithDiagnostics = receivedAt -> {
            stage("NORMALIZE");
            CollectionResult interpretedResult = receivedResponse.interpret(receivedAt);
            recordCollectionResult(interpretedResult);

            responseInterpreted = true;
            stage("SAVE_AND_COMMIT");
            return interpretedResult;
        };

        stage("SAVE_AND_COMMIT");
        CollectionResult savedResult = WorkerOperationLog.measure("collection_save_and_commit", routeId,
            () -> save.apply(responseWithDiagnostics));
        committed = true;

        return savedResult;
    }

    boolean hasInterpretedResponse() {
        return responseInterpreted;
    }

    void observationsCommitted(CollectionResult savedResult) {
        savedResult.observations()
            .filter(rows -> !rows.isFullyExcluded())
            .ifPresent(rows -> WorkerOperationLog.collectionCommitted(routeId,
                savedResult.receivedAt().toEpochSecond(), rows.storableRowCount()));

        stage("KEY_EXCLUSION");
    }

    void collectionFinished(CollectionResult savedResult) {
        if (savedResult.observations().isPresent()) {
            stage("COMPLETE");
            finish("SUCCESS", savedResult.outcome().name());
            return;
        }

        stage("UPSTREAM_RESULT");
        finish("FAILED", savedResult.outcome().name());
    }

    void closeFailedBatch(LongToIntFunction closeBatch) {
        if (batchId < 1) {
            return;
        }

        try {
            final int closedBatchCount = WorkerOperationLog.measure("collection_failure_close", routeId,
                () -> closeBatch.applyAsInt(batchId));

            if (closedBatchCount > 0) {
                COLLECTION_LOGGER.warn("event=collection_batch_closed reason=COLLECTION_FAILED batchId={} routeId={}",
                    batchId, routeId);
            }
        } catch (RuntimeException closeFailure) {
            COLLECTION_LOGGER.error(
                    "event=collection_batch_close_failed batchId={} routeId={} exceptionType={}",
                    batchId,
                    routeId,
                    closeFailure.getClass().getSimpleName()
            );
        }
    }

    void keyRejected(String keyAlias, String reasonCode, final int reservedCallsBefore) {
        COLLECTION_LOGGER.warn(
                "포털이 GBIS 키를 거절해 그 키를 한국 자정까지 쓰지 않는다. 슬롯={} 사유={} 채우기 전 사용량={}",
                keyAlias,
                reasonCode,
                reservedCallsBefore
        );
    }

    void failed(RuntimeException failure) {
        Set<Throwable> visitedCauses = Collections.newSetFromMap(new IdentityHashMap<>());
        String sqlState = "UNKNOWN";
        String stopReferenceDetails = "";
        Throwable rootCause = failure;

        for (Throwable cause = failure; cause != null && visitedCauses.add(cause); cause = cause.getCause()) {
            rootCause = cause;
            if (cause instanceof SQLException sqlFailure) {
                String currentSqlState = sqlFailure.getSQLState();
                String databaseMessage = sqlFailure.getMessage();

                if (currentSqlState != null) {
                    sqlState = safe(currentSqlState);
                }
                if (isStopReferenceViolation(currentSqlState, databaseMessage)) {
                    Matcher stopReferenceMatch = STOP_REFERENCE.matcher(databaseMessage);
                    if (stopReferenceMatch.find()) {
                        stopReferenceDetails = " referenceRouteVersionId=" + stopReferenceMatch.group(1)
                            + " stopOrder=" + stopReferenceMatch.group(2)
                            + " stopId=" + stopReferenceMatch.group(3)
                            + sourceEvidence(
                                Integer.parseInt(stopReferenceMatch.group(2)), stopReferenceMatch.group(3));
                    }
                }
            }
        }

        String reason = exceptionReason(sqlState);

        LOGGER.error(
                "{} sqlState={} exceptionType={} rootCause={}{}",
                summary("FAILED", reason),
                sqlState,
                failure.getClass().getSimpleName(),
                rootCause.getClass().getSimpleName(),
                stopReferenceDetails
        );
    }

    private boolean isStopReferenceViolation(String sqlState, String databaseMessage) {
        if (!"23503".equals(sqlState) || databaseMessage == null) {
            return false;
        }
        return databaseMessage.contains("fk_observation_route_stop");
    }

    private String exceptionReason(String sqlState) {
        if ("23503".equals(sqlState)) {
            return "DB_REFERENCE_MISMATCH";
        }
        return stage + "_EXCEPTION";
    }

    void finish(String status, String reason) {
        if ("FAILED".equals(status)) {
            finishFailure(status, reason);
            return;
        }

        if (!"0".equals(MDC.get("collectionRouteMismatchRows"))
                && MDC.get("collectionRouteMismatchRows") != null) {
            LOGGER.warn("{}", summary(status, reason));
            return;
        }

        LOGGER.debug("{}", summary(status, reason));
    }

    /** 분류된 외부 실패는 갱신 중단 알림으로 감지한다. 예상하지 못한 내부 예외는 ERROR를 유지한다. */
    private void finishFailure(String status, String reason) {
        if ("UPSTREAM_RESULT".equals(stage)
                && !"UNEXPECTED_EXCEPTION".equals(MDC.get("collectionTransport"))) {
            LOGGER.warn("{}", summary(status, reason));
            return;
        }

        LOGGER.error("{}", summary(status, reason));
    }

    private String sourceEvidence(final int stopOrder, String stopId) {
        if (upstreamResponse == null) {
            return " sourceReference=UNAVAILABLE";
        }
        return upstreamResponse.sourceReference(stopOrder, stopId).map(source ->
                " sourceReference=MATCHED sourceRowNumber=" + source.sourceRowNumber()
                + " responseRouteId=" + safe(source.routeId())
                + " responseStopOrder=" + source.stopOrder() + " responseStopId=" + safe(source.stopId()))
            .orElse(" sourceReference=NOT_FOUND");
    }

    private String summary(String status, String reason) {
        String loggedRouteId = safe(routeId);
        return "event=collection_attempt status=" + status + " attemptId=" + attemptId + " routeId=" + loggedRouteId
            + " routeVersionId=" + routeVersionId + " batchId=" + batchId + " stage=" + stage
            + " reason=" + safe(reason) + " durationMs=" + (System.nanoTime() - startedAtNanos) / 1_000_000
            + " httpStatus=" + safe(MDC.get("collectionHttpStatus"))
            + " transport=" + safe(MDC.get("collectionTransport"))
            + " transportCause=" + safe(MDC.get("collectionTransportCause"))
            + " upstreamResult=" + safe(MDC.get("collectionUpstreamResult"))
            + " responseRouteMismatchRows=" + safe(MDC.get("collectionRouteMismatchRows"))
            + responseSummary()
            + " resultCommitConfirmed=" + committed
            + WorkerOperationLog.routeContext("collection_attempt", loggedRouteId);
    }

    private String responseSummary() {
        if (collectionResult == null) {
            return " outcome=NOT_INTERPRETED failureCode=UNKNOWN resultCode=UNKNOWN"
                + " providerRows=-1 candidateRows=-1 excludedRows=-1";
        }
        return " outcome=" + collectionResult.outcome().name()
            + " failureCode=" + collectionResult.failureCode() + " resultCode=" + collectionResult.upstreamResultCode()
            + collectionResult.observations().map(this::rowSummary)
                .orElse(" providerRows=-1 candidateRows=-1 excludedRows=-1");
    }

    private String rowSummary(ObservationRows rows) {
        return " providerRows=" + rows.providerRows() + " candidateRows=" + rows.storableRowCount()
            + " excludedRows=" + rows.excludedRowCount();
    }

    private static String safe(String value) {
        if (value != null && value.matches("[a-zA-Z0-9_.-]{1,80}")) {
            return value;
        }
        return "UNKNOWN";
    }

    @Override
    public void close() {
        if (previousContext == null) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(previousContext);
    }
}
