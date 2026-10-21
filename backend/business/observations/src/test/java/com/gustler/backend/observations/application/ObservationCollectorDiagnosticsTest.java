package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.GatewayRejected;
import com.gustler.backend.gbis.api.GbisLocationResult.GbisSystemError;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.UnreadableResponse;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.observations.domain.ObservationBatchReservation;
import com.gustler.backend.observations.domain.ObservationReply;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationSource;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationSource;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class ObservationCollectorDiagnosticsTest {
    private final CurrentRouteVersion routes = mock(CurrentRouteVersion.class);
    private final ObservationBatchLedger ledger = mock(ObservationBatchLedger.class);
    private final ApiCallQuota quota = mock(ApiCallQuota.class);
    private final ObservationSource source = mock(ObservationSource.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(CollectionAttemptLog.class);
    private ListAppender<ILoggingEvent> logs;
    private final ObservationCollector collector = new ObservationCollector(routes, ledger, quota, source,
        Clock.fixed(Instant.parse("2026-10-02T09:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        when(routes.currentVersionOf(anyString(), any())).thenReturn(Optional.of(new RouteReference(4)));
        when(ledger.reserve(any(), any())).thenReturn(new ObservationBatchReservation(10, true, "a"));
        when(ledger.markDispatching(anyLong(), any(), any(), anyString())).thenReturn(true);
    }

    @AfterEach
    void cleanUp() {
        logger.detachAppender(logs);
        logs.stop();
        MDC.clear();
    }

    @Test
    void 커밋_단계_실패를_성공으로_기록하지_않고_기존_예외를_전파한다() {
        // when
        ObservationReply reply = at -> ObservationResponse.unconfirmed(at);
        when(source.read(anyString(), anyString())).thenReturn(reply);
        RuntimeException failure = new IllegalStateException("commit failed");
        when(ledger.conclude(anyLong(), any(), any())).thenAnswer(call -> {
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2));
            throw failure;
        });

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> collector.collectOnce("234000886"));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isSameAs(failure);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.getFirst().getFormattedMessage())
            .contains("stage=SAVE_AND_COMMIT", "resultCommitConfirmed=false", "batchId=10");
        assertThat(MDC.get("collectionAttemptId")).isNull();
        verifyNoInteractions(quota);
    }

    @Test
    void 정규화_실패와_DB_저장_실패를_구분한다() {
        // when
        when(source.read(anyString(), anyString())).thenReturn(at -> {
            throw new IllegalArgumentException("bad input");
        });
        when(ledger.conclude(anyLong(), any(), any())).thenAnswer(call ->
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2)));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> collector.collectOnce("234000886"));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOf(IllegalArgumentException.class);
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.getFirst()
                .getFormattedMessage()).contains("stage=NORMALIZE", "resultCommitConfirmed=false");
    }

    static Stream<GbisLocationResult> externalFailures() {
        return Stream.of(new NoResponse("private timeout message"),
            new GbisSystemError("time", "private upstream message"),
            new UnreadableResponse("private body"),
            new DailyQuotaExceeded(),
            new GatewayRejected("30", "error", "private rejected message"));
    }

    @ParameterizedTest
    @MethodSource("externalFailures")
    void 외부_실패는_요약과_키_제외까지_WARN이고_ERROR를_남기지_않는다(GbisLocationResult result) {
        // when
        GbisLocationSource location = mock(GbisLocationSource.class);
        when(location.read(anyString(), anyString())).thenReturn(result);
        when(ledger.conclude(anyLong(), any(), any())).thenAnswer(call ->
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2)));
        when(quota.excludeLocationKey(anyString(), any())).thenReturn(Optional.of(10));
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> all = new ListAppender<ILoggingEvent>();
        all.start();
        root.addAppender(all);
        try {
            new ObservationCollector(routes, ledger, quota, new GbisObservationSource(location),
                Clock.systemUTC()).collectOnce("234000886");

            // then
            assertThat(logs.list).hasSize(1);
            assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.WARN);
            assertThat(logs.list.getFirst().getFormattedMessage())
                .contains("status=FAILED", "stage=UPSTREAM_RESULT", "resultCommitConfirmed=true")
                .doesNotContain("private");
            assertThat(all.list).noneMatch(event -> event.getLevel() == Level.ERROR);
        } finally {
            root.detachAppender(all);
            all.stop();
        }
    }

    @Test
    void 상류_호출_중_예상하지_못한_내부_예외는_ERROR를_유지한다() {
        // when
        GbisLocationSource location = mock(GbisLocationSource.class);
        when(location.read(anyString(), anyString())).thenThrow(new IllegalStateException("private"));
        when(ledger.conclude(anyLong(), any(), any())).thenAnswer(call ->
            call.<ObservationReply>getArgument(1).interpret(call.getArgument(2)));
        new ObservationCollector(routes, ledger, quota, new GbisObservationSource(location),
            Clock.systemUTC()).collectOnce("234000886");

        // then
        assertThat(logs.list.getFirst().getLevel()).isEqualTo(Level.ERROR);
        assertThat(logs.list.getFirst().getFormattedMessage())
            .contains("transport=UNEXPECTED_EXCEPTION").doesNotContain("private");
    }
}
