package com.gustler.backend.stations.infrastructure.jdbc;

import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.stations.api.ImportStationSeed;
import com.gustler.backend.stations.infrastructure.csv.StationSeedCsv;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 검증한 파일을 한 트랜잭션으로 설치한다. 더 최근의 실조회 정보는 파일이 덮어쓰지 않는다.
 *
 * <p>설치 잠금은 기다리지 않는다. 여러 worker가 동시에 기동하면 잠금을 얻은 하나만 설치하고 나머지는 건너뛴다.
 */
public final class JdbcStationSeedImporter implements ImportStationSeed {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcStationSeedImporter(
        JdbcClient jdbc,
        JdbcTemplate batch,
        PlatformTransactionManager manager,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.transactions = new TransactionTemplate(manager);
        this.clock = clock;
    }

    @Override
    public Optional<ImportStationSeed.Result> importFile(Path path, Charset charset) {
        StationSeedCsv.Seed seed = StationSeedCsv.read(path, charset);
        validateSourceDate(seed);
        return transactions.execute(status -> installIfUnlocked(seed));
    }

    private void validateSourceDate(StationSeedCsv.Seed seed) {
        if (seed.date().isAfter(clock.instant().atZone(KOREA).toLocalDate())) {
            throw new IllegalArgumentException("미래 기준일의 정류장 명부는 설치할 수 없다");
        }
    }

    private Optional<ImportStationSeed.Result> installIfUnlocked(StationSeedCsv.Seed seed) {
        limitTransaction();
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(20261106, 50)").query(Boolean.class).single()) {
            return Optional.empty();
        }
        return Optional.of(install(seed));
    }

    private ImportStationSeed.Result install(StationSeedCsv.Seed seed) {
        Optional<Installed> current = findInstalled();
        if (current.filter(installed -> installed.canReuse(seed)).isPresent()) {
            return unchangedResult(current.orElseThrow());
        }
        createStagingTable();
        Timestamp importedAt = Timestamp.from(clock.instant());
        stageRows(seed, importedAt);
        Timestamp sourceAt = Timestamp.from(seed.date().atStartOfDay(KOREA).toInstant());
        mergeRows(sourceAt);
        retireMissingRows(seed.date(), sourceAt);
        saveManifest(seed, importedAt);
        return new ImportStationSeed.Result(seed.date(), seed.stations().size(), true);
    }

    private void limitTransaction() {
        jdbc.sql("SET LOCAL lock_timeout = '2s'").update();
        jdbc.sql("SET LOCAL statement_timeout = '30s'").update();
    }

    private Optional<Installed> findInstalled() {
        return jdbc.sql("SELECT data_date, file_sha256 FROM station_seed_dataset WHERE source_id = 'GBIS'")
            .query((row, index) -> new Installed(row.getDate(1).toLocalDate(), row.getString(2)))
            .optional();
    }

    private ImportStationSeed.Result unchangedResult(Installed installed) {
        final int count = jdbc.sql("SELECT station_count FROM station_seed_dataset WHERE source_id = 'GBIS'")
            .query(Integer.class)
            .single();
        return new ImportStationSeed.Result(installed.date(), count, false);
    }

    private void createStagingTable() {
        jdbc.sql("CREATE TEMP TABLE station_seed_stage (LIKE bus_station INCLUDING DEFAULTS) ON COMMIT DROP").update();
    }

    private void stageRows(StationSeedCsv.Seed seed, Timestamp importedAt) {
        batch.batchUpdate("""
            INSERT INTO station_seed_stage(station_id,name,x,y,mobile_no,region_name,tile_x,tile_y,
                first_seen_at,last_seen_at,baseline_date,active)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,true)
            """, seed.stations(), 500, (statement, station) -> {
            StationTileId tile = station.tile();
            statement.setString(1, station.stationId());
            statement.setString(2, station.name());
            statement.setDouble(3, station.x());
            statement.setDouble(4, station.y());
            statement.setString(5, station.mobileNo());
            statement.setString(6, station.regionName());
            statement.setInt(7, tile.x());
            statement.setInt(8, tile.y());
            statement.setTimestamp(9, importedAt);
            statement.setTimestamp(10, importedAt);
            statement.setDate(11, Date.valueOf(seed.date()));
        });
    }

    private void mergeRows(Timestamp sourceAt) {
        jdbc.sql("""
            INSERT INTO bus_station AS s SELECT * FROM station_seed_stage
            ON CONFLICT(station_id) DO UPDATE SET name = EXCLUDED.name,x = EXCLUDED.x,y = EXCLUDED.y,
                mobile_no = EXCLUDED.mobile_no,region_name = EXCLUDED.region_name,
                tile_x = EXCLUDED.tile_x,tile_y = EXCLUDED.tile_y,baseline_date = EXCLUDED.baseline_date,
                last_seen_at = EXCLUDED.last_seen_at, active = true
            WHERE (s.baseline_date IS NULL OR s.baseline_date <= EXCLUDED.baseline_date)
              AND (s.verified_at IS NULL OR s.verified_at < ?)
            """).param(sourceAt).update();
    }

    private void retireMissingRows(LocalDate sourceDate, Timestamp sourceAt) {
        jdbc.sql("""
            UPDATE bus_station s SET active = false, baseline_date = ?
            WHERE s.baseline_date IS NOT NULL AND s.baseline_date <= ?
              AND (s.verified_at IS NULL OR s.verified_at < ?)
              AND NOT EXISTS(SELECT 1 FROM station_seed_stage stage WHERE stage.station_id = s.station_id)
            """).params(Date.valueOf(sourceDate), Date.valueOf(sourceDate), sourceAt).update();
    }

    private void saveManifest(StationSeedCsv.Seed seed, Timestamp importedAt) {
        jdbc.sql(
            """
            INSERT INTO station_seed_dataset(source_id,data_date,file_sha256,station_count,imported_at)
            VALUES ('GBIS',?,?,?,?) ON CONFLICT(source_id) DO UPDATE SET data_date = EXCLUDED.data_date,
                file_sha256 = EXCLUDED.file_sha256,station_count = EXCLUDED.station_count,
                imported_at = EXCLUDED.imported_at
            """
        )
            .params(
                Date.valueOf(seed.date()),
                seed.sha256(),
                seed.stations().size(),
                importedAt
            )
            .update();
    }

    private record Installed(LocalDate date, String hash) {

        boolean canReuse(StationSeedCsv.Seed seed) {
            return date.isAfter(seed.date()) || hash.equals(seed.sha256());
        }
    }
}
