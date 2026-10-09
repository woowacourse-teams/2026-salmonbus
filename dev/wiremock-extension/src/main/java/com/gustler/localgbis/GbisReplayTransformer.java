package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.file.Path;

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
    public ResponseDefinition transform(ServeEvent event) {
        var parameters = event.getTransformerParameters();
        var reply = engine.response(parameters.getString("operation"),
            (String) parameters.getOrDefault("mode", "normal"), event.getRequest().queryParameter("routeId").firstValue());
        return ResponseDefinitionBuilder.like(event.getResponseDefinition()).withStatus(reply.status()).withBody(Json.write(reply.body()))
            .withHeader("Content-Type", "application/json; charset=utf-8").withHeader("Cache-Control", "no-store").build();
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
