package com.gustler.backend.observations.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.domain.CollectionBatchStatus;
import com.gustler.backend.observations.domain.CollectionFailureReason;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationResponse;
import com.gustler.backend.observations.domain.ObservationRows;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

class GbisObservationClientTest {

    private static final String ROUTE_ID = "204000057";
    private static final Instant RECEIVED = Instant.parse("2026-09-23T03:00:00Z");
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final OffsetDateTime RECEIVED_AT = RECEIVED.atZone(KOREA).toOffsetDateTime();

    private final GbisLocationSource source = mock(GbisLocationSource.class);
    private final GbisObservationClient observations = new GbisObservationClient(source);

    @Test
    void 정상_응답을_업무_관측으로_바꾸고_저장할_수_없는_행을_구분한다() {
        // given
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time",
            List.of(bus(1, "stop-1", 43), bus(null, "stop-2", 12))));

        // when
        CollectionResult result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.outcome()).isEqualTo(CollectionBatchStatus.SUCCESS_ROWS);
            softly.assertThat(result.upstreamResultCode()).isZero();
        }
        ObservationRows collected = result.observations().orElseThrow();
        assertThat(collected.providerRows()).isEqualTo(2);
        assertThat(collected.storableRows()).hasSize(1);
        assertThat(collected.excludedRows()).hasSize(1);
        assertThat(collected.storableRows().getFirst().sourceRowNumber()).isZero();
        assertThat(collected.excludedRows().getFirst().sourceRowNumber()).isEqualTo(1);
    }

    @Test
    void 운행_차량이_없는_응답도_행수가_영인_정상_수집으로_반환한다() {
        // given
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.NoVehicles("query time"));

        // when
        CollectionResult result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.outcome()).isEqualTo(CollectionBatchStatus.SUCCESS_EMPTY);
            softly.assertThat(result.upstreamResultCode()).isEqualTo(4);
        }
        assertThat(result.observations().orElseThrow().providerRows()).isZero();
    }

    @ParameterizedTest
    @MethodSource("failedResponses")
    void 외부_실패_유형과_원본_결과_코드를_보존한다(
        GbisLocationResult response,
        CollectionBatchStatus outcome,
        CollectionFailureReason failureCode,
        Integer resultCode
    ) {
        // given
        given(source.read(ROUTE_ID, "a")).willReturn(response);

        // when
        CollectionResult result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.outcome()).isEqualTo(outcome);
            softly.assertThat(result.failureCode()).isEqualTo(failureCode);
            softly.assertThat(result.upstreamResultCode()).isEqualTo(resultCode);
        }
        assertThat(result.observations()).isEmpty();
    }

    @Test
    void 예상하지_못한_조회_예외는_응답을_확인하지_못한_결과로_반환한다() {
        // given
        given(source.read(ROUTE_ID, "a")).willThrow(new IllegalStateException("connection failed"));

        // when
        CollectionResult result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        // then
        assertThat(result.outcome()).isEqualTo(CollectionBatchStatus.UNKNOWN_AFTER_DISPATCH);
        assertThat(result.observations()).isEmpty();
        assertThat(result.receivedAt()).isEqualTo(RECEIVED_AT);
    }

    @Test
    void 예상하지_못한_조회_예외는_상류_호출_실패로_운영_로그에_남긴다() {
        // given
        given(source.read(ROUTE_ID, "a")).willThrow(new IllegalStateException("connection failed"));
        Logger operationLog = (Logger) LoggerFactory.getLogger(WorkerOperationLog.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        operationLog.addAppender(events);

        // when
        try {
            observations.read(ROUTE_ID, "a");
        } finally {
            operationLog.detachAppender(events);
        }

        // then
        assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).singleElement().asString()
            .contains("status=FAILED operation=collection_upstream route=" + ROUTE_ID);
    }

    @Test
    void 정규화_오류는_응답_미수신으로_바꾸지_않고_전파한다() {
        // given
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time", null));

        // when
        Throwable normalizationFailure = catchThrowable(() -> observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT));

        // then
        assertThat(normalizationFailure)
            .isNotNull()
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void 원본_응답_목록이_바뀌어도_정규화한_결과는_바뀌지_않는다() {
        // given
        ArrayList<BusLocationResponse.BusLocation> rows = new ArrayList<>(List.of(bus(1, "stop-1", 43)));
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time", rows));
        CollectionResult result = observations.read(ROUTE_ID, "a").interpret(RECEIVED_AT);

        // when
        rows.clear();

        // then
        assertThat(result.observations().orElseThrow().storableRows()).hasSize(1);
    }

    @Test
    void 정류장_참조는_순번과_ID가_모두_일치하는_첫_원본_행을_반환한다() {
        // given
        given(source.read(ROUTE_ID, "a")).willReturn(new GbisLocationResult.Success("query time", List.of(
                bus(2, "other-stop", 10),
                bus(1, "matched-stop", 20),
                bus(2, "matched-stop", 30),
                bus(2, "matched-stop", 40)
        )));
        ObservationResponse upstreamResponse = observations.read(ROUTE_ID, "a");

        // when
        Optional<ObservationResponse.SourceStopReference> sourceReference =
            upstreamResponse.sourceReference(2, "matched-stop");

        // then
        assertThat(sourceReference).contains(new ObservationResponse.SourceStopReference(
                2,
                ROUTE_ID,
                2,
                "matched-stop"
        ));
    }

    private static Stream<Arguments> failedResponses() {
        return Stream.of(
                Arguments.of(
                        new GbisLocationResult.DailyQuotaExceeded(),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.DAILY_QUOTA_EXCEEDED,
                        null
                ),
                Arguments.of(
                        new GbisLocationResult.PerSecondQuotaExceeded(),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.PER_SECOND_QUOTA_EXCEEDED,
                        null
                ),
                Arguments.of(
                        new GbisLocationResult.GatewayRejected("reason", "error", "message"),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.UPSTREAM_ERROR,
                        null
                ),
                Arguments.of(
                        new GbisLocationResult.GbisSystemError("time", "message"),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.UPSTREAM_ERROR,
                        1
                ),
                Arguments.of(
                        new GbisLocationResult.MissingRequiredParameter("time", "message"),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.UPSTREAM_ERROR,
                        2
                ),
                Arguments.of(
                        new GbisLocationResult.UnknownGbisResultCode("time", 99, "message"),
                        CollectionBatchStatus.FAILED_UPSTREAM,
                        CollectionFailureReason.UPSTREAM_ERROR,
                        99
                ),
                Arguments.of(
                        new GbisLocationResult.NoResponse("message"),
                        CollectionBatchStatus.UNKNOWN_AFTER_DISPATCH,
                        null,
                        null
                ),
                Arguments.of(
                        new GbisLocationResult.UnreadableResponse("message"),
                        CollectionBatchStatus.FAILED_UNREADABLE,
                        null,
                        null
                )
        );
    }

    private static BusLocationResponse.BusLocation bus(Integer stopSequence, String stopId, final int seats) {
        return new BusLocationResponse.BusLocation(
                "plate",
                "vehicle-1",
                0,
                ROUTE_ID,
                11,
                stopId,
                stopSequence,
                2,
                seats,
                3,
                1
        );
    }
}
