package com.gustler.backend.observations.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.observations.domain.CollectionBatchStatus;
import com.gustler.backend.observations.domain.CollectionFailureReason;
import com.gustler.backend.observations.domain.CollectionPreparation;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationClient;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class ObservationCollectorKeyTest {
    private static final String ROUTE_3330 = "204000057";
    private static final long ROUTE_VERSION_ID = 1L;
    private static final long BATCH_ID = 1L;
    private static final String KEY_ALIAS_B = "b";
    private static final Clock KOREA_NOON =
        Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime KOREA_NOON_DISPATCH = OffsetDateTime.parse("2026-08-28T12:00:00+09:00");

    private static final CollectionFailureReason DAILY_QUOTA_EXCEEDED = CollectionFailureReason.DAILY_QUOTA_EXCEEDED;
    private static final CollectionFailureReason PER_SECOND_QUOTA_EXCEEDED =
        CollectionFailureReason.PER_SECOND_QUOTA_EXCEEDED;
    private static final CollectionFailureReason GATEWAY_REJECTED = CollectionFailureReason.UPSTREAM_ERROR;
    private static final int EXCLUDED = 1;
    private static final int NOT_EXCLUDED = 0;
    private static final int RESERVED_BEFORE_764 = 764;

    private final ListAppender<ILoggingEvent> collectorLog = new ListAppender<>();

    @Mock
    private CurrentRouteVersion currentRouteVersion;

    @Mock
    private CollectionBatchService batchLedger;

    @Mock
    private ApiCallQuota callQuota;

    @Mock
    private GbisObservationClient observationSource;

    @AfterEach
    void 로그_수집을_멈춘다() {
        collectorLogger().detachAppender(collectorLog);
    }

    @Test
    void 수집_준비에서_확보한_키로_상류를_부른다() {
        // given
        ObservationCollector collector = collectorReceiving(CollectionResult.unconfirmed(KOREA_NOON_DISPATCH));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(observationSource).should().read(ROUTE_3330, KEY_ALIAS_B);
    }

    @ParameterizedTest
    @MethodSource("responsesAndExclusions")
    void 키_거절_사유가_있는_응답만_보낸_날_장부에서_그_키를_뺀다(CollectionResult response, final int expectedExclusions) {
        // given
        ObservationCollector collector = collectorReceiving(response);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(callQuota).should(times(expectedExclusions)).excludeLocationKey(KEY_ALIAS_B, KOREA_NOON_DISPATCH);
        then(callQuota).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @MethodSource("rejectionsAndReasonCodes")
    void 키를_빼면_슬롯과_사유와_채우기_전_사용량만_경고_로그로_남긴다(CollectionResult response, String reasonCode) {
        // given
        ObservationCollector collector = collectorReceiving(response);
        given(callQuota.excludeLocationKey(KEY_ALIAS_B, KOREA_NOON_DISPATCH))
            .willReturn(Optional.of(RESERVED_BEFORE_764));
        startCapturingCollectorLog();

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertSoftly(checks -> {
            checks.assertThat(messagesAt(Level.WARN)).containsExactly(
                "포털이 GBIS 키를 거절해 그 키를 한국 자정까지 쓰지 않는다. 슬롯=b 사유=" + reasonCode + " 채우기 전 사용량=764");
            checks.assertThat(messagesAt(Level.ERROR)).isEmpty();
        });
    }

    private static Stream<Arguments> rejectionsAndReasonCodes() {
        return Stream.of(
            Arguments.of(CollectionResult.failedByRejectedKey(
                    CollectionBatchStatus.FAILED_UPSTREAM,
                    DAILY_QUOTA_EXCEEDED,
                    null,
                    KOREA_NOON_DISPATCH,
                    "22"
            ),
            "22"),
            Arguments.of(CollectionResult.failedByRejectedKey(
                    CollectionBatchStatus.FAILED_UPSTREAM,
                    GATEWAY_REJECTED,
                    null,
                    KOREA_NOON_DISPATCH,
                    "31"
            ), "31"));
    }

    private static Stream<Arguments> responsesAndExclusions() {
        return Stream.of(
                Arguments.of(CollectionResult.failedByRejectedKey(
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        DAILY_QUOTA_EXCEEDED,
                        null,
                        KOREA_NOON_DISPATCH,
                        "22"
                ),
                    EXCLUDED),
                Arguments.of(CollectionResult.failedByRejectedKey(
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        GATEWAY_REJECTED,
                        null,
                        KOREA_NOON_DISPATCH,
                        "30"
                ),
                    EXCLUDED),
                Arguments.of(CollectionResult.failedByRejectedKey(
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        GATEWAY_REJECTED,
                        null,
                        KOREA_NOON_DISPATCH,
                        "31"
                ),
                    EXCLUDED),
                Arguments.of(CollectionResult.failed(
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        PER_SECOND_QUOTA_EXCEEDED,
                        null,
                        KOREA_NOON_DISPATCH
                ), NOT_EXCLUDED),
                Arguments.of(CollectionResult.failed(
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        GATEWAY_REJECTED,
                        null,
                        KOREA_NOON_DISPATCH
                ), NOT_EXCLUDED),
                Arguments.of(CollectionResult.unconfirmed(KOREA_NOON_DISPATCH), NOT_EXCLUDED)
        );
    }

    private ObservationCollector collectorReceiving(CollectionResult response) {
        given(currentRouteVersion.findCurrentVersion(ROUTE_3330))
            .willReturn(Optional.of(new RouteReference(ROUTE_VERSION_ID)));
        given(batchLedger.prepare(any(), any()))
            .willReturn(new CollectionPreparation(BATCH_ID, true, KEY_ALIAS_B));

        given(observationSource.read(ROUTE_3330, KEY_ALIAS_B)).willReturn(receivedAt -> response);
        given(batchLedger.conclude(eq(BATCH_ID), any(), any())).willReturn(response);
        return new ObservationCollector(
                currentRouteVersion,
                batchLedger,
                callQuota,
                observationSource,
                KOREA_NOON,
                mock(ApplicationEventPublisher.class)
        );
    }

    private void startCapturingCollectorLog() {
        collectorLog.start();
        collectorLogger().addAppender(collectorLog);
    }

    private Logger collectorLogger() {
        return (Logger) LoggerFactory.getLogger(ObservationCollector.class);
    }

    private List<String> messagesAt(Level level) {
        return collectorLog.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
