package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.file.Path;
import java.util.Map;

public final class GbisReplayTransformer implements ResponseDefinitionTransformerV2 {
    private final ReplayEngine engine;

    public GbisReplayTransformer() {
        this(loadEngine());
    }

    GbisReplayTransformer(ReplayEngine engine) {
        this.engine = engine;
    }

    private static ReplayEngine loadEngine() {
        try {
            return new ReplayEngine(Path.of("/local/data/routes.json"), Path.of("/local/scenarios/replay.json"));
        } catch (Exception error) {
            throw new IllegalStateException("개발 노선과 재생 설정을 읽지 못했습니다.", error);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public ResponseDefinition transform(ServeEvent event) {
        var parameters = event.getTransformerParameters();
        String operation = parameters.getString("operation");
        if (operation.equals("stationAround")) {
            var request = event.getRequest();
            var around = engine.stationAround(request.queryParameter("x").firstValue(),
                request.queryParameter("y").firstValue());
            return ResponseDefinitionBuilder.like(event.getResponseDefinition()).withStatus(around.status())
                .withBody(Json.write(around.body())).withHeader("Content-Type", "application/json;charset=UTF-8")
                .withHeader("X-RateLimit-Limit", String.valueOf(StationReplay.DAILY_LIMIT))
                .withHeader("X-RateLimit-Remaining", String.valueOf(engine.stationCallsRemaining()))
                .withHeader("X-RateLimit-Requested", "1").build();
        }
        var reply = engine.response(operation,
            (String) parameters.getOrDefault("mode", "normal"), event.getRequest()
                .queryParameter(operation.equals("arrival") ? "stationId" : "routeId").firstValue(),
            (Map<String, String>) parameters.getOrDefault("routeModes", Map.of()));
        var response = ResponseDefinitionBuilder.like(event.getResponseDefinition()).withStatus(reply.status())
            .withBody(Json.write(reply.body())).withHeader("Content-Type", "application/json; charset=utf-8")
            .withHeader("Cache-Control", "no-store");
        if (reply.delayMillis() > 0) response.withFixedDelay(reply.delayMillis());
        return response.build();
    }

    @Override
    public String getName() {
        return "gbis-replay";
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }
}
