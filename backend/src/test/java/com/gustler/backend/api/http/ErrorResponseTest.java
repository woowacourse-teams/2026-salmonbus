package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

@JsonTest
class ErrorResponseTest {

    @Autowired
    private JacksonTester<ErrorResponse> json;

    @Test
    void 오류_응답을_계약의_네_필드로_직렬화한다() throws IOException {
        // given
        final ErrorResponse response = new ErrorResponse(
            ErrorCode.INVALID_ROUTE_ID,
            "routeId must be 9 digits",
            "request-id",
            false
        );

        // when & then
        assertThat(json.write(response)).isStrictlyEqualToJson("""
            {
              "code": "INVALID_ROUTE_ID",
              "message": "routeId must be 9 digits",
              "requestId": "request-id",
              "retryable": false
            }
            """);
    }

    @Test
    void 계약에서_필수인_필드가_null이면_오류_응답을_만들지_않는다() {
        assertThatThrownBy(() -> new ErrorResponse(null, "message", "request-id", false))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("code must not be null");
        assertThatThrownBy(() -> new ErrorResponse(ErrorCode.INVALID_ROUTE_ID, null, "request-id", false))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("message must not be null");
        assertThatThrownBy(() -> new ErrorResponse(ErrorCode.INVALID_ROUTE_ID, "message", null, false))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("requestId must not be null");
    }
}
