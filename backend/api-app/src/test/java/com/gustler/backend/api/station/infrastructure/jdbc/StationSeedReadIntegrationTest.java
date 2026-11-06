package com.gustler.backend.api.station.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.station.application.StationReadRepository;
import com.gustler.backend.api.station.application.StationTileQueryService;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.support.IntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class StationSeedReadIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");

    @Autowired
    private StationReadRepository repository;

    @Autowired
    private StationTileQueryService service;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void seed() {
        jdbc.sql("TRUNCATE bus_station,bus_station_tile,station_seed_dataset").update();
        jdbc.sql("TRUNCATE route CASCADE").update();
        jdbc.sql("INSERT INTO station_seed_dataset VALUES ('GBIS',DATE '2026-10-20',repeat('a',64),2,?)")
            .param(Timestamp.from(NOW))
            .update();
    }

    @Test
    void 실조회_이력_없이_기초명부를_즉시_제공하고_서울도_조회한다() {
        // given
        insertStation("101000148", 126.983, 37.56, "서울", true);

        // when
        StationTile tile = repository.tile(StationTileId.of(126.983, 37.56));
        Optional<StationInfo> station = repository.info("101000148");

        // then
        assertSoftly(softly -> {
            softly.assertThat(tile.ready()).isTrue();
            softly.assertThat(tile.verifiedAt()).isNull();
            softly.assertThat(tile.baselineDate()).isEqualTo(LocalDate.of(2026, 10, 20));
            softly.assertThat(tile.stations()).extracting(MapStation::stationId).contains("101000148");
            softly.assertThat(station).isPresent();
        });
    }

    @Test
    void 프론트_공개경계_밖의_등록자료도_백엔드는_제공한다() {
        // given
        insertStation("241002020", 127.13, 35.82, "전주", true);
        StationTileId requested = StationTileId.of(127.13, 35.82);

        // when
        StationTile tile = service.get(14, requested.x(), requested.y());

        // then
        assertThat(tile.stations()).extracting(MapStation::stationId).contains("241002020");
    }

    @Test
    void 비활성_정류장은_목록과_상세에서_제외한다() {
        // given
        insertStation("206000533", 127.11, 37.39, "성남", false);

        // when
        StationTile tile = repository.tile(StationTileId.of(127.11, 37.39));
        Optional<StationInfo> station = repository.info("206000533");

        // then
        assertSoftly(softly -> {
            softly.assertThat(tile.stations()).noneMatch(stop -> stop.stationId().equals("206000533"));
            softly.assertThat(station).isEmpty();
        });
    }

    @Test
    void 우리_노선_정류장은_노선_좌표의_칸에만_나오고_명부의_지역을_붙인다() {
        // given
        insertStation("206000532", 127.14, 37.39, "성남", true);
        insertSalmonStop("206000532", 127.11, 37.39);
        StationTileId salmonTile = StationTileId.of(127.11, 37.39);
        StationTileId sourceTile = StationTileId.of(127.14, 37.39);

        // when
        StationTile salmon = repository.tile(salmonTile);
        StationTile source = repository.tile(sourceTile);

        // then
        assertSoftly(softly -> {
            softly.assertThat(salmonTile).isNotEqualTo(sourceTile);
            softly.assertThat(salmon.stations())
                .filteredOn(stop -> stop.stationId().equals("206000532"))
                .singleElement()
                .satisfies(stop -> {
                    assertThat(stop.name()).isEqualTo("노선정류장");
                    assertThat(stop.regionName()).isEqualTo("성남");
                    assertThat(stop.salmonRouteIds()).containsExactly("204000057");
                });
            softly.assertThat(source.stations()).noneMatch(stop -> stop.stationId().equals("206000532"));
        });
    }

    private void insertStation(
        String identifier,
        final double longitude,
        final double latitude,
        String region,
        final boolean active
    ) {
        StationTileId tile = StationTileId.of(longitude, latitude);
        jdbc.sql("""
            INSERT INTO bus_station(station_id,name,x,y,region_name,tile_x,tile_y,first_seen_at,last_seen_at,active)
            VALUES (?,'기초정류장',?,?,?,?,?,?,?,?)
            """)
            .params(identifier, longitude, latitude, region, tile.x(), tile.y(),
                Timestamp.from(NOW), Timestamp.from(NOW), active)
            .update();
    }

    private void insertSalmonStop(String identifier, final double longitude, final double latitude) {
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
            VALUES (?,1,?,'노선정류장','UP',true)
            """).params(version, identifier).update();
        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,1,?,'2026-10-13T00:00:00Z',?,?)
            """).params(version, identifier, longitude, latitude).update();
    }
}
