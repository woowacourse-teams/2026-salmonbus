package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.UpstreamRouteStop;
import com.gustler.backend.support.IntegrationTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class RouteVersionConcurrencyTest {

    private static final OffsetDateTime FIRST = OffsetDateTime.parse("2026-09-01T09:00:00+09:00");
    private static final OffsetDateTime SECOND = FIRST.plusDays(1);
    private static final RouteTimetable TIMETABLE = new RouteTimetable("05:00", "22:00", "05:30", "22:30");

    @Autowired
    private RouteVersionLoader loader;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate transaction;

    private long routeId;

    @BeforeEach
    void registerRoute() {
        String sourceId = UUID.randomUUID().toString().substring(0, 20);
        routeId = jdbc.sql("""
            INSERT INTO route(public_route_id, source_id, source_route_id,
                              display_name, start_stop_name, end_stop_name)
            VALUES (?, 'GBIS', ?, '동시성 검증', '기점', '종점') RETURNING id
            """).params(sourceId, sourceId).query(Long.class).single();
    }

    @AfterEach
    void removeRoute() {
        transaction.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM route_stop WHERE route_version_id IN (SELECT id FROM route_version WHERE route_id = ?)")
                .param(routeId).update();
            jdbc.sql("DELETE FROM route_version WHERE route_id = ?").param(routeId).update();
            jdbc.sql("DELETE FROM route WHERE id = ?").param(routeId).update();
        });
    }

    @Test
    void 최초_등록을_동시에_요청해도_같은_버전_하나를_반환한다() throws Exception {
        List<Long> versions = loadTogether(stops("종점"), FIRST);

        assertThat(versions.getFirst()).isEqualTo(versions.getLast());
        assertThat(versionCount()).isEqualTo(1);
    }

    @Test
    void 같은_개편을_동시에_적용해도_기간이_겹치지_않는_새_버전_하나만_연다() throws Exception {
        long previous = loader.load(routeId, stops("종점"), TIMETABLE, FIRST);

        List<Long> versions = loadTogether(stops("변경된 종점"), SECOND);

        assertThat(versions.getFirst()).isEqualTo(versions.getLast()).isNotEqualTo(previous);
        assertThat(versionCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT valid_to FROM route_version WHERE id = ?")
            .param(previous).query(OffsetDateTime.class).single()).isEqualTo(SECOND);
    }

    @Test
    void 새_정류장_저장이_실패하면_이전_버전_종료도_롤백한다() {
        long previous = loader.load(routeId, stops("종점"), TIMETABLE, FIRST);

        assertThatThrownBy(() -> loader.load(routeId, stops("긴".repeat(61)), TIMETABLE, SECOND))
            .isInstanceOf(RuntimeException.class);

        assertThat(versionCount()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT valid_to IS NULL FROM route_version WHERE id = ?")
            .param(previous).query(Boolean.class).single()).isTrue();
    }

    private List<Long> loadTogether(RouteStops stops, OffsetDateTime at) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> {
                start.await();
                return loader.load(routeId, stops, TIMETABLE, at);
            });
            var second = threads.submit(() -> {
                start.await();
                return loader.load(routeId, stops, TIMETABLE, at);
            });
            start.countDown();
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }

    private int versionCount() {
        return jdbc.sql("SELECT count(*) FROM route_version WHERE route_id = ?")
            .param(routeId).query(Integer.class).single();
    }

    private static RouteStops stops(String lastStopName) {
        return RouteStops.from(null, List.of(
            new UpstreamRouteStop(1, "100", "기점"),
            new UpstreamRouteStop(2, "200", lastStopName)));
    }
}
