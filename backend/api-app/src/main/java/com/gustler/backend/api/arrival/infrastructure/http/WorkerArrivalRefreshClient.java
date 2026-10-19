package com.gustler.backend.api.arrival.infrastructure.http;

import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.client.RestClient;

/** 기본은 빠른 접수 응답이며, 요청한 경우에만 수집 완료를 제한 시간 동안 기다린다. */
public final class WorkerArrivalRefreshClient implements ArrivalRefreshGateway {

    private final RestClient rest;
    private final RestClient waitingRest;
    private final String token;
    private final Clock clock;

    public WorkerArrivalRefreshClient(RestClient rest, String token, Clock clock) {
        this(rest, rest, token, clock);
    }

    public WorkerArrivalRefreshClient(RestClient rest, RestClient waitingRest, String token, Clock clock) {
        this.rest = rest;
        this.waitingRest = waitingRest;
        this.token = token;
        this.clock = clock;
    }

    @Override
    public ArrivalRefresh request(String stationId) {
        return request(stationId, Duration.ZERO);
    }

    @Override
    public ArrivalRefresh request(String stationId, Duration maximumWait) {
        try {
            RestClient selected = maximumWait.isZero() ? rest : waitingRest;
            var request = selected.post().uri("/internal/arrivals/refresh").header("X-Arrival-Token", token);
            if (!maximumWait.isZero()) request.header(ArrivalRefresh.WAIT_HEADER, Long.toString(maximumWait.toMillis()));
            ArrivalRefresh reply = request.body(new ArrivalRefresh.Request(stationId))
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
