package com.gustler.backend.stations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.quota.configuration.StationQuotaConfiguration;
import com.gustler.backend.station.contract.StationTileId;
import com.gustler.backend.station.contract.StationTileRefresh;
import com.gustler.backend.stations.api.FillStationTiles;
import com.gustler.backend.stations.api.ImportStationSeed;
import com.gustler.backend.stations.api.StationFillPolicy;
import com.gustler.backend.stations.application.StationTileFiller;
import com.gustler.backend.stations.domain.BusStation;
import com.gustler.backend.stations.domain.StationAroundSource;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationRefreshQueue;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationSeedImporter;
import com.gustler.backend.stations.infrastructure.jdbc.JdbcStationTileStore;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    classes = StationStorageIntegrationTest.Config.class,
    properties = {"spring.datasource.hikari.minimum-idle=1", "stations.fill.enabled=true"}
)
@Import({PostgresTestContainer.class, StationQuotaConfiguration.class})
class StationStorageIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-06T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final StationTileId TILE = StationTileId.of(127.11, 37.39);
    private static final String HEADER = "정류장번호,정류장명,위도,경도,정보수집일,모바일단축번호,도시코드,도시명,관리도시명\n";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JdbcTemplate batch;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private StationCallQuota quota;

    @Autowired
    private DataSource dataSource;

    @TempDir
    private Path directory;

    @BeforeEach
    void clearStationsAndStationQuota() {
        jdbc.sql("TRUNCATE bus_station,bus_station_tile,station_seed_dataset").update();
        jdbc.sql("DELETE FROM daily_call_quota WHERE api_service='BUS_STATION'").update();
    }

    @Test
    void 기초명부는_GGB_승차정류장만_설치하고_기준일과_실조회시각을_구분한다() throws Exception {
        // given
        Path file = writeCsv(
            stationRow("GGB206000532", "\"판교,역\"", "2026-10-20")
                + stationRow("GGB277000001", "미정차", "2026-10-20")
                + stationRow("BSB206000532", "다른원천", "2026-10-20")
        );

        // when
        ImportStationSeed.Result result = importer().importFile(file, StandardCharsets.UTF_8).orElseThrow();

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.stationCount()).isEqualTo(1);
            softly.assertThat(jdbc.sql("SELECT name FROM bus_station").query(String.class).single())
                .isEqualTo("판교,역");
            softly.assertThat(jdbc.sql("SELECT mobile_no FROM bus_station").query(String.class).single())
                .isEqualTo("07489");
            softly.assertThat(jdbc.sql(
                "SELECT verified_at IS NULL AND baseline_date = DATE '2026-10-20' FROM bus_station"
            ).query(Boolean.class).single()).isTrue();
        });
    }

    @Test
    void 같은_명부를_다시_설치해도_기존_자료를_교체하지_않는다() throws Exception {
        // given
        Path file = writeCsv(stationRow("GGB206000532", "기존", "2026-10-20"));
        importer().importFile(file, StandardCharsets.UTF_8);

        // when
        ImportStationSeed.Result result = importer().importFile(file, StandardCharsets.UTF_8).orElseThrow();

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.changed()).isFalse();
            softly.assertThat(jdbc.sql("SELECT name FROM bus_station").query(String.class).list())
                .containsExactly("기존");
        });
    }

    @Test
    void 잘못된_명부는_기존_목록과_기준일을_바꾸지_않는다() throws Exception {
        // given
        importer().importFile(writeCsv(stationRow("GGB206000532", "기존", "2026-10-20")), StandardCharsets.UTF_8);
        Path broken = writeCsv(
            stationRow("GGB206000533", "정상행", "2026-10-21") + stationRow("GGBbad", "깨진행", "2026-10-21")
        );

        // when
        Throwable failure = catchThrowable(() -> importer().importFile(broken, StandardCharsets.UTF_8));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isInstanceOf(IllegalArgumentException.class);
            softly.assertThat(jdbc.sql("SELECT name FROM bus_station").query(String.class).list())
                .containsExactly("기존");
            softly.assertThat(jdbc.sql("SELECT data_date::text FROM station_seed_dataset").query(String.class).single())
                .isEqualTo("2026-10-20");
        });
    }

    @Test
    void 오래된_CSV는_최신_실조회_자료를_덮지_않는다() throws Exception {
        // given
        importer().importFile(writeCsv(stationRow("GGB206000532", "기존", "2026-10-20")), StandardCharsets.UTF_8);
        jdbc.sql("UPDATE bus_station SET name='실조회',verified_at=? WHERE station_id='206000532'")
            .param(Timestamp.from(NOW))
            .update();
        Path file = writeCsv(stationRow("GGB206000532", "파일값", "2026-10-21"));

        // when
        importer().importFile(file, StandardCharsets.UTF_8);

        // then
        assertThat(jdbc.sql("SELECT name FROM bus_station WHERE station_id='206000532'").query(String.class).single())
            .isEqualTo("실조회");
    }

    @Test
    void 새_명부에서_빠진_기초_행은_제외한다() throws Exception {
        // given
        importer().importFile(writeCsv(
            stationRow("GGB206000532", "남길정류장", "2026-10-20") + stationRow("GGB206000533", "삭제후보", "2026-10-20")
        ), StandardCharsets.UTF_8);
        jdbc.sql("UPDATE bus_station SET verified_at=? WHERE station_id='206000532'")
            .param(Timestamp.from(NOW))
            .update();
        Path file = writeCsv(stationRow("GGB206000532", "남길정류장", "2026-10-21"));

        // when
        importer().importFile(file, StandardCharsets.UTF_8);

        // then
        assertThat(jdbc.sql("SELECT active FROM bus_station WHERE station_id='206000533'")
            .query(Boolean.class).single()).isFalse();
    }

    @Test
    void 같은_한국날짜의_실조회값도_명부가_덮지_않는다() throws Exception {
        // given
        importer().importFile(writeCsv(stationRow("GGB206000532", "초기", "2026-10-19")), StandardCharsets.UTF_8);
        jdbc.sql("UPDATE bus_station SET name='당일실조회',verified_at=?")
            .param(Timestamp.from(Instant.parse("2026-10-19T15:30:00Z")))
            .update();
        Path file = writeCsv(stationRow("GGB206000532", "명부값", "2026-10-20"));

        // when
        importer().importFile(file, StandardCharsets.UTF_8);

        // then
        assertThat(jdbc.sql("SELECT name FROM bus_station").query(String.class).single()).isEqualTo("당일실조회");
    }

    @Test
    void 다른_인스턴스가_설치_중이면_기다리지_않고_건너뛴다() throws Exception {
        // given
        Path file = writeCsv(stationRow("GGB206000532", "건너뜀", "2026-10-20"));
        Optional<ImportStationSeed.Result> result;

        // when
        try (Connection holder = dataSource.getConnection(); Statement statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(20261106, 50)");
            try {
                result = importer().importFile(file, StandardCharsets.UTF_8);
            } finally {
                statement.execute("SELECT pg_advisory_unlock(20261106, 50)");
            }
        }

        // then
        assertSoftly(softly -> {
            softly.assertThat(result).isEmpty();
            softly.assertThat(jdbc.sql("SELECT count(*) FROM bus_station").query(Integer.class).single()).isZero();
        });
    }

    @Test
    void 수집_범위_밖의_칸은_대기열에_넣지_않는다() {
        // given
        JdbcStationRefreshQueue queue = refreshQueue(4);
        StationTileId outside = StationTileId.of(127.13, 35.82);

        // when
        StationTileRefresh result = queue.request(outside);

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo(StationTileRefresh.Status.FRESH);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM bus_station_tile").query(Integer.class).single())
                .isZero();
        });
    }

    @Test
    void 연속_실패로_포기한_칸은_대기열_상한을_차지하지_않고_기존_명부를_남긴다() throws Exception {
        // given
        importer().importFile(writeCsv(stationRow("GGB206000532", "기존", "2026-10-20")), StandardCharsets.UTF_8);
        JdbcStationRefreshQueue queue = refreshQueue(1);
        queue.request(TILE);
        StationTileFiller filler = filler(
            (longitude, latitude, keyAlias) -> new StationAroundSource.Failed("ERROR", null), retryPolicy(2)
        );
        filler.fillNext();
        jdbc.sql("UPDATE bus_station_tile SET retry_after_at = NULL").update();
        StationTileId other = new StationTileId(TILE.x() + 1, TILE.y());

        // when
        FillStationTiles.Step step = filler.fillNext();
        StationTileRefresh otherRequest = queue.request(other);

        // then
        assertSoftly(softly -> {
            softly.assertThat(step).isEqualTo(FillStationTiles.Step.FAILED);
            softly.assertThat(otherRequest.status()).isEqualTo(StationTileRefresh.Status.QUEUED);
            softly.assertThat(jdbc.sql("""
                SELECT refresh_requested_at IS NULL AND pass_started_at IS NULL AND failure_count = 0
                FROM bus_station_tile WHERE tile_x = ? AND tile_y = ?
                """).params(TILE.x(), TILE.y()).query(Boolean.class).single()).isTrue();
            softly.assertThat(activeStationCount()).isEqualTo(1);
        });
    }

    @Test
    void 포기한_칸을_다시_요청하면_첫_탐색점부터_새_회차로_채운다() {
        // given
        JdbcStationRefreshQueue queue = refreshQueue(4);
        queue.request(TILE);
        filler(emptySource(new AtomicInteger(), null), policy()).fillNext();
        filler((longitude, latitude, keyAlias) -> new StationAroundSource.Failed("INVALID_STATION_ROW", null), policy())
            .fillNext();
        jdbc.sql("UPDATE bus_station_tile SET retry_after_at = NULL").update();

        // when
        StationTileRefresh again = queue.request(TILE);
        FillStationTiles.Step step = filler(emptySource(new AtomicInteger(), null), policy()).fillNext();

        // then
        assertSoftly(softly -> {
            softly.assertThat(again.status()).isEqualTo(StationTileRefresh.Status.QUEUED);
            softly.assertThat(step).isEqualTo(FillStationTiles.Step.PROBED);
            softly.assertThat(jdbc.sql("SELECT probes_done FROM bus_station_tile").query(Integer.class).single())
                .isEqualTo(1);
        });
    }

    @Test
    void 같은_칸의_동시요청은_한_번만_접수한다() throws Exception {
        // given
        JdbcStationRefreshQueue queue = refreshQueue(1);
        List<Callable<StationTileRefresh.Status>> tasks = IntStream.range(0, 16)
            .<Callable<StationTileRefresh.Status>>mapToObj(index -> () -> queue.request(TILE).status())
            .toList();
        List<StationTileRefresh.Status> results;

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            results = completedResults(pool.invokeAll(tasks));
        }
        StationTileRefresh joined = queue.request(TILE);

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).filteredOn(status -> status == StationTileRefresh.Status.QUEUED).hasSize(1);
            softly.assertThat(results).allMatch(StationStorageIntegrationTest::isAdmissionResult);
            softly.assertThat(joined.status()).isEqualTo(StationTileRefresh.Status.JOINED);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM bus_station_tile").query(Integer.class).single())
                .isEqualTo(1);
        });
    }

    @Test
    void 다른_칸의_요청은_대기열_상한을_지킨다() {
        // given
        JdbcStationRefreshQueue queue = refreshQueue(1);
        queue.request(TILE);
        StationTileId other = new StationTileId(TILE.x() + 1, TILE.y());

        // when
        StationTileRefresh result = queue.request(other);

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.status()).isEqualTo(StationTileRefresh.Status.BUSY);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM bus_station_tile").query(Integer.class).single())
                .isEqualTo(1);
        });
    }

    @Test
    void 열람_요청이_없는_칸은_수집하지_않는다() {
        // given
        AtomicInteger calls = new AtomicInteger();
        StationTileFiller filler = filler(emptySource(calls, null), policy());

        // when
        FillStationTiles.Step step = filler.fillNext();

        // then
        assertSoftly(softly -> {
            softly.assertThat(step).isEqualTo(FillStationTiles.Step.NOTHING_TO_DO);
            softly.assertThat(calls.get()).isZero();
        });
    }

    @Test
    void 칸_수집을_완료할_때까지_기존_목록을_유지한다() throws Exception {
        // given
        importer().importFile(writeCsv(
            stationRow("GGB206000532", "남길정류장", "2026-10-20")
                + stationRow("GGB206000533", "사라진정류장", "2026-10-20")
        ), StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        StationAroundSource source = (longitude, latitude, keyAlias) -> {
            calls.incrementAndGet();
            return new StationAroundSource.Found(
                List.of(new BusStation("206000532", "변경이름", 127.11, 37.39, "07489", "성남", false)), null
            );
        };
        StationTileFiller filler = filler(source, policy());
        JdbcStationRefreshQueue queue = refreshQueue(4);
        queue.request(TILE);
        for (int index = 0; index < 11; index++) {
            filler.fillNext();
        }
        assertThat(activeStationCount()).isEqualTo(2);

        // when
        FillStationTiles.Step completed = filler.fillNext();
        final int activeAfterCompletion = activeStationCount();
        StationTileRefresh refresh = queue.request(TILE);
        FillStationTiles.Step next = filler.fillNext();

        // then
        assertSoftly(softly -> {
            softly.assertThat(completed).isEqualTo(FillStationTiles.Step.TILE_COMPLETED);
            softly.assertThat(activeAfterCompletion).isEqualTo(1);
            softly.assertThat(refresh.status()).isEqualTo(StationTileRefresh.Status.FRESH);
            softly.assertThat(next).isEqualTo(FillStationTiles.Step.NOTHING_TO_DO);
            softly.assertThat(calls.get()).isEqualTo(12);
            softly.assertThat(jdbc.sql("SELECT count(*) FROM bus_station_tile").query(Integer.class).single())
                .isEqualTo(1);
        });
    }

    @Test
    void 중간_원천실패는_기존_명부를_삭제하지_않는다() throws Exception {
        // given
        importer().importFile(writeCsv(stationRow("GGB206000532", "기존", "2026-10-20")), StandardCharsets.UTF_8);
        refreshQueue(4).request(TILE);
        StationTileFiller filler = filler(
            (longitude, latitude, keyAlias) -> new StationAroundSource.Failed("ERROR", null), policy()
        );

        // when
        FillStationTiles.Step step = filler.fillNext();

        // then
        assertSoftly(softly -> {
            softly.assertThat(step).isEqualTo(FillStationTiles.Step.FAILED);
            softly.assertThat(jdbc.sql("SELECT active FROM bus_station").query(Boolean.class).single()).isTrue();
            softly.assertThat(jdbc.sql("SELECT completed_at IS NULL FROM bus_station_tile")
                .query(Boolean.class).single()).isTrue();
        });
    }

    @Test
    void 수집기를_다시_만들어도_확정한_하루_호출량은_남는다() {
        // given
        refreshQueue(4).request(TILE);
        AtomicInteger calls = new AtomicInteger();
        StationAroundSource source = emptySource(calls, null);
        StationFillPolicy limited = limitedPolicy(2);
        StationTileFiller first = filler(source, limited);
        StationTileFiller restarted = filler(source, limited);

        // when
        List<FillStationTiles.Step> steps = List.of(first.fillNext(), restarted.fillNext(), restarted.fillNext());

        // then
        assertSoftly(softly -> {
            softly.assertThat(steps).containsExactly(
                FillStationTiles.Step.PROBED, FillStationTiles.Step.PROBED, FillStationTiles.Step.PAUSED
            );
            softly.assertThat(calls.get()).isEqualTo(2);
            softly.assertThat(jdbc.sql("SELECT reserved_calls FROM daily_call_quota WHERE api_service='BUS_STATION'")
                .query(Integer.class).single()).isEqualTo(2);
        });
    }

    @Test
    void 모호한_응답의_재호출도_한도를_예약해야_한다() {
        // given
        refreshQueue(4).request(TILE);
        AtomicInteger calls = new AtomicInteger();
        StationAroundSource source = (longitude, latitude, keyAlias) -> {
            calls.incrementAndGet();
            return new StationAroundSource.Ambiguous(null);
        };
        StationTileFiller filler = filler(source, limitedPolicy(1));

        // when
        FillStationTiles.Step step = filler.fillNext();

        // then
        assertSoftly(softly -> {
            softly.assertThat(step).isEqualTo(FillStationTiles.Step.PAUSED);
            softly.assertThat(calls.get()).isEqualTo(1);
            softly.assertThat(jdbc.sql("SELECT probes_done FROM bus_station_tile").query(Integer.class).single())
                .isZero();
        });
    }

    @Test
    void 원천_잔여량_하한으로_멈춘_상태도_재시작을_견딘다() {
        // given
        refreshQueue(4).request(TILE);
        AtomicInteger calls = new AtomicInteger();
        StationAroundSource source = emptySource(calls, 1999);
        StationTileFiller first = filler(source, policy());
        StationTileFiller restarted = filler(source, policy());

        // when
        List<FillStationTiles.Step> steps = List.of(first.fillNext(), restarted.fillNext());

        // then
        assertSoftly(softly -> {
            softly.assertThat(steps).containsExactly(FillStationTiles.Step.PROBED, FillStationTiles.Step.PAUSED);
            softly.assertThat(calls.get()).isEqualTo(1);
        });
    }

    @Test
    void 예산은_서로_다른_예약자도_공유한다() throws Exception {
        // given
        Instant requestedAt = Instant.parse("2026-11-06T14:59:59Z");
        List<Callable<Boolean>> requests = IntStream.range(0, 16)
            .<Callable<Boolean>>mapToObj(index -> () -> quota.reserve("a", requestedAt, 2))
            .toList();
        List<Boolean> results;

        // when
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            results = completedResults(pool.invokeAll(requests));
        }
        final boolean additionalReservation = quota.reserve("a", requestedAt, 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(results).filteredOn(Boolean::booleanValue).hasSize(2);
            softly.assertThat(additionalReservation).isFalse();
        });
    }

    @Test
    void 예산은_키별로_구분한다() {
        // given
        Instant requestedAt = Instant.parse("2026-11-06T14:59:59Z");
        quota.reserve("a", requestedAt, 1);

        // when
        final boolean reserved = quota.reserve("b", requestedAt, 2);

        // then
        assertThat(reserved).isTrue();
    }

    @Test
    void 예산은_한국_자정이_지나면_새_날짜에_예약한다() {
        // given
        Instant beforeMidnight = Instant.parse("2026-11-06T14:59:59Z");
        quota.reserve("a", beforeMidnight, 1);

        // when
        final boolean reserved = quota.reserve("a", beforeMidnight.plusSeconds(1), 2);

        // then
        assertSoftly(softly -> {
            softly.assertThat(reserved).isTrue();
            softly.assertThat(jdbc.sql("""
                SELECT reserved_calls FROM daily_call_quota
                WHERE api_service='BUS_STATION' AND key_alias='a' AND kst_date=DATE '2026-11-07'
                """).query(Integer.class).single()).isEqualTo(1);
        });
    }

    private JdbcStationSeedImporter importer() {
        return new JdbcStationSeedImporter(jdbc, batch, manager, CLOCK);
    }

    private JdbcStationRefreshQueue refreshQueue(final int capacity) {
        return new JdbcStationRefreshQueue(jdbc, manager, CLOCK, Duration.ofDays(30), capacity);
    }

    private StationTileFiller filler(StationAroundSource source, StationFillPolicy policy) {
        return new StationTileFiller(new JdbcStationTileStore(jdbc, manager), source, policy, CLOCK, quota);
    }

    private int activeStationCount() {
        return jdbc.sql("SELECT count(*) FROM bus_station WHERE active").query(Integer.class).single();
    }

    private Path writeCsv(String rows) throws Exception {
        Path path = directory.resolve(UUID.randomUUID() + ".csv");
        Files.writeString(path, HEADER + rows);
        return path;
    }

    private static String stationRow(String identifier, String name, String date) {
        return identifier + "," + name + ",37.39,127.11," + date + ",7489,31020,경기도 성남시,경기BIS\n";
    }

    private static boolean isAdmissionResult(StationTileRefresh.Status status) {
        if (status == StationTileRefresh.Status.QUEUED || status == StationTileRefresh.Status.JOINED) {
            return true;
        }
        return status == StationTileRefresh.Status.BUSY;
    }

    private static StationAroundSource emptySource(AtomicInteger calls, Integer remaining) {
        return (longitude, latitude, keyAlias) -> {
            calls.incrementAndGet();
            return new StationAroundSource.NoStations(remaining);
        };
    }

    private static <T> List<T> completedResults(List<Future<T>> futures) throws Exception {
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private static StationFillPolicy limitedPolicy(final int dailyLimit) {
        return new StationFillPolicy(
            "a", dailyLimit, 0, Duration.ofSeconds(65), Duration.ofMinutes(10), Duration.ofDays(30),
            Duration.ofSeconds(40), Duration.ofSeconds(300), 5, Duration.ofHours(1)
        );
    }

    private static StationFillPolicy retryPolicy(final int maxFailures) {
        return new StationFillPolicy(
            "a", 8000, 2000, Duration.ofSeconds(65), Duration.ofMinutes(10), Duration.ofDays(30),
            Duration.ofSeconds(40), Duration.ofSeconds(300), maxFailures, Duration.ofHours(1)
        );
    }

    private static StationFillPolicy policy() {
        return new StationFillPolicy(
            "a", 8000, 2000, Duration.ofSeconds(65), Duration.ofMinutes(10), Duration.ofDays(30),
            Duration.ofSeconds(40), Duration.ofSeconds(300), 5, Duration.ofHours(1)
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Config {
    }
}
