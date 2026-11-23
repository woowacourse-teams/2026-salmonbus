package com.gustler.backend.api.station.infrastructure.jdbc;

import com.gustler.backend.api.station.application.StationReadRepository;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.api.station.domain.StationSearchQuery;
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
                   tile.completed_at AS verified_at,
                   tile.completed_at IS NOT NULL OR EXISTS (
                       SELECT 1 FROM bus_station s
                       WHERE s.tile_x = :x AND s.tile_y = :y AND s.baseline_date IS NOT NULL
                   ) AS ready
            FROM (SELECT 1) one
            LEFT JOIN bus_station_tile tile ON tile.tile_x = :x AND tile.tile_y = :y
            """)
            .param("x", id.x())
            .param("y", id.y())
            .query((row, index) -> new TileState(
                dateOf(row.getDate(1)),
                instantOf(row.getTimestamp(2)),
                row.getBoolean(3)
            ))
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
            state.ready(),
            stations,
            state.baselineDate(),
            state.verifiedAt()
        );
    }

    @Override
    public List<MapStation> search(StationSearchQuery query, int limit) {
        // 우리 노선 정류장은 좌표가 노선마다 다를 수 있어 노선이 가장 많은 좌표, 같으면 작은 좌표 하나를 고른다.
        // 노선 목록은 그 정류장을 지나는 모든 노선이며 정류장별 GROUP BY 한 번으로 모은다(행마다 부분 질의를 돌리면 제곱으로 느려진다).
        // bus_station 쪽 제외는 이미 구한 salmon을 NOT IN으로 걸어 해시로 처리한다. NOT EXISTS는 행마다 중첩 루프를 돌았다.
        // route_stop.stop_id가 NOT NULL이라 NOT IN이 NULL 때문에 전부 탈락하지 않는다. salmon은 SALMON_STOP_OF_STATION과 같은 조건이다.
        // LIMIT 뒤에 방면·지역 조인을 붙여 최대 limit행만 조인한다.
        String key = query.key();
        return jdbc.sql(
            """
            WITH salmon_group AS (
            """ + SALMON_STOPS + """
                GROUP BY stop.stop_id, location.x, location.y
            ),
            salmon AS (
                SELECT DISTINCT ON (g.stop_id) g.stop_id AS station_id, g.name, g.x, g.y, every.route_ids
                FROM salmon_group g
                JOIN (SELECT member.stop_id, array_agg(DISTINCT route_id ORDER BY route_id) AS route_ids
                      FROM salmon_group member, unnest(member.route_ids) AS route_id
                      GROUP BY member.stop_id) every ON every.stop_id = g.stop_id
                ORDER BY g.stop_id, cardinality(g.route_ids) DESC, g.x, g.y
            ),
            candidate AS (
                SELECT salmon.station_id, salmon.name, station_search_key(salmon.name) AS key,
                       salmon.x, salmon.y, salmon.route_ids
                FROM salmon
                UNION ALL
                SELECT s.station_id, s.name, s.name_key, s.x, s.y, NULL
                FROM bus_station s
                WHERE s.active
                  AND s.station_id NOT IN (SELECT salmon.station_id FROM salmon)
            ),
            matched AS (
                SELECT candidate.*,
                       CASE WHEN candidate.key = :key THEN 0 WHEN candidate.key LIKE :prefix THEN 1 ELSE 2 END AS grade
                FROM candidate
                WHERE candidate.key LIKE :contains
                ORDER BY grade, (candidate.route_ids IS NULL), candidate.key COLLATE "C", candidate.station_id
                LIMIT :limit
            )
            SELECT matched.station_id, matched.name, matched.x, matched.y,
                   info.mobile_no, info.region_name, direction.name AS direction, matched.route_ids
            FROM matched
            LEFT JOIN bus_station info ON info.station_id = matched.station_id AND info.active
            """ + DIRECTION_OF_SALMON_STOP.formatted("CASE WHEN matched.route_ids IS NOT NULL THEN matched.station_id END") + """
            ORDER BY matched.grade, (matched.route_ids IS NULL), matched.key COLLATE "C", matched.station_id
            """
        )
            .param("key", key)
            .param("prefix", key + "%")
            .param("contains", "%" + key + "%")
            .param("limit", limit)
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

    private record TileState(LocalDate baselineDate, Instant verifiedAt, boolean ready) {
    }

    private static List<String> routeIdsOf(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }
}
