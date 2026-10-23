package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.gustler.backend.gbis.api.GbisKey;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.GbisLocationSource;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.routecatalog.domain.RouteHeadways;
import com.gustler.backend.routecatalog.domain.RouteSource;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import com.gustler.backend.routecatalog.domain.UpstreamRouteStop;
import com.gustler.backend.support.PostgresTestContainer;
import com.gustler.backend.worker.configuration.CollectionProperties;
import com.gustler.backend.worker.configuration.CollectionRuntimeConfiguration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 설정에 적은 노선을 한 차례 수집하는지 확인한다.
 *
 * <p>주기 작업을 끄고 스케줄러 메서드를 직접 호출한다. 주기 작업을 켜면 최초 수집과
 * 이 테스트가 겹쳐 어느 쪽이 행을 저장했는지 구분하기 어렵다.
 * 스케줄 등록은 ScheduledCollectionTest 에서 확인한다.
 */
@SpringBootTest
@Import({PostgresTestContainer.class, CollectionRuntimeConfiguration.class})
class CollectionTwentyRoutesTest {
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_277103149 = "277103149";
    private static final String STOP_208000069 = "208000069";
    private static final int TURN_SEQUENCE = 2;
    private static final String QUERY_TIME = "2026-08-28 11:14:04.9";

    @MockitoBean
    private RouteSource routeSource;

    @MockitoBean
    private GbisLocationSource locationSource;

    @Autowired
    private CollectionScheduler scheduler;

    @Autowired
    private CollectionProperties properties;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 상류_대역을_세운다() {
        given(routeSource.requiredCallsPerRead()).willReturn(2);
        for (String routeId : properties.routeIds()) {
            given(routeSource.read(routeId)).willReturn(new RouteSourceResult.Success(upstreamRoute(routeId)));
            given(locationSource.read(routeId, GbisKey.PRIMARY)).willReturn(new Success(QUERY_TIME, List.of(
                new BusLocation("테스트차량", "V" + routeId, 0, routeId, 11,
                    STOP_205000217, 1, 2, 43, 3, 1))));
        }
    }

    @AfterEach
    void 쌓인_것을_비운다() {
        jdbcClient.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """)
            .update();
    }

    @Test
    void 추가한_12개_노선의_관측을_수집한다() {
        // given
        assertThat(properties.routeIds()).hasSize(20);

        // when
        scheduler.collectAllRoutes();

        // then
        assertThat(countOf("SELECT count(*) FROM vehicle_observation")).isEqualTo(20);
        assertThat(jdbcClient.sql("SELECT source_route_id FROM route").query(String.class).list())
            .contains("204000081", "234000002", "234000313", "234000878", "234000882", "204000082",
                "234001739", "233000266", "204000170", "234000879", "228000393", "200000314");
    }

    @Test
    void 등록된_노선_정보를_중복으로_생성하지_않는다() {
        // given
        scheduler.collectAllRoutes();

        // when
        scheduler.collectAllRoutes();

        // then
        assertThat(countOf("SELECT count(*) FROM route")).isEqualTo(20);
        assertThat(countOf("SELECT count(*) FROM route_version")).isEqualTo(20);
    }

    @Test
    void 한_노선의_수집이_실패해도_다른_노선을_계속_수집한다() {
        // given
        given(locationSource.read("204000081", GbisKey.PRIMARY)).willThrow(new IllegalStateException("test failure"));

        // when
        scheduler.collectAllRoutes();

        // then
        assertThat(countOf("SELECT count(*) FROM vehicle_observation")).isEqualTo(19);
    }

    private static UpstreamRoute upstreamRoute(String routeId) {
        return new UpstreamRoute(
            routeId, routeId, "범계역", "강남역",
            RouteStops.from(TURN_SEQUENCE, List.of(
                new UpstreamRouteStop(1, STOP_205000217, "범계역"),
                new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
                new UpstreamRouteStop(3, STOP_208000069, "안양역"))),
            new RouteTimetable("05:00", "22:35", "05:00", "23:55", RouteHeadways.none()));
    }

    private int countOf(
        String sql
    ) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }
}
