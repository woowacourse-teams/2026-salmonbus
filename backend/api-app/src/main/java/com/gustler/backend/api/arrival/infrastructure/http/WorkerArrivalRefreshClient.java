package com.gustler.backend.api.arrival.infrastructure.http;

import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Clock;
import org.springframework.web.client.RestClient;

/** Worker의 빠른 접수 응답만 기다린다. 실패해도 기존 수신 자료의 조회를 막지 않는다. */
public final class WorkerArrivalRefreshClient implements ArrivalRefreshGateway {

    private final RestClient rest;
    private final String token;
    private final Clock clock;

    public WorkerArrivalRefreshClient(RestClient rest, String token, Clock clock) {
        this.rest = rest;
        this.token = token;
        this.clock = clock;
    }

    @Override
    public ArrivalRefresh request(String stationId) {
        try {
            ArrivalRefresh reply = rest.post().uri("/internal/arrivals/refresh")
                .header("X-Arrival-Token", token).body(new ArrivalRefresh.Request(stationId))
                .retrieve().body(ArrivalRefresh.class);
            if (reply == null || reply.status() == null || reply.suggestedRefreshAt() == null) {
                throw new IllegalStateException("수집 Worker 응답 미해석");
            }
            return reply;
        } catch (RuntimeException unavailable) {
            return new ArrivalRefresh(ArrivalRefresh.Status.UNAVAILABLE, "WORKER_UNAVAILABLE",
                clock.instant().plusSeconds(2));
        }
    }
}
