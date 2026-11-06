package com.gustler.backend.api.station.infrastructure.jdbc;

import com.gustler.backend.api.station.application.StationReadRepository;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.api.station.domain.StationTile;
import com.gustler.backend.station.contract.StationTileId;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 정류장 원본(bus_station)과 우리 노선 현재 판본 정류장(route_stop)을 칸·정류장 단위로 읽는다.
 *
 * <p>우리 노선 정류장은 노선 지도·판정보드와 같은 이름·좌표를 쓰려고 route_stop을 앞세운다. 칸도 route_stop 좌표로 정하고,
 * 모바일 번호·지역만 bus_station에서 붙인다.
 * 지역별 노출 정책은 프론트가 정한다. 저장된 활성 정류장은 지역에 관계없이 제공한다.
 */
@Repository
public class JdbcStationReadRepository implements StationReadRepository {

    private static final String SALMON_STOPS = """
        SELECT stop.stop_id, min(stop.name) AS name, location.x, location.y,
               array_agg(DISTINCT route.public_route_id ORDER BY route.public_route_id) AS route_ids
        FROM route_stop stop
        JOIN route_version version ON version.id = stop.route_version_id AND version.valid_to IS NULL
        JOIN route route ON route.id = version.route_id AND route.source_id = 'GBIS'
        JOIN LATERAL (SELECT history.x, history.y FROM route_stop_location_history history
            WHERE history.route_version_id = stop.route_version_id AND history.stop_order = stop.stop_order
            ORDER BY history.observed_at DESC LIMIT 1) location ON true
        WHERE stop.boarding_allowed
        """;

    /**
     * bus_station 행 s가 우리 노선 현재 판본 승차 정류장인지 본다. 그런 정류장의 칸은 route_stop 좌표가 정하므로
     * bus_station 쪽에서는 빼서 한 정류장이 두 칸에 나오지 않게 한다. 조건은 SALMON_STOPS와 같다.
     */
    private static final String SALMON_STOP_OF_STATION = """
        SELECT 1 FROM route_stop stop
        JOIN route_version version ON version.id = stop.route_version_id AND version.valid_to IS NULL
        JOIN route route ON route.id = version.route_id AND route.source_id = 'GBIS'
        WHERE stop.stop_id = s.station_id AND stop.boarding_allowed
          AND EXISTS (SELECT 1 FROM route_stop_location_history history
              WHERE history.route_version_id = stop.route_version_id AND history.stop_order = stop.stop_order)
        """;

    /**
     * 우리 노선 정류장의 방면. 방문마다 다음 정차 정류장(미정차 건너뜀) 이름을 구하고,
     * 가장 많은 방문이 가리키는 이름을 고른다. 같으면 노선 ID가 작은 쪽이다. 종점 방문은 다음 정류장이 없어 세지 않는다.
     */
    private static final String DIRECTION_OF_SALMON_STOP = """
        LEFT JOIN LATERAL (
            SELECT next.name FROM route_stop here
            JOIN route_version here_version ON here_version.id = here.route_version_id AND here_version.valid_to IS NULL
            JOIN route here_route ON here_route.id = here_version.route_id AND here_route.source_id = 'GBIS'
            JOIN LATERAL (SELECT following.name FROM route_stop following
                WHERE following.route_version_id = here.route_version_id AND following.stop_order > here.stop_order
                  AND following.boarding_allowed
                ORDER BY following.stop_order LIMIT 1) next ON true
            WHERE here.stop_id = %s AND here.boarding_allowed
            GROUP BY next.name
            ORDER BY count(*) DESC, min(here_route.public_route_id)
            LIMIT 1
        ) direction ON true
        """;

    private final JdbcClient jdbc;

    public JdbcStationReadRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public StationTile tile(StationTileId id) {
        JdbcStationReadRepository.TileState state = jdbc.sql("""
            SELECT (SELECT data_date FROM station_seed_dataset WHERE source_id = 'GBIS') AS baseline_date,
                   (SELECT completed_at FROM bus_station_tile WHERE tile_x = ? AND tile_y = ?) AS verified_at
            """)
            .params(id.x(), id.y())
            .query((row, index) -> new TileState(dateOf(row.getDate(1)), instantOf(row.getTimestamp(2))))
            .single();
        List<MapStation> stations = jdbc.sql(
            """
            WITH salmon AS (
            """ + SALMON_STOPS + """
                  AND location.x >= :west AND location.x < :east AND location.y > :south AND location.y <= :north
                GROUP BY stop.stop_id, location.x, location.y
            ),
            source AS (
                SELECT s.station_id, s.name, s.x, s.y FROM bus_station s
                WHERE s.tile_x = :x AND s.tile_y = :y AND s.active
                  AND NOT EXISTS (""" + SALMON_STOP_OF_STATION + """
                  )
            ),
            tile_station AS (
                SELECT salmon.stop_id AS station_id, salmon.name, salmon.x, salmon.y,
                       salmon.stop_id AS salmon_stop_id, salmon.route_ids
                FROM salmon
                UNION ALL
                SELECT source.station_id, source.name, source.x, source.y, NULL, NULL
                FROM source
            )
            SELECT tile_station.station_id, tile_station.name, tile_station.x, tile_station.y,
                   info.mobile_no, info.region_name, direction.name AS direction, tile_station.route_ids
            FROM tile_station
            LEFT JOIN bus_station info ON info.station_id = tile_station.station_id AND info.active
            """ + DIRECTION_OF_SALMON_STOP.formatted("tile_station.salmon_stop_id") + """
            ORDER BY 1
            """
        )
            .param("x", id.x())
            .param("y", id.y())
            .param("west", id.west())
            .param("east", id.east())
            .param("south", id.south())
            .param("north", id.north())
            .query((row, index) -> new MapStation(
                row.getString(1),
                row.getString(2),
                row.getDouble(3),
                row.getDouble(4),
                row.getString(5),
                row.getString(6),
                row.getString(7),
                routeIdsOf(row.getArray(8))
            ))
            .list();
        return new StationTile(
            id,
            state.baselineDate() != null || state.verifiedAt() != null,
            stations,
            state.baselineDate(),
            state.verifiedAt()
        );
    }

    @Override
    public Optional<StationInfo> info(String stationId) {
        Optional<StationInfo> salmon = jdbc.sql(
            "SELECT salmon.stop_id, salmon.name, salmon.x, salmon.y, direction.name FROM (" + SALMON_STOPS + """
              AND stop.stop_id = :id
            GROUP BY stop.stop_id, location.x, location.y
            LIMIT 1) salmon
            """ + DIRECTION_OF_SALMON_STOP.formatted("salmon.stop_id")
        )
            .param("id", stationId)
            .query((row, index) -> new StationInfo(
                row.getString(1),
                row.getString(2),
                row.getDouble(3),
                row.getDouble(4),
                null,
                null,
                row.getString(5)
            ))
            .optional();
        Optional<StationInfo> source = jdbc.sql("""
            SELECT s.station_id, s.name, s.x, s.y, s.mobile_no, s.region_name FROM bus_station s
            WHERE s.station_id = ? AND s.active
            """)
            .param(stationId)
            .query((row, index) -> new StationInfo(
                row.getString(1),
                row.getString(2),
                row.getDouble(3),
                row.getDouble(4),
                row.getString(5),
                row.getString(6),
                null
            ))
            .optional();
        if (salmon.isPresent()) {
            StationInfo route = salmon.get();
            return Optional.of(new StationInfo(
                route.stationId(),
                route.name(),
                route.x(),
                route.y(),
                source.map(StationInfo::mobileNo).orElse(null),
                source.map(StationInfo::regionName).orElse(null),
                route.direction()
            ));
        }
        return source;
    }

    private static LocalDate dateOf(Date date) {
        if (date == null) {
            return null;
        }
        return date.toLocalDate();
    }

    private static Instant instantOf(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant();
    }

    private record TileState(LocalDate baselineDate, Instant verifiedAt) {
    }

    private static List<String> routeIdsOf(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }
}
