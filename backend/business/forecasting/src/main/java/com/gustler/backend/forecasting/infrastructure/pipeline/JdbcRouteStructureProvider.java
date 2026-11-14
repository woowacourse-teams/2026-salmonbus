package com.gustler.backend.forecasting.infrastructure.pipeline;

import com.gustler.backend.forecasting.application.pipeline.RouteStructureProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 노선 판본의 실정류장 순번을 읽는다.
 *
 * <p>옛 통과 판정은 두 곳에서 같은 목록을 쓴다. 옛 정산의 통과 투영({@code JdbcSettlementProjection.advance})은 정산 근거의
 * {@code shared.realStopOrders}를, 새 예보 단계는 모델 통계의 {@code realStopOrders()}(확정 판은 승차 가능 정류장)를 쓰며, 앞의
 * 것은 옛 발행이 뒤의 것을 그대로 옮겨 적은 값이다. 그래서 먼저 그 노선 판본의 정산 근거가 적힌 가장 최근 모델 배포에서
 * {@code shared.realStopOrders}를 읽는다. 근거가 아직 없으면 {@code route_stop}의 승차 가능 정류장을 돌려준다. 새 예보 단계는 이
 * 표와 모델 통계의 정류장 구성(순번·정류장·승차 가능·방향)이 같은 판본만 되감으므로 두 출처는 같은 목록이다.
 * 행 잠금을 잡지 않는다.
 */
public final class JdbcRouteStructureProvider implements RouteStructureProvider {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String READ_CONTEXT = """
        SELECT (settlement_context -> CAST(:version AS text) -> 'shared' -> 'realStopOrders')::text
        FROM model_deployment
        WHERE jsonb_exists(settlement_context, CAST(:version AS text))
        ORDER BY id DESC
        LIMIT 1
        """;
    private static final String READ_STOPS = """
        SELECT stop_order FROM route_stop WHERE route_version_id = :version AND boarding_allowed ORDER BY stop_order
        """;

    private final JdbcClient jdbc;

    public JdbcRouteStructureProvider(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<Integer> realStopOrders(final long routeVersionId) {
        Optional<String> recorded = jdbc.sql(READ_CONTEXT)
            .param("version", Long.toString(routeVersionId))
            .query((row, index) -> Optional.ofNullable(row.getString(1)))
            .optional()
            .flatMap(value -> value);
        if (recorded.isPresent()) {
            JsonNode orders = JSON.readTree(recorded.get());
            if (orders.isArray()) {
                List<Integer> stops = new ArrayList<>();
                orders.forEach(value -> stops.add(value.asInt()));
                return List.copyOf(stops);
            }
        }
        return jdbc.sql(READ_STOPS).param("version", routeVersionId).query(Integer.class).list();
    }
}
