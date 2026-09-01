package com.gustler.backend.processor.persistence.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.RouteStop;
import com.gustler.backend.processor.RouteStops;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class JdbcRouteVersionRepositoryTest {

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String ROUTE_204000121 = "204000121";
    private static final String CONTENT_DIGEST = "0".repeat(64);

    /** 노선 개편 전 판본이 열려 있던 기간. ex_route_version_no_overlap 이 개편 뒤와 겹치는 것을 막는다. */
    private static final OffsetDateTime BEFORE_REVISION = OffsetDateTime.parse("2026-08-01T00:00+09:00");
    private static final OffsetDateTime REVISED_AT = OffsetDateTime.parse("2026-08-19T00:00+09:00");

    private static final int STOP_ORDER_1 = 1;
    private static final int STOP_ORDER_2 = 2;
    private static final int STOP_ORDER_3 = 3;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private JdbcRouteVersionRepository repository;

    @Test
    void 지금_쓰는_판본만_읽는다() {
        // given
        final long routeId = insertRoute(ROUTE_204000057);
        insertRouteVersion(routeId, BEFORE_REVISION, REVISED_AT);
        final long currentVersionId = insertRouteVersion(routeId, REVISED_AT, null);

        // when
        List<Long> actual = repository.findActiveVersionIds();

        // then
        assertThat(actual).containsExactly(currentVersionId);
    }

    @Test
    void 한_판본의_정류장을_순번_오름차순으로_읽는다() {
        // given
        final long routeVersionId = insertCurrentRouteVersion(ROUTE_204000057);
        insertBoardingStop(routeVersionId, STOP_ORDER_3);
        insertBoardingStop(routeVersionId, STOP_ORDER_1);
        insertBoardingStop(routeVersionId, STOP_ORDER_2);

        // when
        RouteStops actual = repository.readStops(routeVersionId);

        // then
        assertThat(actual.stops())
            .extracting(RouteStop::stopOrder)
            .containsExactly(STOP_ORDER_1, STOP_ORDER_2, STOP_ORDER_3);
    }

    @Test
    void 승차_가능_여부를_그대로_읽는다() {
        // given
        final long routeVersionId = insertCurrentRouteVersion(ROUTE_204000057);
        insertBoardingStop(routeVersionId, STOP_ORDER_1);
        insertPassingPoint(routeVersionId, STOP_ORDER_2);

        // when
        RouteStops actual = repository.readStops(routeVersionId);

        // then
        assertThat(actual.stops())
            .extracting(RouteStop::boardingAllowed)
            .containsExactly(true, false);
    }

    @Test
    void 물어본_판본의_정류장만_읽는다() {
        // given
        final long routeVersionId = insertCurrentRouteVersion(ROUTE_204000057);
        insertBoardingStop(routeVersionId, STOP_ORDER_1);
        insertBoardingStop(routeVersionId, STOP_ORDER_2);
        final long otherRouteVersionId = insertCurrentRouteVersion(ROUTE_204000121);
        insertBoardingStop(otherRouteVersionId, STOP_ORDER_1);
        insertBoardingStop(otherRouteVersionId, STOP_ORDER_2);
        insertBoardingStop(otherRouteVersionId, STOP_ORDER_3);

        // when
        RouteStops actual = repository.readStops(routeVersionId);

        // then
        assertThat(actual.stops())
            .extracting(RouteStop::stopOrder)
            .containsExactly(STOP_ORDER_1, STOP_ORDER_2);
    }

    private long insertCurrentRouteVersion(
        String publicRouteId
    ) {
        return insertRouteVersion(insertRoute(publicRouteId), REVISED_AT, null);
    }

    private long insertRoute(
        String publicRouteId
    ) {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(publicRouteId, SOURCE_ID, publicRouteId, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    /** valid_to 가 NULL 이면 지금 쓰는 판본이고, 값이 있으면 그 시각에 닫힌 옛 판본이다. */
    private long insertRouteVersion(
        final long routeId,
        OffsetDateTime validFrom,
        OffsetDateTime validTo
    ) {
        return jdbcClient.sql("""
                INSERT INTO route_version (route_id, content_digest, valid_from, valid_to)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """)
            .params(routeId, CONTENT_DIGEST, validFrom, validTo)
            .query(Long.class)
            .single();
    }

    /** 승객이 탈 수 있는 정류장. */
    private void insertBoardingStop(
        final long routeVersionId,
        final int stopOrder
    ) {
        insertRouteStop(routeVersionId, stopOrder, "20500021%d".formatted(stopOrder), true);
    }

    /** 아무도 타지 않는 고속 구간의 경유 지점. 정류소 아이디가 277 로 시작하는 자리다. */
    private void insertPassingPoint(
        final long routeVersionId,
        final int stopOrder
    ) {
        insertRouteStop(routeVersionId, stopOrder, "27700021%d".formatted(stopOrder), false);
    }

    private void insertRouteStop(
        final long routeVersionId,
        final int stopOrder,
        String stopId,
        final boolean boardingAllowed
    ) {
        jdbcClient.sql("""
                INSERT INTO route_stop (
                    route_version_id, stop_order, stop_id, name, direction, boarding_allowed
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)
            .params(routeVersionId, stopOrder, stopId, "범계역", "UP", boardingAllowed)
            .update();
    }
}
