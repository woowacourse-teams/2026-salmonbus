package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.SettlementContextProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 배포에 노선 판본마다 한 번 적힌 정산 근거({@code model_deployment.settlement_context -> 노선 판본})를 읽는다.
 *
 * <p>옛 확정 판 정산({@code JdbcAlightingAwareForecastEvaluation.readWork})은 같은 노드를 jsonb 텍스트로 받아 기본 설정
 * {@link JsonMapper}로 읽고, 정류장 계수 노드(또는 {@code extendedSettlement} 칸)의 {@code toString()}을 해시한다. 여기서도
 * 같은 노드를 jsonb 텍스트로 받아 같은 설정으로 읽으므로 계수 노드의 키 순서와 숫자 표기가 같고 계수 해시도 같다.
 *
 * <p>찾은 근거는 (모델 배포, 노선 판본)마다 메모리에 계속 둔다. 근거는 한 번 적히면 바뀌지 않는다(옛 발행 저장소가 다른 값으로
 * 덮어쓰기를 거부한다). 근거가 없으면 {@value #MISSING_RETRY_SECONDS}초 동안 다시 묻지 않는다. 행 잠금을 잡지 않는다.
 */
public final class JdbcSettlementContextProvider implements SettlementContextProvider {
    static final long MISSING_RETRY_SECONDS = 60;
    private static final Duration MISSING_RETRY = Duration.ofSeconds(MISSING_RETRY_SECONDS);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String READ = """
        SELECT (settlement_context -> CAST(:version AS text))::text FROM model_deployment WHERE id = :deployment
        """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<Key, JsonNode> found = new ConcurrentHashMap<>();
    private final Map<Key, Instant> missing = new ConcurrentHashMap<>();

    /**
     * @param jdbc DB 접근
     * @param clock 근거가 없을 때 다시 묻는 간격의 기준
     */
    public JdbcSettlementContextProvider(JdbcClient jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Optional<JsonNode> find(final long modelDeploymentId, final long routeVersionId) {
        Key key = new Key(modelDeploymentId, routeVersionId);
        JsonNode cached = found.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        Instant now = clock.instant();
        Instant checked = missing.get(key);
        if (checked != null && now.isBefore(checked.plus(MISSING_RETRY))) {
            return Optional.empty();
        }
        Optional<String> text = jdbc.sql(READ)
            .param("version", Long.toString(routeVersionId))
            .param("deployment", modelDeploymentId)
            .query((row, index) -> Optional.ofNullable(row.getString(1)))
            .optional()
            .flatMap(value -> value);
        if (text.isEmpty()) {
            missing.put(key, now);
            return Optional.empty();
        }
        JsonNode context = JSON.readTree(text.get());
        found.put(key, context);
        missing.remove(key);
        return Optional.of(context);
    }

    private record Key(long modelDeploymentId, long routeVersionId) {
    }
}
