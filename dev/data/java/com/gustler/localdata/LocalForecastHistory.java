package com.gustler.localdata;

import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistorySeedReader;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** 새 모의 DB에서 처음부터 수집했다는 기준을 만든다. 운영 DB 복구에는 사용하지 않는다. */
final class LocalForecastHistory {
    record Anchor(long version, long batch, Instant at) {
    }

    static List<Anchor> prepare(Connection connection, List<String> supportedRoutes) throws Exception {
        return prepare(connection, supportedRoutes, Clock.systemUTC());
    }

    static List<Anchor> prepare(Connection connection, List<String> supportedRoutes, Clock clock) throws Exception {
        List<Anchor> anchors = new ArrayList<>();
        for (String name : supportedRoutes) {
            long version;
            try (var query = connection.prepareStatement("""
                SELECT v.id
                FROM route r
                JOIN route_version v ON v.route_id=r.id
                WHERE r.display_name=? AND v.valid_to IS NULL
                """)) {
                query.setString(1, name);
                try (var rows = query.executeQuery()) {
                    LocalData.require(rows.next(), "FORECAST_HISTORY_ROUTE");
                    version = rows.getLong(1);
                    LocalData.require(!rows.next(), "FORECAST_HISTORY_ROUTE");
                }
            }
            String key = "local-alighting-origin-" + version;
            try (var query = connection.prepareStatement("""
                SELECT id,response_received_at
                FROM observation_batch
                WHERE attempt_key=?
                """)) {
                query.setString(1, key);
                try (var rows = query.executeQuery()) {
                    if (rows.next()) {
                        anchors.add(new Anchor(version, rows.getLong(1),
                            rows.getObject(2, OffsetDateTime.class).toInstant()));
                        continue;
                    }
                }
            }
            try (var query = connection.prepareStatement("SELECT count(*) FROM observation_batch WHERE route_version_id=?")) {
                query.setLong(1, version);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    LocalData.require(rows.getLong(1) == 0, "FORECAST_HISTORY_BOOTSTRAP_REQUIRED");
                }
            }
            OffsetDateTime at = OffsetDateTime.now(clock).minusSeconds(1);
            try (var query = connection.prepareStatement("""
                INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                    response_received_at,completed_at,outcome,stored_rows,normalization_version,collection_strategy_version,ingestion_origin)
                VALUES(?,?,1,?,?,?,?,'SUCCESS_EMPTY',0,'local-synthetic-v1','local-synthetic-v1','LIVE')
                RETURNING id,response_received_at
                """)) {
                query.setLong(1, version);
                query.setObject(2, at);
                query.setString(3, key);
                query.setObject(4, at);
                query.setObject(5, at);
                query.setObject(6, at);
                try (var rows = query.executeQuery()) {
                    rows.next();
                    anchors.add(new Anchor(version, rows.getLong(1),
                        rows.getObject(2, OffsetDateTime.class).toInstant()));
                }
            }
        }
        return List.copyOf(anchors);
    }

    static void write(List<Anchor> anchors, Path directory) {
        var reader = new AlightingAwareHistorySeedReader(directory.toString(), 250000);
        anchors.forEach(anchor -> reader.write(anchor.version(), new AlightingAwareHistorySeedReader.Seed(
            List.of(), anchor.batch(), 0, anchor.at())));
    }
}
