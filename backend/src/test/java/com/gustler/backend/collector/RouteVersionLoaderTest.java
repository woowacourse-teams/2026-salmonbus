package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.IntegrationTest;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class RouteVersionLoaderTest {

    private static final OffsetDateTime FIRST_READ_AT = OffsetDateTime.parse("2026-08-19T11:14:04.911+09:00");
    private static final OffsetDateTime SECOND_READ_AT = OffsetDateTime.parse("2026-08-26T11:14:04.911+09:00");
    private static final OffsetDateTime THIRD_READ_AT = OffsetDateTime.parse("2026-09-02T11:14:04.911+09:00");

    private static final String SOURCE_ID = "GBIS";
    private static final String ROUTE_204000057 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_208000069 = "208000069";
    private static final String STOP_277103149 = "277103149";
    private static final int TURN_SEQUENCE_3330 = 43;
    private static final String LAST_BUS_MOVED_TO = "22:50";

    private static final RouteTimetable TIMETABLE_1650 =
        new RouteTimetable("05:00", "22:35", "05:00", "23:55");
    private static final RouteTimetable TIMETABLE_1650_LAST_BUS_MOVED =
        new RouteTimetable("05:00", LAST_BUS_MOVED_TO, "05:00", "23:55");

    @Autowired
    private RouteVersionLoader loader;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private EntityManager entityManager;

    private long routeId;

    @BeforeEach
    void 노선_행을_먼저_넣는다() {
        routeId = insertRoute();
    }

    @Test
    void 판본이_하나도_없으면_새_판본을_넣는다() {
        // when
        loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);

        // then
        assertThat(versionCount()).isEqualTo(1);
    }

    @Test
    void 판본을_새로_끊으면_정류소_목록도_같이_들어간다() {
        // given
        final long versionId = loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);

        // when
        List<Integer> actual = stopOrdersOf(versionId);

        // then
        assertThat(actual).containsExactly(1, 2, 3);
    }

    @Test
    void 직전_판본과_정류소가_다르면_판본이_둘이_된다() {
        // given
        loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);

        // when
        loader.load(routeId, fourStops(), TIMETABLE_1650, SECOND_READ_AT);

        // then
        assertThat(versionCount()).isEqualTo(2);
    }

    @Test
    void 새_판본이_열리는_시각에_직전_판본의_유효기간이_닫힌다() {
        // given
        final long firstVersionId = loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);
        loader.load(routeId, fourStops(), TIMETABLE_1650, SECOND_READ_AT);

        // when
        OffsetDateTime actual = validToOf(firstVersionId);

        // then
        assertThat(actual).isEqualTo(SECOND_READ_AT);
    }

    @Test
    void 해시가_예전_판본과_같아도_직전과_다르면_새_판본을_넣는다() {
        // given
        loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);
        loader.load(routeId, fourStops(), TIMETABLE_1650, SECOND_READ_AT);

        // when
        loader.load(routeId, threeStops(), TIMETABLE_1650, THIRD_READ_AT);

        // then
        assertThat(versionCount()).isEqualTo(3);
    }

    @Test
    void 시간표만_바뀌면_판본_id가_그대로다() {
        // given
        final long firstVersionId = loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);

        // when
        final long actual =
            loader.load(routeId, threeStops(), TIMETABLE_1650_LAST_BUS_MOVED, SECOND_READ_AT);

        // then
        assertThat(actual).isEqualTo(firstVersionId);
    }

    @Test
    void 시간표만_바뀌면_같은_판본의_막차가_고쳐진다() {
        // given
        final long versionId = loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);
        loader.load(routeId, threeStops(), TIMETABLE_1650_LAST_BUS_MOVED, SECOND_READ_AT);

        // when
        String actual = upLastDepartureTimeOf(versionId);

        // then
        assertThat(actual).isEqualTo(LAST_BUS_MOVED_TO);
    }

    @Test
    void 직전_판본과_정류소도_시간표도_같으면_판본을_더_만들지_않는다() {
        // given
        loader.load(routeId, threeStops(), TIMETABLE_1650, FIRST_READ_AT);

        // when
        loader.load(routeId, threeStops(), TIMETABLE_1650, SECOND_READ_AT);

        // then
        assertThat(versionCount()).isEqualTo(1);
    }

    @Test
    void 같은_정류소를_두_번_지나는_노선도_적재된다() {
        // given
        RouteStops passesTwice = RouteStops.from(1, List.of(
            new UpstreamRouteStop(1, STOP_208000069, "안양역"),
            new UpstreamRouteStop(2, STOP_208000069, "안양역")
        ));

        // when
        final long versionId = loader.load(routeId, passesTwice, TIMETABLE_1650, FIRST_READ_AT);

        // then
        assertThat(stopOrdersOf(versionId)).containsExactly(1, 2);
    }

    private RouteStops threeStops() {
        return RouteStops.from(TURN_SEQUENCE_3330, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역"),
            new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
            new UpstreamRouteStop(3, STOP_208000069, "안양역")
        ));
    }

    private RouteStops fourStops() {
        return RouteStops.from(TURN_SEQUENCE_3330, List.of(
            new UpstreamRouteStop(1, STOP_205000217, "범계역"),
            new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
            new UpstreamRouteStop(3, STOP_208000069, "안양역"),
            new UpstreamRouteStop(4, STOP_205000217, "범계역")
        ));
    }

    private long insertRoute() {
        return jdbcClient.sql("""
                INSERT INTO route (
                    public_route_id, source_id, source_route_id,
                    display_name, start_stop_name, end_stop_name
                ) VALUES (?, ?, ?, ?, ?, ?)
                RETURNING id
                """)
            .params(ROUTE_204000057, SOURCE_ID, ROUTE_204000057, "3330", "범계역", "강남역")
            .query(Long.class)
            .single();
    }

    private int versionCount() {
        entityManager.flush();
        return jdbcClient.sql("SELECT count(*) FROM route_version WHERE route_id = ?")
            .param(routeId)
            .query(Integer.class)
            .single();
    }

    private List<Integer> stopOrdersOf(
        final long versionId
    ) {
        entityManager.flush();
        return jdbcClient.sql("SELECT stop_order FROM route_stop WHERE route_version_id = ? ORDER BY stop_order")
            .param(versionId)
            .query(Integer.class)
            .list();
    }

    private OffsetDateTime validToOf(
        final long versionId
    ) {
        entityManager.flush();
        return jdbcClient.sql("SELECT valid_to FROM route_version WHERE id = ?")
            .param(versionId)
            .query(OffsetDateTime.class)
            .single();
    }

    private String upLastDepartureTimeOf(
        final long versionId
    ) {
        entityManager.flush();
        return jdbcClient.sql("SELECT up_last_departure_time FROM route_version WHERE id = ?")
            .param(versionId)
            .query(String.class)
            .single();
    }
}
