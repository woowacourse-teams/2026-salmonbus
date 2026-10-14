package com.gustler.backend.api.route.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.application.RouteGeometryQueryService;
import com.gustler.backend.support.PostgresTestContainer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = RouteGeometryQueryRepositoryTest.Config.class)
class RouteGeometryQueryRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcRouteGeometryQueryRepository.class, RouteGeometryQueryService.class})
    static class Config { @Bean ObjectMapper objectMapper() { return new ObjectMapper(); } }
    @Autowired JdbcClient jdbc;
    @Autowired RouteGeometryQueryService service;

    @Test
    void 모델과_차량관측_없이_정적자료를_제공하고_좌표변경을_판본에_반영한다() {
        long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        long version = jdbc.sql("INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from) VALUES (?,1,?,'2026-10-13T00:00:00Z') RETURNING id")
            .params(route, "a".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES (?,1,'206000637','A','UP',true),(?,2,'206000637','A','DOWN',true)
            """).params(version, version).update();
        var absent = service.get(new RouteId("204000057"), null);
        assertThat(absent.status().name()).isEqualTo("UNAVAILABLE");
        assertThat(absent.points()).isEmpty();
        assertThat(absent.busStops()).hasSize(2);
        jdbc.sql("""
            INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points)
            VALUES (?,?,'2026-10-14T00:00:00Z',?, '[{"x":127,"y":37},{"x":127.2,"y":37.2}]')
            """).params(UUID.randomUUID(), version, "b".repeat(64)).update();
        assertThat(service.get(new RouteId("204000057"), version).status().name()).isEqualTo("PARTIAL");
        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,1,'206000637','2026-10-14T00:00:00Z',127.1,37.1),
                   (?,2,'206000637','2026-10-14T00:00:00Z',127.15,37.15)
            """).params(version, version).update();
        var complete = service.get(new RouteId("204000057"), version);
        assertThat(complete.status().name()).isEqualTo("READY");
        assertThat(complete.busStops()).extracting(stop -> stop.stopSequence()).containsExactly(1, 2);
        String revision = complete.revision();
        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,1,'206000637','2026-10-14T00:01:00Z',127.11,37.11)
            """).param(version).update();
        var moved = service.get(new RouteId("204000057"), version);
        assertThat(moved.referenceVersionId()).isEqualTo(version);
        assertThat(moved.revision()).isNotEqualTo(revision);
        assertThat(moved.busStops().getFirst().point().x()).isEqualTo(127.11);
        assertThatThrownBy(() -> service.get(new RouteId("204000057"), version + 1))
            .isInstanceOf(RouteGeometryReferenceConflictException.class);
    }
}
