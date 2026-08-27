package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.ModelOutOfScopeException;
import com.gustler.backend.api.board.NoRecentObservationException;
import com.gustler.backend.api.route.InvalidRouteIdException;
import com.gustler.backend.api.route.RouteNotFoundException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static List<ApiException> thrownExceptions() {
        return List.of(
            new InvalidRouteIdException(),
            new RouteNotFoundException(),
            new ModelOutOfScopeException(),
            new NoRecentObservationException(),
            new ServiceUnavailableException()
        );
    }

    private static List<RuntimeException> databaseUnavailableExceptions() {
        return List.of(
            new CannotCreateTransactionException("DB 연결을 얻지 못했다"),
            new JpaSystemException(new IllegalStateException("DB 트랜잭션을 종료하지 못했다"))
        );
    }

    @ParameterizedTest
    @MethodSource("thrownExceptions")
    void 오류_코드가_정한_상태와_메시지로_응답한다(ApiException exception) {
        // given
        ErrorCode code = exception.code();

        // when
        ResponseEntity<ErrorResponse> actual = handler.handleApiException(exception);

        // then
        assertThat(actual.getStatusCode()).isEqualTo(code.status());
        assertThat(actual.getBody()).isNotNull();
        assertThat(actual.getBody().code()).isEqualTo(code);
        assertThat(actual.getBody().message()).isEqualTo(code.message());
        assertThat(actual.getBody().requestId()).isNotBlank();
    }

    @ParameterizedTest
    @MethodSource("thrownExceptions")
    void 오류_응답은_저장하지_않게_하고_재시도_시각은_알리지_않는다(ApiException exception) {
        // when
        ResponseEntity<ErrorResponse> actual = handler.handleApiException(exception);

        // then
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(actual.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
    }

    @Test
    void 모든_오류_코드는_예외나_상태_매핑으로_도달한다() {
        // given
        Set<ErrorCode> reachedByException = thrownExceptions().stream()
            .map(ApiException::code)
            .collect(Collectors.toSet());
        Set<ErrorCode> reachedByStatus = Arrays.stream(ErrorCode.values())
            .map(code -> ErrorCode.of(code.status()))
            .collect(Collectors.toSet());

        // when
        Set<ErrorCode> reachable =
            Stream.concat(reachedByException.stream(), reachedByStatus.stream())
                .collect(Collectors.toSet());

        // then
        assertThat(reachable).containsAll(Arrays.asList(ErrorCode.values()));
    }

    @Test
    void 다음_수집_시각을_알면_그때까지의_초를_알린다() {
        // given
        ApiException exception = new NoRecentObservationException(Duration.ofSeconds(15));

        // when
        ResponseEntity<ErrorResponse> actual = handler.handleApiException(exception);

        // then
        assertThat(actual.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("15");
    }

    @ParameterizedTest
    @MethodSource("databaseUnavailableExceptions")
    void DB_트랜잭션을_시작하거나_종료하지_못하면_서비스_불가로_응답한다(
        RuntimeException exception
    ) {
        // when
        ResponseEntity<ErrorResponse> actual =
            handler.handleDatabaseUnavailable(exception);

        // then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(actual.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(actual.getBody()).isNotNull();
        assertThat(actual.getBody().code()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        assertThat(actual.getBody().message()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.message());
        assertThat(actual.getBody().requestId()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
        "404, ENDPOINT_NOT_FOUND",
        "405, METHOD_NOT_ALLOWED",
        "503, SERVICE_UNAVAILABLE",
        "400, INVALID_REQUEST",
        "415, INVALID_REQUEST",
        "500, INTERNAL_ERROR",
        "502, INTERNAL_ERROR"
    })
    void 스프링이_판정한_상태를_우리_오류_코드로_옮긴다(
        int status,
        ErrorCode expected
    ) {
        // when
        ResponseEntity<Object> actual = handler.handleExceptionInternal(
            new IllegalStateException("스프링이 잡은 예외"),
            null,
            new HttpHeaders(),
            HttpStatus.valueOf(status),
            null
        );

        // then
        assertThat(actual.getStatusCode().value()).isEqualTo(status);
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(actual.getBody()).isInstanceOfSatisfying(ErrorResponse.class, body -> {
            assertThat(body.code()).isEqualTo(expected);
            assertThat(body.message()).isEqualTo(expected.message());
        });
    }

    @Test
    void 예상하지_못한_예외는_500으로_옮긴다() {
        // when
        ResponseEntity<ErrorResponse> actual =
            handler.handleUnexpected(new IllegalStateException("아무도 모르는 오류"));

        // then
        assertThat(actual.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(actual.getBody().code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(actual.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
