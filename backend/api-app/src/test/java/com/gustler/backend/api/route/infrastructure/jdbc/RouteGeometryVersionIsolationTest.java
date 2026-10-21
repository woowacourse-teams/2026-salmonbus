package com.gustler.backend.api.route.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gustler.backend.api.http.ApiExceptionHandler;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.api.route.controller.RouteGeometryController;
import com.gustler.backend.api.route.domain.RouteGeometry;
import com.gustler.backend.api.route.domain.RouteGeometry.Availability;
import com.gustler.backend.api.route.domain.RouteGeometry.Point;
import com.gustler.backend.api.route.domain.RouteGeometry.Stop;
import com.gustler.backend.api.route.infrastructure.jdbc.RouteGeometryVersionIsolationTest.Config;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = Config.class)
@Transactional
class RouteGeometryVersionIsolationTest {
    private static final String ROUTE_ID = "204000057";
    private static final OffsetDateTime FIRST_AT = OffsetDateTime.parse("2026-10-14T00:00:00Z");
    private static final OffsetDateTime SECOND_AT = FIRST_AT.plusDays(1);

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteGeometryQueryRepository.class, RouteGeometryQueryService.class})
    static class Config {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private RouteGeometryQueryService service;

    @Test
    void 판본이_바뀌면_같은_정류장_ID라도_옛_좌표와_형상을_재사용하지_않는다() {
        // when
        final long route = insertRoute();
        final long oldVersion = insertVersion(route, FIRST_AT, "a");
        insertCoordinates(oldVersion, FIRST_AT, 127, 37);
        insertShape(oldVersion, FIRST_AT, points(127, 37));
        RouteGeometry old = geometry();

        // then
        assertThat(old.status()).isEqualTo(Availability.READY);

        jdbc.sql("UPDATE route_version SET valid_to=? WHERE id=?").params(SECOND_AT, oldVersion).update();
        final long currentVersion = insertVersion(route, SECOND_AT, "b");

        RouteGeometry uncollected = geometry();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(uncollected.referenceVersionId()).isEqualTo(currentVersion);
            softly.assertThat(uncollected.status()).isEqualTo(Availability.UNAVAILABLE);
            softly.assertThat(uncollected.reason()).isEqualTo("NOT_COLLECTED");
        }
        assertThat(uncollected.points()).isEmpty();
        assertThat(uncollected.paths()).isNull();
        assertThat(uncollected.busStops()).hasSize(3).allMatch(stop -> stop.point() == null);
        assertThat(uncollected.boundary()).isNull();

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.get(new RouteId(ROUTE_ID), oldVersion));

        // then
        assertThat(phaseFailure1)
            .isNotNull()
            .isInstanceOf(RouteGeometryReferenceConflictException.class);

        insertCoordinates(currentVersion, SECOND_AT, 128, 38);
        RouteGeometry stopsOnly = geometry();
        assertThat(stopsOnly.status()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(stopsOnly.points()).isEmpty();
        assertThat(stopsOnly.busStops()).extracting(Stop::point)
            .containsExactlyElementsOf(points(128, 38));

        insertShape(currentVersion, SECOND_AT, points(128, 38));
        RouteGeometry complete = geometry();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(complete.referenceVersionId()).isEqualTo(currentVersion);
            softly.assertThat(complete.status()).isEqualTo(Availability.READY);
            softly.assertThat(complete.points()).containsExactlyElementsOf(points(128, 38));
        }
        assertThat(complete.paths()).isNotNull();
        assertThat(complete.revision()).isNotEqualTo(old.revision());
    }

    @Test
    void 최신_정류장_좌표가_유효하지_않으면_이전_좌표로_가리지_않고_부분제공한다() {
        // when
        final long version = insertVersion(insertRoute(), FIRST_AT, "a");
        insertCoordinates(version, FIRST_AT, 127, 37);
        insertShape(version, FIRST_AT, points(127, 37));
        RouteGeometry complete = geometry();

        // then
        assertThat(complete.paths()).isNotNull();

        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,2,'206000638',?,0,0)
            """).params(version, FIRST_AT.plusMinutes(1)).update();

        RouteGeometry partial = geometry();
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(partial.referenceVersionId()).isEqualTo(version);
            softly.assertThat(partial.status()).isEqualTo(Availability.PARTIAL);
            softly.assertThat(partial.reason()).isEqualTo("MISSING_STOP_COORDINATES");
        }
        assertThat(partial.busStops()).hasSize(3);
        assertThat(partial.busStops().get(1).point()).isNull();
        assertThat(partial.busStops().getFirst().point()).isEqualTo(complete.busStops().getFirst().point());
        assertThat(partial.busStops().getLast().point()).isEqualTo(complete.busStops().getLast().point());
        assertThat(partial.points()).isEqualTo(complete.points());
        assertThat(partial.paths()).isNull();
        assertThat(partial.revision()).isNotEqualTo(complete.revision());
    }

    @Test
    void 최신_저장형상이_손상되면_이전_ETag를_받아도_503으로_응답한다() throws Exception {
        // given
        final long version = insertVersion(insertRoute(), FIRST_AT, "a");
        insertCoordinates(version, FIRST_AT, 127, 37);
        insertShape(version, FIRST_AT, points(127, 37));
        String etag = "W/\"g-" + geometry().revision() + "\"";
        jdbc.sql("""
            INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points)
            VALUES (?,?,?,?, '[{"x":999,"y":37},{"x":127,"y":37}]')
            """).params(UUID.randomUUID(), version, FIRST_AT.plusMinutes(1), "b".repeat(64)).update();

        // when
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RouteGeometryController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v2/routes/" + ROUTE_ID + "/geometry").header("If-None-Match", etag))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("ETag"));
    }

    private RouteGeometry geometry() {
        return service.get(new RouteId(ROUTE_ID), null);
    }

    private long insertRoute() {
        return jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES (?,'GBIS',?,'3330','A','B') RETURNING id
            """).params(ROUTE_ID, ROUTE_ID).query(Long.class).single();
    }

    private long insertVersion(final long route, OffsetDateTime validFrom, String digest) {
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from)
            VALUES (?,2,?,?) RETURNING id
            """).params(route, digest.repeat(64), validFrom).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES (?,1,'206000637','A','UP',true),
                   (?,2,'206000638','B','UP',true),
                   (?,3,'206000637','A','DOWN',true)
            """).params(version, version, version).update();
        return version;
    }

    private void insertCoordinates(
        final long version,
        OffsetDateTime observedAt,
        final double x,
        final double y
    ) {
        List<Point> coordinates = points(x, y);
        for (int index = 0; index < coordinates.size(); index++) {
            Point point = coordinates.get(index);
            jdbc.sql("""
                INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
                VALUES (?,?,?,?,?,?)
                """).params(version, index + 1, stopIdOf(index), observedAt,
                point.x(), point.y()).update();
        }
    }

    private void insertShape(final long version, OffsetDateTime receivedAt, List<Point> points) {
        jdbc.sql("""
            INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points)
            VALUES (?,?,?,?,?::jsonb)
            """).params(UUID.randomUUID(), version, receivedAt, "c".repeat(64),
            new ObjectMapper().writeValueAsString(points)).update();
    }

    // 판본 격리 계약을 확인하는 작은 합성 자료이며 실제 도로 형상 fixture가 아니다.
    private static List<Point> points(final double x, final double y) {
        return List.of(new Point(x, y), new Point(x + 0.001, y),
            new Point(x + 0.002, y));
    }

    private static String stopIdOf(final int index) {
        if (index == 1) {
            return "206000638";
        }
        return "206000637";
    }
}
