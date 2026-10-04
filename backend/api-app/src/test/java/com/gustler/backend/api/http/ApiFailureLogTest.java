package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class ApiFailureLogTest {
    @Test void 원인과_응답을_연결하되_요청값과_SQL_본문은_기록하지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiFailureLog.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>(); logs.start(); logger.addAppender(logs);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/routes/234000886/board");
        request.setQueryString("serviceKey=private-key");
        request.setAttribute(RequestId.ATTRIBUTE, "test-request-42");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/routes/{routeId}/board");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("routeId", "234000886"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            var response = new ApiExceptionHandler().handleDatabaseUnavailable(
                new CannotCreateTransactionException("private-key", new SQLException("SELECT private-value", "08006")));
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getFormattedMessage()).contains("event=api_failure", "status=503",
                "routeId=234000886", "requestId=test-request-42", "rootCause=SQLException", "sqlState=08006",
                "endpoint=/api/v1/routes/{routeId}/board").doesNotContain("private-key", "private-value");
            assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
        } finally { RequestContextHolder.resetRequestAttributes(); logger.detachAppender(logs); }
    }
}
