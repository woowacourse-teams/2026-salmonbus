package com.gustler.backend.observations.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.domain.ObservationBatchFailureCode;
import com.gustler.backend.observations.domain.ObservationBatchOutcome;
import com.gustler.backend.observations.domain.ObservationResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class GbisObservationSourceTest {
    private static final String ROUTE_ID = "204000057";
    private static final Instant RECEIVED = Instant.parse("2026-09-23T03:00:00Z");
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final GbisLocationSource source = mock(GbisLocationSource.class);
    private static final OffsetDateTime RECEIVED_AT = RECEIVED.atZone(KOREA).toOffsetDateTime();
    private final GbisObservationSource observations = new GbisObservationSource(source);

    @Test
    void 정상_응답을_업무_관측으로_바꾸고_저장할_수_없는_행을_구분한다() {
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time",
            List.of(bus(1, "stop-1", 43), bus(null, "stop-2", 12))));

        ObservationResponse result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        assertThat(result.conclusion().outcome()).isEqualTo(ObservationBatchOutcome.SUCCESS_ROWS);
        assertThat(result.conclusion().upstreamResultCode()).isZero();
        var collected = result.observations().orElseThrow();
        assertThat(collected.providerRows()).isEqualTo(2);
        assertThat(collected.storableRows()).hasSize(1);
        assertThat(collected.excludedRows()).hasSize(1);
        assertThat(collected.storableRows().getFirst().sourceRowNumber()).isZero();
        assertThat(collected.excludedRows().getFirst().sourceRowNumber()).isEqualTo(1);
    }

    @Test
    void 운행_차량이_없는_응답도_행수가_영인_정상_수집으로_반환한다() {
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.NoVehicles("query time"));

        ObservationResponse result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        assertThat(result.conclusion().outcome()).isEqualTo(ObservationBatchOutcome.SUCCESS_EMPTY);
        assertThat(result.conclusion().upstreamResultCode()).isEqualTo(4);
        assertThat(result.observations().orElseThrow().providerRows()).isZero();
    }

    @ParameterizedTest
    @MethodSource("failedResponses")
    void 외부_실패_유형과_원본_결과_코드를_보존한다(GbisLocationResult response,
        ObservationBatchOutcome outcome, ObservationBatchFailureCode failureCode, Integer resultCode) {
        given(source.read(ROUTE_ID, "a")).willReturn(response);

        ObservationResponse result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        assertThat(result.conclusion().outcome()).isEqualTo(outcome);
        assertThat(result.conclusion().failureCode()).isEqualTo(failureCode);
        assertThat(result.conclusion().upstreamResultCode()).isEqualTo(resultCode);
        assertThat(result.observations()).isEmpty();
    }

    @Test
    void 예상하지_못한_조회_예외는_응답을_확인하지_못한_결과로_반환한다() {
        given(source.read(ROUTE_ID, "a")).willThrow(new IllegalStateException("connection failed"));

        ObservationResponse result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        assertThat(result.conclusion().outcome()).isEqualTo(ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH);
        assertThat(result.observations()).isEmpty();
        assertThat(result.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void 예상하지_못한_조회_예외는_상류_호출_실패로_운영_로그에_남긴다() {
        given(source.read(ROUTE_ID, "a")).willThrow(new IllegalStateException("connection failed"));
        Logger operationLog = (Logger) LoggerFactory.getLogger(WorkerOperationLog.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        operationLog.addAppender(events);

        try {
            observations.read(ROUTE_ID, "a");
        } finally {
            operationLog.detachAppender(events);
        }

        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
            .contains("status=FAILED operation=collection_upstream route=" + ROUTE_ID);
    }

    @Test
    void 정규화_오류는_응답_미수신으로_바꾸지_않고_전파한다() {
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time", null));

        assertThatThrownBy(() -> observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 원본_응답_목록이_바뀌어도_정규화한_결과는_바뀌지_않는다() {
        var rows = new ArrayList<>(List.of(bus(1, "stop-1", 43)));
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time", rows));
        ObservationResponse result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        rows.clear();

        assertThat(result.observations().orElseThrow().storableRows()).hasSize(1);
    }

    private static Stream<Arguments> failedResponses() {
        return Stream.of(
            Arguments.of(new GbisLocationResult.DailyQuotaExceeded(), ObservationBatchOutcome.FAILED_UPSTREAM,
                ObservationBatchFailureCode.DAILY_QUOTA_EXCEEDED, null),
            Arguments.of(new GbisLocationResult.PerSecondQuotaExceeded(), ObservationBatchOutcome.FAILED_UPSTREAM,
                ObservationBatchFailureCode.PER_SECOND_QUOTA_EXCEEDED, null),
            Arguments.of(new GbisLocationResult.GatewayRejected("reason", "error", "message"),
                ObservationBatchOutcome.FAILED_UPSTREAM, ObservationBatchFailureCode.UPSTREAM_ERROR, null),
            Arguments.of(new GbisLocationResult.GbisSystemError("time", "message"),
                ObservationBatchOutcome.FAILED_UPSTREAM, ObservationBatchFailureCode.UPSTREAM_ERROR, 1),
            Arguments.of(new GbisLocationResult.MissingRequiredParameter("time", "message"),
                ObservationBatchOutcome.FAILED_UPSTREAM, ObservationBatchFailureCode.UPSTREAM_ERROR, 2),
            Arguments.of(new GbisLocationResult.UnknownGbisResultCode("time", 99, "message"),
                ObservationBatchOutcome.FAILED_UPSTREAM, ObservationBatchFailureCode.UPSTREAM_ERROR, 99),
            Arguments.of(new GbisLocationResult.NoResponse("message"),
                ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null),
            Arguments.of(new GbisLocationResult.UnreadableResponse("message"),
                ObservationBatchOutcome.FAILED_UNREADABLE, null, null));
    }

    private static BusLocation bus(Integer stopSequence, String stopId, final int seats) {
        return new BusLocation("plate", "vehicle-1", 0, ROUTE_ID, 11, stopId, stopSequence, 2, seats, 3, 1);
    }
}
