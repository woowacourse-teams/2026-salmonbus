package com.gustler.backend.api.route.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

@IntegrationTest
@Transactional
class RouteApiContractTest {

    private static final OffsetDateTime FIRST_VERSION_AT = OffsetDateTime.parse(
        "2026-08-01T00:00:00+09:00"
    );
    private static final OffsetDateTime SECOND_VERSION_AT = OffsetDateTime.parse(
        "2026-08-20T00:00:00+09:00"
    );
    private static final String FIRST_DIGEST = "1".repeat(64);
    private static final String SECOND_DIGEST = "2".repeat(64);

    @Autowired
    private WebApplicationContext applicationContext;

    @Autowired
    private JdbcClient jdbcClient;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
            .webAppContextSetup(applicationContext)
            .build();
    }

    @Test
    void 현재_판본이_있는_노선을_DB_생성_순서와_계약_필드_순서대로_반환한다() throws Exception {
        // given
        final long firstRouteId = insertRoute(
            "234000050",
            "1650",
            "구리수택차고지",
            "안양역"
        );
        final long secondRouteId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertCurrentVersionWithStop(firstRouteId, FIRST_DIGEST, "222000001");
        insertCurrentVersionWithStop(secondRouteId, SECOND_DIGEST, "205000001");
        insertModelDeployment("STAGED");

        final String expected = """
            {"routes":[{"id":"234000050","displayName":"1650","startStopName":"구리수택차고지","endStopName":"안양역","status":"PREPARING"},{"id":"204000057","displayName":"3330","startStopName":"도촌동9단지앞","endStopName":"안양역","status":"PREPARING"}]}
            """.strip();

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(content().string(expected));
    }

    @Test
    void 활성_모델이_있어도_현재_판본에_발행된_예보가_없으면_PREPARING으로_반환한다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertCurrentVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertModelDeployment("ACTIVE");

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].status").value("PREPARING"));
    }

    @Test
    void 활성_모델이_있고_현재_판본에_발행된_예보가_있으면_FORECAST_READY로_반환한다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertCurrentVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertModelDeployment("ACTIVE");
        insertPublication(currentVersionIdOf(routeId));

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].status").value("FORECAST_READY"));
    }

    @Test
    void 활성_모델이_없으면_현재_판본에_발행된_예보가_있어도_PREPARING으로_반환한다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertCurrentVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertModelDeployment("STAGED");
        insertPublication(currentVersionIdOf(routeId));

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].status").value("PREPARING"));
    }

    @Test
    void 종료된_판본에만_발행된_예보가_있으면_PREPARING으로_반환한다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertExpiredVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertCurrentVersionWithStop(routeId, SECOND_DIGEST, "205000002");
        insertModelDeployment("ACTIVE");
        insertPublication(expiredVersionIdOf(routeId));

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes.length()").value(1))
            .andExpect(jsonPath("$.routes[0].status").value("PREPARING"));
    }

    @Test
    void 노선마다_현재_판본의_발행_여부로_상태가_갈린다() throws Exception {
        // given
        final long forecastRouteId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        final long newRouteId = insertRoute(
            "204000070",
            "9007",
            "새 노선 기점",
            "새 노선 종점"
        );
        insertCurrentVersionWithStop(forecastRouteId, FIRST_DIGEST, "205000001");
        insertCurrentVersionWithStop(newRouteId, SECOND_DIGEST, "205000002");
        insertModelDeployment("ACTIVE");
        insertPublication(currentVersionIdOf(forecastRouteId));

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes.length()").value(2))
            .andExpect(jsonPath("$.routes[0].id").value("204000057"))
            .andExpect(jsonPath("$.routes[0].status").value("FORECAST_READY"))
            .andExpect(jsonPath("$.routes[1].id").value("204000070"))
            .andExpect(jsonPath("$.routes[1].status").value("PREPARING"));
    }

    @Test
    void 종료된_판본만_있는_노선은_목록에서_빠진다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertExpiredVersionWithStop(routeId, FIRST_DIGEST, "205000001");

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes").isEmpty());
    }

    @Test
    void 새_현재_판본이_생기면_목록에_들어온다() throws Exception {
        // given
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertExpiredVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertCurrentVersionWithStop(routeId, SECOND_DIGEST, "205000002");

        // when & then
        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes.length()").value(1))
            .andExpect(jsonPath("$.routes[0].id").value("204000057"));
    }

    private long insertRoute(
        final String sourceRouteId,
        final String displayName,
        final String startStopName,
        final String endStopName
    ) {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(
                sourceRouteId,
                "GBIS",
                sourceRouteId,
                displayName,
                startStopName,
                endStopName
            )
            .query(Long.class)
            .single();
    }

    private void insertCurrentVersionWithStop(
        final long routeId,
        final String contentDigest,
        final String stopId
    ) {
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from)
                VALUES (?, ?, ?)
                RETURNING id
                """)
            .params(routeId, contentDigest, SECOND_VERSION_AT)
            .query(Long.class)
            .single();
        insertRouteStop(routeVersionId, stopId);
    }

    private void insertExpiredVersionWithStop(
        final long routeId,
        final String contentDigest,
        final String stopId
    ) {
        final long routeVersionId = jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, valid_to)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(routeId, contentDigest, FIRST_VERSION_AT, SECOND_VERSION_AT)
            .query(Long.class)
            .single();
        insertRouteStop(routeVersionId, stopId);
    }

    private void insertRouteStop(
        final long routeVersionId,
        final String stopId
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id,
                    name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, 1, stopId, "테스트 정류장", "UP", true)
            .update();
    }

    private void insertModelDeployment(final String state) {
        jdbcClient.sql("""
                INSERT INTO model_deployment (
                    deployment_key, release_id, model_key, model_version,
                    bundle_digest, prediction_target_version, calculation_version,
                    supported_scope_digest, data_until, state, activated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)
            .params(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "route-contract-test",
                "seat-forecast",
                "test-v1",
                "3".repeat(64),
                "target-v1",
                "calculation-v1",
                "4".repeat(64),
                SECOND_VERSION_AT,
                state,
                SECOND_VERSION_AT
            )
            .update();
    }

    private long currentVersionIdOf(final long routeId) {
        return jdbcClient.sql("""
                SELECT id FROM route_version WHERE route_id = ? AND valid_to IS NULL
                """)
            .params(routeId)
            .query(Long.class)
            .single();
    }

    private long expiredVersionIdOf(final long routeId) {
        return jdbcClient.sql("""
                SELECT id FROM route_version WHERE route_id = ? AND valid_to IS NOT NULL
                """)
            .params(routeId)
            .query(Long.class)
            .single();
    }

    private void insertPublication(final long routeVersionId) {
        final long batchId = jdbcClient.sql("""
                INSERT INTO observation_batch (
                    route_version_id, scheduled_at, attempt_number, attempt_key,
                    requested_at, response_received_at,
                    completed_at, outcome, provider_rows, stored_rows, excluded_rows,
                    normalization_version, collection_strategy_version
                ) VALUES (?, ?, 1, ?, ?, ?, ?, 'SUCCESS_EMPTY', 0, 0, 0, ?, ?)
                RETURNING id
                """)
            .params(
                routeVersionId,
                SECOND_VERSION_AT,
                "route-contract-test-" + routeVersionId,
                SECOND_VERSION_AT,
                SECOND_VERSION_AT,
                SECOND_VERSION_AT,
                "normalization-v1.0.0",
                "adaptive-kst-v1.0.1"
            )
            .query(Long.class)
            .single();
        jdbcClient.sql("""
                INSERT INTO forecast_publication (
                    source_batch_id, route_version_id,
                    model_deployment_id, demand_statistics_revision, quality_revision,
                    observed_at, generated_at, published_at, prediction_count
                )
                SELECT batch.id, batch.route_version_id,
                       deployment.id, 1, 1,
                       batch.response_received_at, batch.response_received_at, batch.response_received_at, 0
                FROM observation_batch batch
                CROSS JOIN model_deployment deployment
                WHERE batch.id = ?
                  AND deployment.release_id = 'route-contract-test'
                RETURNING id
                """)
            .params(batchId)
            .query(Long.class)
            .single();
    }
}
