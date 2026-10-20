package com.gustler.backend.arrivals.infrastructure.jdbc;

import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 정류장 지원 여부와 도착정보 수신 스냅샷을 저장·조회한다.
 * 만료 자료 정리는 전달된 기준 시각보다 오래된 자료만 대상으로 한다.
 */
public class JdbcArrivalStore implements ArrivalStore {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public JdbcArrivalStore(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public boolean supported(String stationId) {
        return jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM route_stop stop
                JOIN route_version version ON version.id = stop.route_version_id
                JOIN route route ON route.id = version.route_id
                WHERE stop.stop_id = ? AND version.valid_to IS NULL AND route.source_id = 'GBIS')
            """).param(stationId).query(Boolean.class).single();
    }

    @Override
    public Optional<ArrivalSnapshot> latest(String stationId) {
        return jdbc.sql("""
            SELECT payload::text FROM arrival_snapshot
            WHERE provider = ? AND operation = ? AND station_id = ?
            ORDER BY received_at DESC, stored_at DESC LIMIT 1
            """).params(ArrivalSnapshot.PROVIDER, ArrivalSnapshot.OPERATION, stationId)
            .query((row, index) -> json.readValue(row.getString(1), ArrivalSnapshot.class)).optional();
    }

    @Override
    @Transactional(readOnly = false)
    public void save(ArrivalSnapshot snapshot) {
        String payload = json.writeValueAsString(snapshot);
        String digest = digest(payload);
        final int changed = jdbc.sql("""
            INSERT INTO arrival_snapshot(id, provider, operation, station_id, received_at,
                refresh_after_at, stale_at, normalization_version, payload_digest, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (id) DO NOTHING
            """).params(snapshot.id(), ArrivalSnapshot.PROVIDER, ArrivalSnapshot.OPERATION,
            snapshot.stationId(), Timestamp.from(snapshot.receivedAt()), Timestamp.from(snapshot.refreshAfterAt()),
            Timestamp.from(snapshot.staleAt()), ArrivalSnapshot.NORMALIZATION_VERSION, digest, payload).update();
        if (changed == 0) {
            String previous = jdbc.sql("SELECT payload_digest FROM arrival_snapshot WHERE id = ?")
                .param(snapshot.id()).query(String.class).single();
            if (!digest.equals(previous)) {
                throw new IllegalStateException("같은 수집 ID에 서로 다른 응답을 저장할 수 없다");
            }
        }
    }

    @Override
    @Transactional(readOnly = false)
    public int purgeExpiredBefore(Instant cutoff, final int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("삭제 건수는 1~1000건이어야 한다");
        }
        return jdbc.sql("""
            DELETE FROM arrival_snapshot WHERE id IN (
                SELECT id FROM arrival_snapshot WHERE stale_at < ? ORDER BY stale_at LIMIT ?)
            """).params(Timestamp.from(cutoff), limit).update();
    }

    private static String digest(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
