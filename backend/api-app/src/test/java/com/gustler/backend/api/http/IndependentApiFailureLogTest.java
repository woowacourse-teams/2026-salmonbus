package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.api.error.ErrorCode;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

class IndependentApiFailureLogTest {
    @Test
    void 요청_문맥과_예외가_없으면_UNKNOWN을_INFO로_한_번만_기록한다() {
        // when
        List<ILoggingEvent> logs = capture(400, null, null);

        // then
        assertThat(logs).hasSize(1);
        ILoggingEvent log = logs.getFirst();
        assertSoftly(softly -> {
            softly.assertThat(log.getLevel()).isEqualTo(Level.INFO);
            softly.assertThat(log.getFormattedMessage()).contains(
                "endpoint=UNKNOWN", "routeId=UNKNOWN", "exceptionType=UNKNOWN", "rootCause=UNKNOWN",
                "sqlState=UNKNOWN", "source=UNKNOWN", "requestId=audit-request"
            );
            softly.assertThat(log.getThrowableProxy()).isNull();
        });
    }

    @Test
    void 허용되지_않는_경로와_노선값과_SQL상태와_예외메시지는_로그에_싣지_않는다() {
        // given
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/bad?secret=endpoint-marker");
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("routeId", "route-marker"));
        SQLException failure = new SQLException("exception-marker", "state-marker");

        // when
        List<ILoggingEvent> logs = capture(503, failure, request);

        // then
        assertThat(logs).hasSize(1);
        assertSoftly(softly -> {
            softly.assertThat(logs.getFirst().getLevel()).isEqualTo(Level.WARN);
            softly.assertThat(logs.getFirst().getFormattedMessage())
                .contains("endpoint=UNKNOWN", "routeId=UNKNOWN", "sqlState=UNKNOWN", "rootCause=SQLException")
                .doesNotContain("endpoint-marker", "route-marker", "exception-marker", "state-marker");
            softly.assertThat(logs.getFirst().getThrowableProxy()).isNull();
        });
    }

    @Test
    void 트랜잭션_오류는_애플리케이션_원인을_우선하고_중첩된_SQL상태를_보존한다() {
        // given
        TransactionSystemException failure = new TransactionSystemException(
            "transaction-marker", new IllegalStateException("infrastructure-marker")
        );
        failure.initApplicationException(new IllegalArgumentException(
            "application-marker", new SQLException("cause-marker", "08006")
        ));

        // when
        List<ILoggingEvent> logs = capture(503, failure, new MockHttpServletRequest());

        // then
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getFormattedMessage())
            .contains("exceptionType=TransactionSystemException", "rootCause=SQLException", "sqlState=08006")
            .doesNotContain("transaction-marker", "infrastructure-marker", "application-marker", "cause-marker");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void 예외_원인이_순환해도_같은_실패를_한_번만_기록하고_종료한다() {
        // given
        IllegalStateException first = new IllegalStateException("first-marker");
        IllegalArgumentException second = new IllegalArgumentException("second-marker");
        first.initCause(second);
        second.initCause(first);

        // when
        List<ILoggingEvent> logs = capture(500, first, null);

        // then
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getFormattedMessage())
            .contains("exceptionType=IllegalStateException", "rootCause=IllegalArgumentException")
            .doesNotContain("first-marker", "second-marker");
    }

    private static List<ILoggingEvent> capture(
        final int status,
        Throwable failure,
        MockHttpServletRequest request
    ) {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiFailureLog.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        RequestContextHolder.resetRequestAttributes();
        if (request != null) {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        }
        try {
            ApiFailureLog.write(status, ErrorCode.of(HttpStatus.valueOf(status)),
                "audit-request", failure);
            return new ArrayList<>(logs.list);
        } finally {
            RequestContextHolder.resetRequestAttributes();
            logger.detachAppender(logs);
            logs.stop();
        }
    }
}
