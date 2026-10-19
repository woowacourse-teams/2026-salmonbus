package com.gustler.backend.api.arrival.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ArrivalWaitHeaderClientTest {
    @Test
    void 완료_대기는_별도_헤더로_전달해_기존_Worker의_본문_계약을_유지한다() {
        var builder = RestClient.builder().baseUrl("http://worker.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new WorkerArrivalRefreshClient(builder.build(), "test-token", Clock.systemUTC());
        server.expect(requestTo("http://worker.invalid/internal/arrivals/refresh"))
            .andExpect(header(ArrivalRefresh.WAIT_HEADER, "500"))
            .andExpect(content().json("{\"stationId\":\"206000637\"}"))
            .andRespond(withSuccess("{\"status\":\"IDLE\",\"reason\":null,\"suggestedRefreshAt\":\"2026-10-19T03:00:40Z\"}", MediaType.APPLICATION_JSON));
        assertThat(client.request("206000637", Duration.ofMillis(500)).status()).isEqualTo(ArrivalRefresh.Status.IDLE);
        server.verify();
    }
}
