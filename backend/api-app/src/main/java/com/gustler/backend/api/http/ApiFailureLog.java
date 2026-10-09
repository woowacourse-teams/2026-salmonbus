package com.gustler.backend.api.http;

import com.gustler.backend.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** 실패 요청 한 줄. URL, 헤더, SQL, 바인딩 값, 예외 메시지는 기록하지 않는다. */
final class ApiFailureLog {
    private static final Logger log = LoggerFactory.getLogger(ApiFailureLog.class);

    private ApiFailureLog() { }

    static void write(int status, ErrorCode code, String requestId, Throwable failure) {
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a
            ? a.getRequest() : null;
        Object pattern = request == null ? null : request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object variables = request == null ? null : request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object route = variables instanceof Map<?, ?> map ? map.get("routeId") : null;
        String endpoint = pattern == null ? "UNKNOWN" : pattern.toString();
        if (!endpoint.matches("[/a-zA-Z0-9_{}.*-]{1,160}")) { endpoint = "UNKNOWN"; }
        String routeId = route != null && route.toString().matches("[0-9]{9}") ? route.toString() : "UNKNOWN";
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable primary = failure instanceof org.springframework.transaction.TransactionSystemException tx
            && tx.getApplicationException() != null ? tx.getApplicationException() : failure;
        Throwable root = primary;
        String sqlState = "UNKNOWN";
        for (Throwable cause = primary; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            root = cause;
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) {
                sqlState = sql.getSQLState();
            }
        }
        String type = failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
        String rootType = root == null ? "UNKNOWN" : root.getClass().getSimpleName();
        String source = "UNKNOWN";
        if (root != null) {
            for (StackTraceElement frame : root.getStackTrace()) {
                if (frame.getClassName().startsWith("com.gustler.backend.")) {
                    source = frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    break;
                }
            }
        }
        String format = "event=api_failure status={} errorCode={} endpoint={} routeId={} requestId={} exceptionType={} rootCause={} sqlState={} source={}";
        if (status >= 500) { log.warn(format, status, code.name(), endpoint, routeId, requestId, type, rootType, sqlState, source); }
        else { log.info(format, status, code.name(), endpoint, routeId, requestId, type, rootType, sqlState, source); }
    }
}
