package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.domain.walking.StopWalkDataset;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDistance;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDistanceStore;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정류장 쌍 도보 거리 테이블을 묶음 단위로 바꾼다. 이 테이블을 읽는 것은 도보 거리 조회뿐이다.
 */
@Repository
public class JdbcStopWalkDistanceStore implements StopWalkDistanceStore {
    private static final int BATCH = 1_000;
    private final JdbcClient jdbc;
    private final JdbcTemplate batchJdbc;

    public JdbcStopWalkDistanceStore(JdbcClient jdbc, JdbcTemplate batchJdbc) {
        this.jdbc = jdbc;
        this.batchJdbc = batchJdbc;
    }

    @Override
    public boolean isCurrent(String checksum) {
        return jdbc.sql("SELECT EXISTS(SELECT 1 FROM stop_walk_dataset WHERE checksum=?)")
            .param(checksum).query(Boolean.class).single();
    }

    /**
     * 테이블을 잠근 뒤 같은 지문이 지금 적재돼 있는지 다시 확인하고, 아니면 거리와 적재 기록을 지우고 새 묶음으로 채운다.
     * 두 worker가 동시에 기동해도 한쪽만 바꾼다.
     */
    @Override
    @Transactional(readOnly = false)
    public void replace(StopWalkDataset dataset, OffsetDateTime loadedAt) {
        jdbc.sql("LOCK TABLE stop_walk_distance IN EXCLUSIVE MODE").update();
        if (isCurrent(dataset.checksum())) {
            return;
        }
        jdbc.sql("DELETE FROM stop_walk_distance").update();
        List<StopWalkDistance> pairs = dataset.pairs();
        for (int from = 0; from < pairs.size(); from += BATCH) {
            List<Object[]> rows = pairs.subList(from, Math.min(pairs.size(), from + BATCH)).stream()
                .map(pair -> new Object[] {pair.stationA(), pair.stationB(), pair.locationA().x(),
                    pair.locationA().y(), pair.locationB().x(), pair.locationB().y(), pair.walkMeters()})
                .toList();
            batchJdbc.batchUpdate("""
                INSERT INTO stop_walk_distance(station_a,station_b,a_x,a_y,b_x,b_y,walk_meters)
                VALUES (?,?,?,?,?,?,?)
                """, rows);
        }
        jdbc.sql("DELETE FROM stop_walk_dataset").update();
        jdbc.sql("INSERT INTO stop_walk_dataset(checksum,osm_base,pair_count,loaded_at) VALUES (?,?,?,?)")
            .params(dataset.checksum(), dataset.osmBase(), pairs.size(), loadedAt).update();
    }
}
