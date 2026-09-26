package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.collector.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.collector.GbisLocationResult.GatewayRejected;
import com.gustler.backend.collector.GbisLocationResult.PerSecondQuotaExceeded;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
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

@ExtendWith(MockitoExtension.class)
class ObservationCollectorKeyTest {

    private static final String ROUTE_3330 = "204000057";
    private static final long ROUTE_VERSION_ID = 1L;
    private static final long BATCH_ID = 1L;
    private static final String KEY_ALIAS_B = "b";
    private static final Clock KOREA_NOON =
        Clock.fixed(Instant.parse("2026-08-28T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final OffsetDateTime KOREA_NOON_DISPATCH = OffsetDateTime.parse("2026-08-28T12:00:00+09:00");

    private static final String PORTAL_ERROR = "SERVICE ERROR";
    private static final String PORTAL_MESSAGE = "portal rejected";
    private static final int EXCLUDED = 1;
    private static final int NOT_EXCLUDED = 0;
    private static final int RESERVED_BEFORE_764 = 764;

    @Mock
    private RouteCatalogLoader routeCatalogLoader;

    @Mock
    private ObservationBatchLedger batchLedger;

    @Mock
    private CallQuotaLedger callQuotaLedger;

    @Mock
    private GbisLocationSource locationSource;

    private final ListAppender<ILoggingEvent> collectorLog = new ListAppender<>();

    @AfterEach
    void 로그_수집을_멈춘다() {
        collectorLogger().detachAppender(collectorLog);
    }

    @Test
    void 예약한_키로_보내기_직전_자리를_확인하고_상류를_부른다() {
        // given
        ObservationCollector collector =
            new ObservationCollector(routeCatalogLoader, batchLedger, callQuotaLedger, locationSource, KOREA_NOON);
        given(routeCatalogLoader.currentVersionOf(eq(ROUTE_3330), any()))
            .willReturn(OptionalLong.of(ROUTE_VERSION_ID));
        given(batchLedger.reserve(any(), any()))
            .willReturn(new ObservationBatchReservation(BATCH_ID, true, KEY_ALIAS_B));
        given(batchLedger.markDispatching(anyLong(), any(), any(), eq(KEY_ALIAS_B))).willReturn(true);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(locationSource).should().read(ROUTE_3330, KEY_ALIAS_B);
    }

    @ParameterizedTest
    @MethodSource("resultsAndExclusions")
    void 하루_한도_초과나_키_미등록_만료로_거절된_키만_보낸_날_장부에서_뺀다(
        GbisLocationResult result,
        final int expectedExclusions
    ) {
        // given
        ObservationCollector collector = collectorReceiving(result);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(callQuotaLedger).should(times(expectedExclusions))
            .exclude(CallQuota.BUS_LOCATION, KEY_ALIAS_B, KOREA_NOON_DISPATCH);
        then(callQuotaLedger).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @MethodSource("rejectionsAndReasonCodes")
    void 키를_빼면_슬롯과_사유와_채우기_전_사용량만_오류_로그로_남긴다(
        GbisLocationResult result,
        String reasonCode
    ) {
        // given
        ObservationCollector collector = collectorReceiving(result);
        given(callQuotaLedger.exclude(CallQuota.BUS_LOCATION, KEY_ALIAS_B, KOREA_NOON_DISPATCH))
            .willReturn(Optional.of(RESERVED_BEFORE_764));
        startCapturingCollectorLog();

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(errorMessages()).containsExactly(
            "포털이 GBIS 키를 거절해 그 키를 한국 자정까지 쓰지 않는다. 슬롯=b 사유=" + reasonCode + " 채우기 전 사용량=764");
    }

    private static Stream<Arguments> rejectionsAndReasonCodes() {
        return Stream.of(
            Arguments.of(new DailyQuotaExceeded(), "22"),
            Arguments.of(new GatewayRejected("31", PORTAL_ERROR, PORTAL_MESSAGE), "31"));
    }

    private static Stream<Arguments> resultsAndExclusions() {
        return Stream.of(
            Arguments.of(new DailyQuotaExceeded(), EXCLUDED),
            Arguments.of(new GatewayRejected("30", PORTAL_ERROR, PORTAL_MESSAGE), EXCLUDED),
            Arguments.of(new GatewayRejected("31", PORTAL_ERROR, PORTAL_MESSAGE), EXCLUDED),
            Arguments.of(new PerSecondQuotaExceeded(), NOT_EXCLUDED),
            Arguments.of(new GatewayRejected("20", PORTAL_ERROR, PORTAL_MESSAGE), NOT_EXCLUDED),
            Arguments.of(new GatewayRejected(null, PORTAL_ERROR, PORTAL_MESSAGE), NOT_EXCLUDED));
    }

    private ObservationCollector collectorReceiving(
        GbisLocationResult result
    ) {
        given(routeCatalogLoader.currentVersionOf(eq(ROUTE_3330), any()))
            .willReturn(OptionalLong.of(ROUTE_VERSION_ID));
        given(batchLedger.reserve(any(), any()))
            .willReturn(new ObservationBatchReservation(BATCH_ID, true, KEY_ALIAS_B));
        given(batchLedger.markDispatching(anyLong(), any(), any(), eq(KEY_ALIAS_B))).willReturn(true);
        given(locationSource.read(ROUTE_3330, KEY_ALIAS_B)).willReturn(result);
        return new ObservationCollector(routeCatalogLoader, batchLedger, callQuotaLedger, locationSource, KOREA_NOON);
    }

    private void startCapturingCollectorLog() {
        collectorLog.start();
        collectorLogger().addAppender(collectorLog);
    }

    private Logger collectorLogger() {
        return (Logger) LoggerFactory.getLogger(ObservationCollector.class);
    }

    private List<String> errorMessages() {
        return collectorLog.list.stream()
            .filter(event -> event.getLevel() == Level.ERROR)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
