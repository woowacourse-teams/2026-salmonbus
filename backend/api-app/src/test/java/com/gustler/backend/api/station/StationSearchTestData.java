package com.gustler.backend.api.station;

import com.gustler.backend.station.contract.StationTileId;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 정류장 이름 검색 테스트가 같이 쓰는 자료 입력기.
 */
public final class StationSearchTestData {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");
    private static final String OBSERVED = "2026-10-13T00:00:00Z";

    private final JdbcClient jdbc;

    public StationSearchTestData(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void clear() {
        jdbc.sql("TRUNCATE bus_station,bus_station_tile,station_seed_dataset").update();
        jdbc.sql("TRUNCATE route CASCADE").update();
    }

    public void station(String stationId, String name, boolean active) {
        StationTileId tile = StationTileId.of(127.11, 37.39);
        jdbc.sql("""
            INSERT INTO bus_station(station_id,name,x,y,mobile_no,region_name,tile_x,tile_y,first_seen_at,last_seen_at,
                                    active)
            VALUES (?,?,127.11,37.39,'07489','성남',?,?,?,?,?)
            """)
            .params(stationId, name, tile.x(), tile.y(), Timestamp.from(NOW), Timestamp.from(NOW), active)
            .update();
    }

    public void station(String stationId, String name) {
        station(stationId, name, true);
    }

    /** 노선 한 개에 정류장을 하나 싣는다. 같은 stationId를 다른 노선에 또 실을 수 있다. */
    public void salmonStop(String publicRouteId, String stationId, String name, double x, double y) {
        long route = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES (?,'GBIS',?,?,'A','B') RETURNING id
            """).params(publicRouteId, publicRouteId, publicRouteId.substring(6)).query(Long.class).single();
        long version = jdbc.sql("""
            INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from)
            VALUES (?,40,?,?::timestamptz) RETURNING id
            """).params(route, "a".repeat(64), OBSERVED).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            VALUES (?,1,?,?,'UP',true),(?,2,'900000001','다음정류장','UP',true)
            """).params(version, stationId, name, version).update();
        jdbc.sql("""
            INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
            VALUES (?,1,?,?::timestamptz,?,?)
            """).params(version, stationId, OBSERVED, x, y).update();
    }
}
