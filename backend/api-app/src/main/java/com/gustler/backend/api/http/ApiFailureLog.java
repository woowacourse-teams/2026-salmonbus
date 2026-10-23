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
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

/** 실패한 요청을 한 줄로 기록한다. URL, 헤더, SQL, 바인딩 값, 예외 메시지는 기록하지 않는다. */
final class ApiFailureLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiFailureLog.class);

    private ApiFailureLog() {
    }

    static void write(
        final int status,
        ErrorCode code,
        String requestId,
        Throwable failure
    ) {
        HttpServletRequest request = currentRequest();
        Object pattern = attribute(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object variables = attribute(request, HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        Object route = routeId(variables);
        String endpoint = endpoint(pattern);
        String routeId = safeRouteId(route);
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable primary = primaryFailure(failure);
        Throwable root = primary;
        String sqlState = "UNKNOWN";
        for (Throwable cause = primary; cause != null; cause = cause.getCause()) {
            if (seen.size() >= 32 || !seen.add(cause)) {
                break;
            }

            root = cause;
            String reportableSqlState = reportableSqlState(cause);
            if (reportableSqlState != null) {
                sqlState = reportableSqlState;
            }
        }

        String type = exceptionType(failure);
        String rootType = exceptionType(root);
        String source = "UNKNOWN";
        if (root != null) {
            for (StackTraceElement frame : root.getStackTrace()) {
                String className = frame.getClassName();
                if (className.startsWith("com.gustler.backend.")) {
                    source = className + "." + frame.getMethodName() + ":" + frame.getLineNumber();
                    break;
                }
            }
        }

        String format =
            "event=api_failure status={} errorCode={} endpoint={} routeId={} requestId={} exceptionType={} rootCause={} sqlState={} source={}";
        if (status >= 500) {
            LOGGER.warn(format, status, code.name(), endpoint, routeId, requestId, type, rootType, sqlState, source);
            return;
        }
        LOGGER.info(format, status, code.name(), endpoint, routeId, requestId, type, rootType, sqlState, source);
    }

    private static String reportableSqlState(Throwable cause) {
        if (!(cause instanceof SQLException sql)) {
            return null;
        }
        String sqlState = sql.getSQLState();
        if (sqlState == null || !sqlState.matches("[A-Z0-9]{5}")) {
            return null;
        }
        return sqlState;
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private static Object attribute(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        return request.getAttribute(name);
    }

    private static Object routeId(Object variables) {
        if (variables instanceof Map<?, ?> map) {
            return map.get("routeId");
        }
        return null;
    }

    private static String endpoint(Object pattern) {
        if (pattern == null) {
            return "UNKNOWN";
        }
        String endpoint = pattern.toString();
        if (!endpoint.matches("[/a-zA-Z0-9_{}.*-]{1,160}")) {
            return "UNKNOWN";
        }
        return endpoint;
    }

    private static String safeRouteId(Object route) {
        if (route == null) {
            return "UNKNOWN";
        }
        String routeId = route.toString();
        if (routeId.matches("[0-9]{9}")) {
            return routeId;
        }
        return "UNKNOWN";
    }

    private static Throwable primaryFailure(Throwable failure) {
        if (failure instanceof TransactionSystemException transactionFailure
                && transactionFailure.getApplicationException() != null) {
            return transactionFailure.getApplicationException();
        }
        return failure;
    }

    private static String exceptionType(Throwable failure) {
        if (failure == null) {
            return "UNKNOWN";
        }
        return failure.getClass().getSimpleName();
    }
}
