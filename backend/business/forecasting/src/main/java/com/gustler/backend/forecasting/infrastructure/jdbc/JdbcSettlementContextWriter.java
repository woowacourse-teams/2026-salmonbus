package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.application.forecast.SettlementContextWriter;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 모델 배포의 {@code settlement_context}에 노선 판본 키로 정산 근거를 더한다. 이미 키가 있으면 바꾸지 않고 값이 같은지만 본다.
 */
public final class JdbcSettlementContextWriter implements SettlementContextWriter {
    private final JdbcClient jdbc;

    public JdbcSettlementContextWriter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(final long modelDeploymentId, final long routeVersionId, String context) {
        String route = Long.toString(routeVersionId);
        final int inserted = jdbc.sql("""
            UPDATE /* salmonbus:settlement_context.store */ model_deployment
            SET settlement_context = settlement_context || jsonb_build_object(CAST(? AS text), CAST(? AS jsonb))
            WHERE id = ? AND jsonb_exists(settlement_context, ?) = false
            """).params(route, context, modelDeploymentId, route).update();
        if (inserted == 0 && !hasSameStoredContext(modelDeploymentId, route, context)) {
            throw new IllegalStateException("같은 모델과 노선 판본의 정산 근거가 변경됐다");
        }
    }

    private boolean hasSameStoredContext(final long modelDeploymentId, String route, String context) {
        return jdbc.sql("""
            SELECT /* salmonbus:settlement_context.compare */
                (settlement_context -> CAST(? AS text)) IS NOT DISTINCT FROM CAST(? AS jsonb)
            FROM model_deployment WHERE id = ?
            """).params(route, context, modelDeploymentId).query(Boolean.class).single();
    }
}
