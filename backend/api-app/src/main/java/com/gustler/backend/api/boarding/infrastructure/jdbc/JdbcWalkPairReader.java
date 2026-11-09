package com.gustler.backend.api.boarding.infrastructure.jdbc;

import com.gustler.backend.api.boarding.application.WalkPairReader;
import com.gustler.backend.api.boarding.domain.WalkPair;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code stop_walk_distance}에서 정류장이 한쪽에 들어간 쌍을 읽는다. 쌍은 정류장 ID 순서로 한 번만 저장돼 있다.
 */
@Repository
public class JdbcWalkPairReader implements WalkPairReader {
    private final JdbcClient jdbc;

    public JdbcWalkPairReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<WalkPair> walkPairs(String stationId) {
        return jdbc.sql("""
            SELECT station_a,station_b,a_x,a_y,b_x,b_y,walk_meters FROM stop_walk_distance WHERE station_a=:station
            UNION ALL
            SELECT station_a,station_b,a_x,a_y,b_x,b_y,walk_meters FROM stop_walk_distance WHERE station_b=:station
            """).param("station", stationId).query((row, index) -> new WalkPair(row.getString(1), row.getString(2),
            row.getDouble(3), row.getDouble(4), row.getDouble(5), row.getDouble(6), row.getInt(7))).list();
    }
}
