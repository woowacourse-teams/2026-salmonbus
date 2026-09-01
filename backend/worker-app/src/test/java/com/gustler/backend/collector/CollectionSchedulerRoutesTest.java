package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.gustler.backend.collector.GbisLocationResult.Success;
import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.support.PostgresTestContainer;
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
 * 설정에 적은 노선으로 한 바퀴 도는 것을 본다.
 *
 * <p>주기 작업은 꺼둔 채로 스케줄러 메서드를 직접 부른다. 켜두면 뒤에서 첫 판이 도는 것과
 * 이 테스트가 겹쳐 무엇이 쌓은 행인지 흐려진다. 스케줄이 걸리는지는 ScheduledCollectionTest 가 본다.
 */
@SpringBootTest(properties = "collection.route-ids[0]=204000057")
@Import(PostgresTestContainer.class)
class CollectionSchedulerRoutesTest {

    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_277103149 = "277103149";
    private static final String STOP_208000069 = "208000069";
    private static final int TURN_SEQUENCE = 2;
    private static final String QUERY_TIME = "2026-08-28 11:14:04.9";

    @MockitoBean
    private GbisRouteSource routeSource;

    @MockitoBean
    private GbisLocationSource locationSource;

    @Autowired
    private CollectionScheduler scheduler;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void 상류_대역을_세운다() {
        given(routeSource.read(ROUTE_3330)).willReturn(new GbisRouteResult.Success(upstreamRoute()));
        given(locationSource.read(ROUTE_3330)).willReturn(new Success(QUERY_TIME, List.of(
            new BusLocation("경기70아0001", "204000206", 0, ROUTE_3330, 11,
                STOP_205000217, 1, 2, 43, 3, 1))));
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
    void 설정에_적은_노선의_판본이_한_바퀴_만에_열린다() {
        // when
        scheduler.collectAllRoutes();

        // then
        assertThat(countOf("SELECT count(*) FROM route_version WHERE valid_to IS NULL")).isEqualTo(1);
    }

    @Test
    void 설정에_적은_노선을_한_바퀴_돌면_관측이_쌓인다() {
        // when
        scheduler.collectAllRoutes();

        // then
        assertThat(countOf("SELECT count(*) FROM vehicle_observation")).isEqualTo(1);
    }

    private static UpstreamRoute upstreamRoute() {
        return new UpstreamRoute(
            ROUTE_3330, "3330", "범계역", "강남역",
            RouteStops.from(TURN_SEQUENCE, List.of(
                new UpstreamRouteStop(1, STOP_205000217, "범계역"),
                new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
                new UpstreamRouteStop(3, STOP_208000069, "안양역"))),
            new RouteTimetable("05:00", "22:35", "05:00", "23:55"));
    }

    private int countOf(
        final String sql
    ) {
        return jdbcClient.sql(sql).query(Integer.class).single();
    }
}
