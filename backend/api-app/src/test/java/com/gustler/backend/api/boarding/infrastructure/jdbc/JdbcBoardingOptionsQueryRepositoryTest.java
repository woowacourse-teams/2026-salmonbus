package com.gustler.backend.api.boarding.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.application.WalkAccessFinder;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.infrastructure.jdbc.JdbcBoardingOptionsQueryRepositoryTest.Config;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.support.PostgresTestContainer;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(classes = Config.class)
class JdbcBoardingOptionsQueryRepositoryTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class, JdbcBoardingOptionsQueryRepository.class, JdbcWalkPairReader.class,
        WalkAccessFinder.class, BoardingOptionsQueryService.class})
    static class Config {
    }

    @Autowired
    JdbcClient jdbc;
    @Autowired
    BoardingOptionsQueryService service;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        jdbc.sql("DELETE FROM stop_walk_distance").update();
        final long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id
            """).query(Long.class).single();
        final long version = jdbc.sql("""
            INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from)
            VALUES (?,40,?,'2026-10-13T00:00:00Z') RETURNING id
            """).params(route, "a".repeat(64)).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES (?,4,'205000222','동분당포레스트','UP',true),(?,5,'205000231','동강프라자앞','UP',true),
                   (?,81,'205000226','동강프라자','DOWN',true)
            """).params(version, version, version).update();
        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,4,'205000222','2026-10-13T00:00:00Z',127.1530667,37.4146),
                   (?,5,'205000231','2026-10-13T00:00:00Z',127.1487833,37.4146333),
                   (?,81,'205000226','2026-10-13T00:00:00Z',127.14865,37.4145333)
            """).params(version, version, version).update();
        jdbc.sql("""
            INSERT INTO stop_walk_distance(station_a,station_b,a_x,a_y,b_x,b_y,walk_meters)
            VALUES ('205000222','205000231',127.1530667,37.4146,127.1487833,37.4146333,420),
                   ('205000226','205000231',127.14865,37.4145333,127.1487833,37.4146333,103)
            """).update();
    }

    @Test
    void 기준_정류장이_쌍의_앞에_있든_뒤에_있든_저장한_도보_거리로_후보를_찾는다() {
        // when
        BoardingOptions result = service.get(new BoardingOptionsQuery(new RouteId("204000057"), "205000231", null, null,
            Set.of(AccessMode.WALK), 15, 10, EnumSet.allOf(StopRelation.class), true));

        // then
        assertThat(result.options()).extracting(option -> option.stop().stationId() + "@" + option.sequenceOffset()
            + "=" + option.accesses().getFirst().legs().getFirst().distanceMeters())
            .containsExactly("205000226@76=103", "205000222@-1=420");
    }
}
