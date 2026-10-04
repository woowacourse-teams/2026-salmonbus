package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class CollectionAttemptLogTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(CollectionAttemptLog.class);
    private ListAppender<ILoggingEvent> appender;
    @BeforeEach void setUp() {
        MDC.clear(); appender = new ListAppender<>(); appender.start(); logger.addAppender(appender);
    }
    @AfterEach void cleanUp() { logger.detachAppender(appender); appender.stop(); MDC.clear(); }
    @Test void 원본_노선과_정류장을_연결하고_민감정보를_빼낸다() {
        GbisLocationSource source = mock(GbisLocationSource.class);
        when(source.read("234000886", "a")).thenReturn(new GbisLocationResult.Success("time", List.of(
            new BusLocation("PRIVATE_PLATE", "PRIVATE_VEHICLE", 0, "204000070", 11,
                "206000564", 57, 2, 10, 1, 0))));
        try (var attempt = new CollectionAttemptLog("234000886")) {
            attempt.routeVersion(4); attempt.batch(123);
            var reply = new GbisObservationSource(source).read("234000886", "a");
            attempt.reply(reply); attempt.response(reply.interpret(OffsetDateTime.now()));
            attempt.stage("SAVE_AND_COMMIT");
            var sql = new SQLException("SECRET fk_observation_route_stop "
                + "Key (route_version_id, stop_order, stop_id)=(4, 57, 206000564) is not present", "23503");
            attempt.failed(new IllegalStateException("serviceKey=SECRET", sql));
        }
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.getFirst().getFormattedMessage()).contains(
            "stage=SAVE_AND_COMMIT", "reason=DB_REFERENCE_MISMATCH", "routeId=234000886", "batchId=123",
            "responseRouteId=204000070", "responseStopOrder=57", "responseStopId=206000564",
            "sourceReference=MATCHED", "responseRouteMismatchRows=1", "providerRows=1", "resultCommitConfirmed=false")
            .doesNotContain("SECRET", "PRIVATE_PLATE", "PRIVATE_VEHICLE", "serviceKey");
        assertThat(appender.list.getFirst().getThrowableProxy()).isNull();
        verify(source).read("234000886", "a"); verifyNoMoreInteractions(source);
    }
    @Test void 응답에_없는_저장값은_상류에서_왔다고_단정하지_않는다() {
        try (var attempt = new CollectionAttemptLog("234000886")) {
            attempt.reply(at -> null);
            attempt.failed(new IllegalStateException(new SQLException(
                "fk_observation_route_stop Key (route_version_id, stop_order, stop_id)=(4, 57, 206000564)", "23503")));
        }
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("sourceReference=NOT_FOUND")
            .doesNotContain("responseStopId=");
    }
    @Test void 다른_제약_오류의_내용은_추출하지_않는다() {
        try (var attempt = new CollectionAttemptLog("234000886")) {
            attempt.failed(new IllegalStateException(new SQLException("private_column=SECRET", "23505")));
        }
        assertThat(appender.list.getFirst().getFormattedMessage()).contains("sqlState=23505")
            .doesNotContain("SECRET", "private_column");
    }
    @Test void MDC를_원래대로_복원하고_다음_시도와_구분한다() {
        MDC.put("requestId", "outer"); MDC.put("collectionHttpStatus", "old"); String first;
        try (var attempt = new CollectionAttemptLog("234000886")) {
            first = MDC.get("collectionAttemptId"); assertThat(MDC.get("collectionHttpStatus")).isNull();
            MDC.put("collectionHttpStatus", "503"); attempt.finish("FAILED", "FAILED_UPSTREAM");
        }
        assertThat(MDC.get("requestId")).isEqualTo("outer");
        assertThat(MDC.get("collectionHttpStatus")).isEqualTo("old");
        assertThat(MDC.get("collectionAttemptId")).isNull();
        try (var attempt = new CollectionAttemptLog("234000886")) {
            assertThat(MDC.get("collectionAttemptId")).isNotEqualTo(first);
        }
    }
}
