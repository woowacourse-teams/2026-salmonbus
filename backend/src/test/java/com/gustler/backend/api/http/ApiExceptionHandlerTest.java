package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.route.RouteNotFoundException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static List<ApiException> 우리가_던지는_예외들() {
        return List.of(
            new InvalidRouteIdException(),
            new RouteNotFoundException(),
            new ModelOutOfScopeException(),
            new NoRecentObservationException(),
            new ServiceUnavailableException()
        );
    }

    @ParameterizedTest
    @MethodSource("우리가_던지는_예외들")
    void 오류_코드가_정한_상태와_메시지와_재시도_여부로_응답한다(final ApiException exception) {
        // given
        final ErrorCode code = exception.code();

        // when
        final ResponseEntity<ErrorResponse> actual = handler.handleApiException(exception);

        // then
        assertThat(actual.getStatusCode()).isEqualTo(code.status());
        assertThat(actual.getBody()).isNotNull();
        assertThat(actual.getBody().code()).isEqualTo(code);
        assertThat(actual.getBody().message()).isEqualTo(code.message());
        assertThat(actual.getBody().requestId()).isNotBlank();
        assertThat(actual.getBody().retryable()).isEqualTo(code.retryable());
    }

    @ParameterizedTest
    @MethodSource("우리가_던지는_예외들")
    void 오류_응답은_저장하지_않게_하고_재시도_시각은_알리지_않는다(final ApiException exception) {
        // when
        final ResponseEntity<ErrorResponse> actual = handler.handleApiException(exception);

        // then
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(actual.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 오류_코드마다_예외가_하나씩_있다() {
        // given
        final List<ErrorCode> 예외가_있는_코드 = 우리가_던지는_예외들().stream()
            .map(ApiException::code)
            .toList();

        // when & then
        assertThat(예외가_있는_코드)
            .containsExactlyInAnyOrderElementsOf(Arrays.asList(ErrorCode.values()));
    }

    @Test
    void 재시도_가능한_오류는_이름을_가진_것뿐이다() {
        // when
        final List<ErrorCode> 재시도_가능 = Arrays.stream(ErrorCode.values())
            .filter(ErrorCode::retryable)
            .toList();

        // then
        assertThat(재시도_가능).containsExactlyInAnyOrder(
            ErrorCode.MODEL_OUT_OF_SCOPE,
            ErrorCode.NO_RECENT_OBSERVATION,
            ErrorCode.SERVICE_UNAVAILABLE
        );
    }

}
