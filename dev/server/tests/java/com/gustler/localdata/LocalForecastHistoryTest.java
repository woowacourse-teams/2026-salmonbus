package com.gustler.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistorySeedReader;
import com.gustler.backend.support.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalForecastHistoryTest {
    @TempDir Path directory;

    @Test
    void 새_모의_DB의_빈_시작점을_한번만_저장하고_복구한다() throws Exception {
        try (var database=TestDatabase.create();var connection=database.connection()) {
            Flyway.configure().dataSource(database.jdbcUrl(),database.username(),database.password()).load().migrate();
            var route=RouteDataset.read(Path.of(System.getProperty("dev.data.directory"),"routes.json")).routes().getFirst();
            long version=LocalData.seedRoute(connection,route);
            var anchors=LocalForecastHistory.prepare(connection,List.of(route.displayName()));
            LocalForecastHistory.write(anchors,directory);
            var restored=new AlightingAwareHistorySeedReader(directory.toString(),250000).read(version);
            assertThat(restored.batchId()).isEqualTo(anchors.getFirst().batch());
            assertThat(restored.observations()).isEmpty();
            assertThat(LocalForecastHistory.prepare(connection,List.of(route.displayName()))).isEqualTo(anchors);
            try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM observation_batch")) {
                rows.next();assertThat(rows.getLong(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void 이미_이력이_있으면_임의의_시작점으로_잘라_정상처럼_만들지_않는다() throws Exception {
        try (var database=TestDatabase.create();var connection=database.connection()) {
            Flyway.configure().dataSource(database.jdbcUrl(),database.username(),database.password()).load().migrate();
            var route=RouteDataset.read(Path.of(System.getProperty("dev.data.directory"),"routes.json")).routes().getFirst();
            long version=LocalData.seedRoute(connection,route);
            try(var sql=connection.createStatement()) {
                sql.execute("INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,outcome,stored_rows,normalization_version,collection_strategy_version) VALUES("+version+",now(),1,'actual',now(),'SUCCESS_EMPTY',0,'test','test')");
            }
            assertThatThrownBy(() -> LocalForecastHistory.prepare(connection,List.of(route.displayName())))
                .hasMessage("FORECAST_HISTORY_BOOTSTRAP_REQUIRED");
        }
    }
}
