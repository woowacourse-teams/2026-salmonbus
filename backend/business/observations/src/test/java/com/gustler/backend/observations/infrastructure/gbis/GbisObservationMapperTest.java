package com.gustler.backend.observations.infrastructure.gbis;

import static com.gustler.backend.observations.domain.CollectionBatchStatus.FAILED_UNREADABLE;
import static com.gustler.backend.observations.domain.CollectionBatchStatus.FAILED_UPSTREAM;
import static com.gustler.backend.observations.domain.CollectionBatchStatus.SUCCESS_EMPTY;
import static com.gustler.backend.observations.domain.CollectionBatchStatus.SUCCESS_ROWS;
import static com.gustler.backend.observations.domain.CollectionBatchStatus.UNKNOWN_AFTER_DISPATCH;
import static com.gustler.backend.observations.domain.CollectionFailureReason.DAILY_QUOTA_EXCEEDED;
import static com.gustler.backend.observations.domain.CollectionFailureReason.PER_SECOND_QUOTA_EXCEEDED;
import static com.gustler.backend.observations.domain.CollectionFailureReason.UPSTREAM_ERROR;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.gbis.api.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.GatewayRejected;
import com.gustler.backend.gbis.api.GbisLocationResult.GbisSystemError;
import com.gustler.backend.gbis.api.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.NoVehicles;
import com.gustler.backend.gbis.api.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.GbisLocationResult.UnknownGbisResultCode;
import com.gustler.backend.gbis.api.GbisLocationResult.UnreadableResponse;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.observations.domain.CollectionResult;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GbisObservationMapperTest {
    private static final String QUERY_TIME = "2026-08-19 11:14:04.9";
    private static final int RESULT_CODE_SUCCESS = 0;
    private static final int RESULT_CODE_SYSTEM_FAILURE = 1;
    private static final int RESULT_CODE_PARAMETER_MISSING = 2;
    private static final int RESULT_CODE_NO_VEHICLES = 4;
    private static final int RESULT_CODE_NEVER_SEEN = 99;
    private static final OffsetDateTime RECEIVED_AT = OffsetDateTime.parse("2026-08-28T12:00:00+09:00");
    private static final String PORTAL_ERROR = "SERVICE ERROR";
    private static final String PORTAL_MESSAGE = "portal rejected";

    @Test
    void 차량_행이_있는_정상_응답의_결말은_SUCCESS_ROWS다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new Success(QUERY_TIME, List.of(bus())), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(SUCCESS_ROWS, null, RESULT_CODE_SUCCESS);
    }

    @Test
    void 차량_행이_없는_정상_응답의_결말은_SUCCESS_EMPTY다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new Success(QUERY_TIME, List.of()), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(SUCCESS_EMPTY, null, RESULT_CODE_SUCCESS);
    }

    @Test
    void 운행_차량이_없다는_응답의_결말은_SUCCESS_EMPTY다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new NoVehicles(QUERY_TIME), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(SUCCESS_EMPTY, null, RESULT_CODE_NO_VEHICLES);
    }

    @Test
    void 하루_한도_초과_응답의_실패_사유는_DAILY_QUOTA_EXCEEDED다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new DailyQuotaExceeded(), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, DAILY_QUOTA_EXCEEDED, null);
    }

    @Test
    void 초당_한도_초과_응답의_실패_사유는_PER_SECOND_QUOTA_EXCEEDED다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new PerSecondQuotaExceeded(), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, PER_SECOND_QUOTA_EXCEEDED, null);
    }

    @Test
    void 게이트웨이가_거절한_응답의_실패_사유는_UPSTREAM_ERROR다() {
        // when
        CollectionResult actual = GbisObservationMapper.response(
            new GatewayRejected("30", "SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "등록되지 않은 키다"), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, UPSTREAM_ERROR, null);
    }

    @Test
    void 상류_시스템_오류_응답의_실패_사유는_UPSTREAM_ERROR다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new GbisSystemError(QUERY_TIME, "시스템 오류가 발생하였습니다."), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, UPSTREAM_ERROR, RESULT_CODE_SYSTEM_FAILURE);
    }

    @Test
    void 필수_파라미터가_빠졌다는_응답의_실패_사유는_UPSTREAM_ERROR다() {
        // when
        CollectionResult actual = GbisObservationMapper.response(
            new MissingRequiredParameter(QUERY_TIME, "필수 파라미터가 누락되었습니다."), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, UPSTREAM_ERROR, RESULT_CODE_PARAMETER_MISSING);
    }

    @Test
    void 뜻을_모르는_결과_코드의_응답은_그_코드를_원문_그대로_남긴다() {
        // when
        CollectionResult actual = GbisObservationMapper.response(
            new UnknownGbisResultCode(QUERY_TIME, RESULT_CODE_NEVER_SEEN, "알 수 없는 응답"), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UPSTREAM, UPSTREAM_ERROR, RESULT_CODE_NEVER_SEEN);
    }

    @Test
    void 응답이_오지_않은_판의_결말은_UNKNOWN_AFTER_DISPATCH다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new NoResponse("I/O error on GET request"), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(UNKNOWN_AFTER_DISPATCH, null, null);
    }

    @Test
    void 읽지_못한_응답의_결말은_FAILED_UNREADABLE다() {
        // when
        CollectionResult actual =
            GbisObservationMapper.response(new UnreadableResponse("Open API 응답에 헤더가 없다"), RECEIVED_AT);

        // then
        assertThat(actual)
            .extracting(CollectionResult::outcome, CollectionResult::failureCode, CollectionResult::upstreamResultCode)
            .containsExactly(FAILED_UNREADABLE, null, null);
    }

    @ParameterizedTest
    @MethodSource("resultsAndKeyRejectionCodes")
    void 하루_한도_초과나_키_미등록_만료로_거절된_응답에만_키_거절_사유를_남긴다(
        GbisLocationResult result,
        Optional<String> expected
    ) {
        // when
        Optional<String> actual = GbisObservationMapper.response(result, RECEIVED_AT).keyRejectionCode();

        // then
        assertThat(actual).isEqualTo(expected);
    }

    private static Stream<Arguments> resultsAndKeyRejectionCodes() {
        return Stream.of(
                Arguments.of(new DailyQuotaExceeded(), Optional.of("22")),
                Arguments.of(new GatewayRejected("30", PORTAL_ERROR, PORTAL_MESSAGE), Optional.of("30")),
                Arguments.of(new GatewayRejected("31", PORTAL_ERROR, PORTAL_MESSAGE), Optional.of("31")),
                Arguments.of(new PerSecondQuotaExceeded(), Optional.empty()),
                Arguments.of(new GatewayRejected("20", PORTAL_ERROR, PORTAL_MESSAGE), Optional.empty()),
                Arguments.of(new GatewayRejected(null, PORTAL_ERROR, PORTAL_MESSAGE), Optional.empty())
        );
    }

    private static BusLocation bus() {
        return new BusLocation(
                "경기70아0001",
                "204000206",
                0,
                "204000057",
                11,
                "205000217",
                1,
                2,
                43,
                3,
                1
        );
    }
}
