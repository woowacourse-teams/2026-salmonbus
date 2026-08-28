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

        String expected = """
            {"routes":[{"id":"234000050","displayName":"1650","startStopName":"구리수택차고지","endStopName":"안양역","status":"PREPARING"},{"id":"204000057","displayName":"3330","startStopName":"도촌동9단지앞","endStopName":"안양역","status":"PREPARING"}]}
            """.strip();

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public"))
            .andExpect(content().string(expected));
    }

    @Test
    void 활성_모델이_있으면_현재_노선을_FORECAST_READY로_반환한다() throws Exception {
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertCurrentVersionWithStop(routeId, FIRST_DIGEST, "205000001");
        insertModelDeployment("ACTIVE");

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes[0].status").value("FORECAST_READY"));
    }

    @Test
    void 판본이_교체되면_종료된_판본을_제외하고_새_현재_판본을_읽는다() throws Exception {
        final long routeId = insertRoute(
            "204000057",
            "3330",
            "도촌동9단지앞",
            "안양역"
        );
        insertExpiredVersionWithStop(routeId, FIRST_DIGEST, "205000001");

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes").isEmpty());

        insertCurrentVersionWithStop(routeId, SECOND_DIGEST, "205000002");

        mockMvc.perform(get("/api/v1/routes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.routes.length()").value(1))
            .andExpect(jsonPath("$.routes[0].id").value("204000057"));
    }

    private long insertRoute(
        String sourceRouteId,
        String displayName,
        String startStopName,
        String endStopName
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
        String contentDigest,
        String stopId
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
        String contentDigest,
        String stopId
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
        String stopId
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

    private void insertModelDeployment(String state) {
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
}
