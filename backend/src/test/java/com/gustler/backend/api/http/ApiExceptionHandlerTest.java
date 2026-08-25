package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.route.RouteNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void 잘못된_노선_ID를_400_INVALID_ROUTE_ID로_옮긴다() {
        // when
        final ResponseEntity<ErrorResponse> actual =
            handler.handleInvalidRouteId(new InvalidRouteIdException());

        // then
        assertContract(actual, HttpStatus.BAD_REQUEST, ErrorCode.INVALID_ROUTE_ID,
            "routeId는 9자리 숫자여야 합니다.", false);
    }

    @Test
    void 등록되지_않은_노선을_404_ROUTE_NOT_FOUND로_옮긴다() {
        // when
        final ResponseEntity<ErrorResponse> actual =
            handler.handleRouteNotFound(new RouteNotFoundException());

        // then
        assertContract(actual, HttpStatus.NOT_FOUND, ErrorCode.ROUTE_NOT_FOUND,
            "등록되지 않은 노선입니다.", false);
    }

    @Test
    void 활성_번들이_담지_않은_노선_판본을_503_MODEL_OUT_OF_SCOPE로_옮긴다() {
        // when
        final ResponseEntity<ErrorResponse> actual =
            handler.handleModelOutOfScope(new ModelOutOfScopeException());

        // then
        assertContract(actual, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.MODEL_OUT_OF_SCOPE,
            "활성 모델 번들이 지원하지 않는 노선 판본입니다.", true);
    }

    @Test
    void 최근_관측_없음을_503_NO_RECENT_OBSERVATION으로_옮긴다() {
        // when
        final ResponseEntity<ErrorResponse> actual =
            handler.handleNoRecentObservation(new NoRecentObservationException());

        // then
        assertContract(actual, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.NO_RECENT_OBSERVATION,
            "예보 기준으로 사용할 최근 차량 관측이 없습니다.", true);
    }

    @Test
    void 일시적인_서버_장애를_503_SERVICE_UNAVAILABLE로_옮긴다() {
        // when
        final ResponseEntity<ErrorResponse> actual =
            handler.handleServiceUnavailable(new ServiceUnavailableException());

        // then
        assertContract(actual, HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
            "일시적인 서버 장애가 발생했습니다.", true);
    }

    private void assertContract(
        final ResponseEntity<ErrorResponse> actual,
        final HttpStatus expectedStatus,
        final ErrorCode expectedCode,
        final String expectedMessage,
        final boolean expectedRetryable
    ) {
        assertThat(actual.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(actual.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(actual.getBody()).isNotNull();
        assertThat(actual.getBody().code()).isEqualTo(expectedCode);
        assertThat(actual.getBody().message()).isEqualTo(expectedMessage);
        assertThat(actual.getBody().requestId()).isNotBlank();
        assertThat(actual.getBody().retryable()).isEqualTo(expectedRetryable);
    }
}
