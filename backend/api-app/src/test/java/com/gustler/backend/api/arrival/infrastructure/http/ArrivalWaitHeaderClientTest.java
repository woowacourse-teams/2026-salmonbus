package com.gustler.backend.api.arrival.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;

class ArrivalWaitHeaderClientTest {
    @Test
    void 완료_대기는_별도_헤더로_전달해_기존_Worker의_본문_계약을_유지한다() {
        // when
        Builder builder = RestClient.builder().baseUrl("http://worker.invalid");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        WorkerArrivalRefreshClient client =
            new WorkerArrivalRefreshClient(builder.build(), "test-token", Clock.systemUTC());
        server.expect(requestTo("http://worker.invalid/internal/arrivals/refresh"))
            .andExpect(header(ArrivalRefresh.WAIT_HEADER, "500"))
            .andExpect(content().json("{\"stationId\":\"206000637\"}"))
            .andRespond(
                withSuccess("{\"status\":\"IDLE\",\"reason\":null,\"suggestedRefreshAt\":\"2026-10-19T03:00:40Z\"}",
                    MediaType.APPLICATION_JSON));

        // then
        assertThat(client.request("206000637", Duration.ofMillis(500)).status()).isEqualTo(Status.IDLE);
        server.verify();
    }
}
