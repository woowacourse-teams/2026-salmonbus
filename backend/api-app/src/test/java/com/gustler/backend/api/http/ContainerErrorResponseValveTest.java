package com.gustler.backend.api.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.apache.catalina.connector.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class ContainerErrorResponseValveTest {

    private final ContainerErrorResponseValve valve = new ContainerErrorResponseValve();

    private final StringWriter written = new StringWriter();

    private Response 오류를_내는_응답(
        final int status
    ) throws Exception {
        Response response = mock(Response.class);
        given(response.getStatus()).willReturn(status);
        given(response.getContentWritten()).willReturn(0L);
        given(response.setErrorReported()).willReturn(true);
        given(response.getReporter()).willReturn(new PrintWriter(written));

        return response;
    }

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_REQUEST",
        "404, ENDPOINT_NOT_FOUND",
        "405, METHOD_NOT_ALLOWED",
        "500, INTERNAL_ERROR",
        "503, SERVICE_UNAVAILABLE"
    })
    void 컨트롤러를_못_거친_오류도_같은_오류_코드로_적는다(
        final int status,
        ErrorCode expected
    ) throws Exception {
        // given
        Response response = 오류를_내는_응답(status);

        // when
        valve.report(null, response, null);

        // then
        assertThat(written.toString())
            .contains("\"code\":\"%s\"".formatted(expected.name()))
            .contains("\"message\":\"%s\"".formatted(expected.message()))
            .contains("\"requestId\":\"");
    }

    @Test
    void 오류_응답은_JSON_이고_저장하지_않게_한다() throws Exception {
        // given
        Response response = 오류를_내는_응답(400);

        // when
        valve.report(null, response, null);

        // then
        verify(response).setContentType(MediaType.APPLICATION_JSON_VALUE);
        verify(response).setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
    }

    @Test
    void 이미_본문을_쓴_응답에는_덧붙이지_않는다() throws Exception {
        // given
        Response response = mock(Response.class);
        given(response.getStatus()).willReturn(400);
        given(response.getContentWritten()).willReturn(133L);

        // when
        valve.report(null, response, null);

        // then
        assertThat(written.toString()).isEmpty();
        verify(response, never()).setHeader(anyString(), anyString());
    }

    @Test
    void 오류가_아닌_응답에는_아무것도_안_쓴다() throws Exception {
        // given
        Response response = mock(Response.class);
        given(response.getStatus()).willReturn(200);

        // when
        valve.report(null, response, null);

        // then
        assertThat(written.toString()).isEmpty();
        verify(response, never()).setHeader(anyString(), anyString());
    }
}
