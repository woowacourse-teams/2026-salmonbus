package com.gustler.backend.api.arrival.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class WorkerArrivalRefreshClientTest {
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://worker.invalid");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final WorkerArrivalRefreshClient client = new WorkerArrivalRefreshClient(builder.build(),
        "test-token", Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void 정류장과_내부_인증을_보내고_접수_결과를_해석한다() {
        server.expect(requestTo("http://worker.invalid/internal/arrivals/refresh"))
            .andExpect(method(HttpMethod.POST)).andExpect(header("X-Arrival-Token", "test-token"))
            .andExpect(content().json("{\"stationId\":\"206000637\"}"))
            .andRespond(withSuccess("""
                {"status":"ACCEPTED","reason":"COLLECTING","suggestedRefreshAt":"2026-10-14T03:00:02Z"}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.request("206000637")).isEqualTo(new ArrivalRefresh(
            ArrivalRefresh.Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));
        server.verify();
    }

    @Test
    void Worker의_실패는_빠른_재조회_힌트를_가진_결과로_돌려준다() {
        server.expect(requestTo("http://worker.invalid/internal/arrivals/refresh")).andRespond(withServerError());
        assertThat(client.request("206000637")).isEqualTo(unavailable());
        server.verify();
    }

    @Test
    void 불완전한_접수_응답도_성공으로_처리하지_않는다() {
        server.expect(requestTo("http://worker.invalid/internal/arrivals/refresh"))
            .andRespond(withSuccess("{\"status\":\"ACCEPTED\"}", MediaType.APPLICATION_JSON));
        assertThat(client.request("206000637")).isEqualTo(unavailable());
        server.verify();
    }

    private ArrivalRefresh unavailable() {
        return new ArrivalRefresh(ArrivalRefresh.Status.UNAVAILABLE, "WORKER_UNAVAILABLE", NOW.plusSeconds(2));
    }
}
