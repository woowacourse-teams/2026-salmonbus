package com.gustler.backend.observations.application;

import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 수집 시도당 요약 한 줄. 응답 본문, URL, 키, 예외 메시지는 기록하지 않는다. */
final class CollectionAttemptLog implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(CollectionAttemptLog.class);
    // 알려진 정류장 참조 오류의 숫자 필드만 추출한다. 그 밖의 SQL 상세는 기록하지 않는다.
    private static final Pattern STOP_REFERENCE = Pattern.compile(
        "Key \\(route_version_id, stop_order, stop_id\\)=\\(([0-9]{1,19}), ([0-9]{1,9}), ([0-9]{1,20})\\)");
    private final Map<String, String> previous = MDC.getCopyOfContextMap();
    private final String attemptId = UUID.randomUUID().toString();
    private final String routeId;
    private final long startedAtNanos = System.nanoTime();
    private String stage = "START";
    private long routeVersionId = -1;
    private long batchId = -1;
    private String outcome = "NOT_INTERPRETED";
    private String failureCode = "UNKNOWN";
    private String resultCode = "UNKNOWN";
    private int providerRows = -1;
    private int storedCandidates = -1;
    private int excludedRows = -1;
    private boolean committed;
    private ObservationReply reply;

    CollectionAttemptLog(String routeId) {
        this.routeId = safe(routeId);
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

    void reply(ObservationReply reply) {
        this.reply = reply;
    }

    void stage(String stage) {
        this.stage = stage;
    }

    void routeVersion(final long value) {
        routeVersionId = value;
    }

    void batch(final long value) {
        batchId = value;
    }

    void committed() {
        committed = true;
    }

    void response(ObservationResponse response) {
        outcome = response.conclusion().outcome().name();
        failureCode = String.valueOf(response.conclusion().failureCode());
        resultCode = String.valueOf(response.conclusion().upstreamResultCode());
        response.observations().ifPresent(rows -> {
            providerRows = rows.providerRows();
            storedCandidates = rows.storableRows().size();
            excludedRows = rows.excludedRows().size();
        });
    }

    void failed(RuntimeException failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        String sqlState = "UNKNOWN";
        String stop = "";
        Throwable root = failure;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            root = cause;
            if (cause instanceof SQLException sql) {
                String currentSqlState = sql.getSQLState();
                String message = sql.getMessage();
                if (currentSqlState != null) {
                    sqlState = safe(currentSqlState);
                }
                if (isStopReferenceViolation(currentSqlState, message)) {
                    Matcher match = STOP_REFERENCE.matcher(message);
                    if (match.find()) {
                        stop = " referenceRouteVersionId=" + match.group(1) + " stopOrder=" + match.group(2)
                            + " stopId=" + match.group(3)
                            + sourceEvidence(Integer.parseInt(match.group(2)), match.group(3));
                    }
                }
            }
        }
        String reason = exceptionReason(sqlState);
        LOGGER.error("{} sqlState={} exceptionType={} rootCause={}{}", summary("FAILED", reason), sqlState,
            failure.getClass().getSimpleName(), root.getClass().getSimpleName(), stop);
    }

    private boolean isStopReferenceViolation(String sqlState, String message) {
        if (!"23503".equals(sqlState) || message == null) {
            return false;
        }
        return message.contains("fk_observation_route_stop");
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

    private void finishFailure(String status, String reason) {
        // 분류된 외부 실패는 갱신 중단 알림으로 감지한다. 예상하지 못한 내부 예외는 ERROR를 유지한다.
        if ("UPSTREAM_RESULT".equals(stage)
                && !"UNEXPECTED_EXCEPTION".equals(MDC.get("collectionTransport"))) {
            LOGGER.warn("{}", summary(status, reason));
            return;
        }
        LOGGER.error("{}", summary(status, reason));
    }

    private String sourceEvidence(final int stopOrder, String stopId) {
        if (reply == null) {
            return " sourceReference=UNAVAILABLE";
        }
        return reply.sourceReference(stopOrder, stopId).map(source ->
                " sourceReference=MATCHED sourceRowNumber=" + source.sourceRowNumber()
                + " responseRouteId=" + safe(source.routeId())
                + " responseStopOrder=" + source.stopOrder() + " responseStopId=" + safe(source.stopId()))
            .orElse(" sourceReference=NOT_FOUND");
    }

    private String summary(String status, String reason) {
        return "event=collection_attempt status=" + status + " attemptId=" + attemptId + " routeId=" + routeId
            + " routeVersionId=" + routeVersionId + " batchId=" + batchId + " stage=" + stage
            + " reason=" + safe(reason) + " durationMs=" + (System.nanoTime() - startedAtNanos) / 1_000_000
            + " httpStatus=" + safe(MDC.get("collectionHttpStatus"))
            + " transport=" + safe(MDC.get("collectionTransport"))
            + " transportCause=" + safe(MDC.get("collectionTransportCause"))
            + " upstreamResult=" + safe(MDC.get("collectionUpstreamResult"))
            + " responseRouteMismatchRows=" + safe(MDC.get("collectionRouteMismatchRows"))
            + " outcome=" + outcome + " failureCode=" + failureCode + " resultCode=" + resultCode
            + " providerRows=" + providerRows + " candidateRows=" + storedCandidates + " excludedRows=" + excludedRows
            + " resultCommitConfirmed=" + committed
            + WorkerOperationLog.routeContext("collection_attempt", routeId);
    }

    private static String safe(String value) {
        if (value != null && value.matches("[a-zA-Z0-9_.-]{1,80}")) {
            return value;
        }
        return "UNKNOWN";
    }

    @Override
    public void close() {
        if (previous == null) {
            MDC.clear();
            return;
        }
        MDC.setContextMap(previous);
    }
}
