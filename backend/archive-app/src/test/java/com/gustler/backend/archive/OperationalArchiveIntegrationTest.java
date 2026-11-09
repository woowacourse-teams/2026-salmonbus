package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** 공유 PG 서버의 격리 DB에서 실제 FK·transaction·재개를 검증한다. Spring/Worker는 기동하지 않는다. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OperationalArchiveIntegrationTest {
    TestDatabase database;
    JdbcClient jdbc;
    ArchiveRepository repository;
    long version;
    final Clock clock = Clock.fixed(Instant.parse("2026-11-09T03:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    void migrate() {
        database = TestDatabase.create();
        DriverManagerDataSource source = new DriverManagerDataSource(database.jdbcUrl(), database.username(),
            database.password());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = JdbcClient.create(source);
        jdbc.sql("CREATE INDEX ix_archive_evaluation_arrival_fk ON forecast_evaluation_result(arrival_observation_id)")
            .update();
        jdbc.sql("CREATE INDEX ix_archive_compact_arrival_fk ON forecast_evaluation_compact(arrival_observation_id)")
            .update();
        jdbc.sql("CREATE INDEX ix_archive_sample_arrival_fk ON stop_demand_pending_sample(arrival_observation_id)")
            .update();
        jdbc.sql("CREATE INDEX ix_archive_forecast_publication_fk ON seat_forecast(publication_id)").update();
        repository = new ArchiveRepository(jdbc, new JdbcTransactionManager(source));
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void setup() {
        jdbc.sql("TRUNCATE route,model_deployment CASCADE").update();
        final long route = jdbc.sql("INSERT INTO"
            + " route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)"
            + " VALUES('test','GBIS','test','3330','A','B') RETURNING id").query(Long.class).single();
        version = jdbc.sql(
            "INSERT INTO route_version(route_id,content_digest,valid_from)" + " VALUES(?,?,'2026-01-01') RETURNING id")
            .params(route, "0".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO" + " route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)"
            + " VALUES(?,1,'s1','S1','UP',true),(?,2,'s2','S2','UP',true)").params(version, version).update();
        jdbc.sql(
            "INSERT INTO" + " stop_demand_run(route_version_id,run_id,quality_revision,phase,data_until,completed_at)"
                + " VALUES(?,gen_random_uuid(),1,'DONE','2026-11-08T15:00Z','2026-11-09T00:00Z')")
            .param(version).update();
    }

    long batch(String date) {
        return jdbc
            .sql(
                """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,requested_at,
                        response_received_at,completed_at,outcome,normalization_version,collection_strategy_version)
                    VALUES(?,CAST(? AS timestamptz),1,?,CAST(? AS timestamptz),CAST(? AS timestamptz),CAST(? AS timestamptz),'SUCCESS_ROWS','n','c') RETURNING id
                    """)
            .params(version, date, UUID.randomUUID().toString(), date, date, date).query(Long.class).single();
    }

    long observation(final long batch) {
        return jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            VALUES(?,?,1,'vehicle',1,'s1',2,1,12) RETURNING id
            """).params(batch, version).query(Long.class).single();
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    ArchiveService service(MemoryObjects objects) {
        return new ArchiveService(repository, objects, clock,
            new SimpleMeterRegistry(), "salmonbus/test/lake-v2/", 2, true);
    }

    @Test
    void 완료한_과거만_이관하고_오늘과_어제는_남긴다() {
        observation(batch("2026-11-07T03:00Z"));
        observation(batch("2026-11-08T03:00Z"));
        observation(batch("2026-11-09T03:00Z"));
        MemoryObjects objects = new MemoryObjects();
        service(objects).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("vehicle_observation")).isEqualTo(2);
            softly.assertThat(count("observation_batch")).isEqualTo(2);
            softly.assertThat(jdbc.sql("SELECT state FROM operational_archive_job").query(String.class).single())
                .isEqualTo("DONE");
            softly.assertThat(objects.values.keySet()).anyMatch(k -> k.contains("/manifest-"));
            softly.assertThat(count("stop_demand_run")).isEqualTo(1);
        });
    }

    @Test
    void 미완료_정산은_해당_노선의_삭제를_막는다() {
        final long id = observation(batch("2026-11-07T03:00Z"));
        jdbc.sql("INSERT INTO" + " forecast_evaluation_work(vehicle_observation_id,route_version_id)" + " VALUES(?,?)")
            .params(id, version).update();
        service(new MemoryObjects()).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("vehicle_observation")).isEqualTo(1);
            softly.assertThat(jdbc.sql("SELECT failure_code FROM operational_archive_job").query(String.class).single())
                .isEqualTo("SETTLEMENT_PENDING");
        });
    }

    @Test
    void 미반영_통계가_있으면_삭제하지_않는다() {
        final long id = observation(batch("2026-11-07T03:00Z"));
        jdbc.sql("""
            INSERT INTO stop_demand_pending_sample(route_version_id,prediction_observation_id,arrival_observation_id,
                vehicle_id,target_stop_order,arrived_at,scored_at,prediction_remaining_seats,arrival_remaining_seats)
            VALUES(?,?,?,'vehicle',2,'2026-11-07T03:10Z','2026-11-07T03:11Z',12,10)
            """).params(version, id, id).update();
        service(new MemoryObjects()).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("vehicle_observation")).isEqualTo(1);
            softly.assertThat(jdbc.sql("SELECT failure_code FROM operational_archive_job").query(String.class).single())
                .isEqualTo("STATISTICS_PENDING");
        });
    }

    @Test
    void 업로드_응답_유실_뒤_같은_파일로_재개한다() {
        observation(batch("2026-11-07T03:00Z"));
        MemoryObjects objects = new MemoryObjects();
        objects.failOnce = true;
        service(objects).advance();
        assertThat(count("vehicle_observation")).isEqualTo(1);
        jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity',lease_until='-infinity'").update();
        service(objects).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("vehicle_observation")).isZero();
            softly.assertThat(jdbc.sql("SELECT state FROM operational_archive_job").query(String.class).single())
                .isEqualTo("DONE");
        });
    }

    @Test
    void 오래된_임대_소유자는_삭제를_시작할_수_없다() {
        observation(batch("2026-11-07T03:00Z"));
        ArchiveRepository.Job job = repository.claim(ArchiveRetention.cutoff(clock)).orElseThrow();
        jdbc.sql("UPDATE operational_archive_job SET lease_token=gen_random_uuid()").update();
        assertThatThrownBy(() -> repository.closeRange(job)).isInstanceOf(IllegalStateException.class);
        assertThat(count("vehicle_observation")).isEqualTo(1);
    }

    @Test
    void 기존_이관_테이블에는_새_원본_FK가_없다() {
        assertThat(jdbc
            .sql("SELECT count(*) FROM pg_constraint WHERE" + " conrelid='archive_legacy_member'::regclass AND"
                + " contype='f' AND confrelid" + " IN('vehicle_observation'::regclass,'seat_forecast'::regclass)")
            .query(Long.class).single()).isZero();
    }

    @Test
    void 옛_S3는_읽지_않고_DB_원본과_수동_인계용_멤버_근거만_보관한다() {
        // given
        final long source = observation(batch("2026-11-07T03:00Z"));
        forecast(source, false, 0);
        jdbc.sql("DELETE FROM forecast_evaluation_result WHERE vehicle_observation_id=?").param(source).update();
        UUID legacyBatch = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO archive_legacy_batch(id,route_version_id,quality_revision,lease_token,lease_until,
                state,row_count,manifest_sha256,verified_at,storage_state,purged_at)
            VALUES(?,?,1,gen_random_uuid(),'-infinity','VERIFIED',1,repeat('a',64),now(),'PURGED',now())
            """).params(legacyBatch, version).update();
        jdbc.sql("""
            INSERT INTO archive_legacy_member(vehicle_observation_id,target_stop_order,route_version_id,
                batch_id,original_sha256) VALUES(?,2,?,?,repeat('b',64))
            """).params(source, version, legacyBatch).update();
        MemoryObjects objects = new MemoryObjects();

        // when
        service(objects).advance();

        // then
        assertSoftly(softly -> {
            softly.assertThat(jdbc.sql("SELECT state FROM operational_archive_job").query(String.class).single())
                .isEqualTo("DONE");
            softly.assertThat(count("vehicle_observation")).isZero();
            softly.assertThat(count("archive_legacy_member")).isZero();
            softly.assertThat(count("archive_legacy_batch")).isOne();
            softly.assertThat(objects.values.keySet()).allMatch(key -> key.startsWith("salmonbus/test/lake-v2/"))
                .noneMatch(key -> key.contains("legacy_evidence"));
        });
        String memberKey = objects.values.keySet().stream()
            .filter(key -> key.contains("-archive_legacy_member-")).findFirst().orElseThrow();
        assertThat(ArchiveFiles.decode(objects.values.get(memberKey)))
            .singleElement().asString().contains(legacyBatch.toString(), "b".repeat(64));
    }

    void forecast(final long source, final boolean compact, final long arrival) {
        final long model = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state)
            VALUES(gen_random_uuid(),'test','seat','1',?,'target','calc',?,'2026-01-01','STAGED') RETURNING id
            """).params("1".repeat(64), "2".repeat(64)).query(Long.class).single();
        final long publication = jdbc
            .sql(
                """
                    INSERT INTO forecast_publication(source_batch_id,route_version_id,model_deployment_id,
                        demand_statistics_revision,quality_revision,observed_at,generated_at,published_at,prediction_count)
                    SELECT o.observation_batch_id,?, ?,1,1,b.response_received_at,b.response_received_at,b.response_received_at,1
                    FROM vehicle_observation o JOIN observation_batch b ON b.id=o.observation_batch_id WHERE o.id=? RETURNING id
                    """)
            .params(version, model, source).query(Long.class).single();
        jdbc.sql("""
            INSERT INTO seat_forecast(publication_id,vehicle_observation_id,target_stop_order,route_version_id,
                stops_to_target,model_deployment_id,demand_statistics_revision,seat_full_chance_raw,seat_full_chance,
                generated_at,quality_revision) VALUES(?,?,2,?,1,?,1,0.2,0.3,'2026-11-07T03:00Z',1)
            """).params(publication, source, version, model).update();
        if (compact) {
            jdbc.sql("""
                INSERT INTO forecast_evaluation_compact(vehicle_observation_id,target_stop_order,arrival_observation_id,
                    seats_on_arrival,scored_at,arrived_at,arrival_quality_direction)
                VALUES(?,2,?,9,'2026-11-07T03:11Z','2026-11-07T03:10Z',0)
                """).params(source, arrival).update();
            return;
        }
        {
            jdbc.sql("""
                INSERT INTO forecast_evaluation_result(vehicle_observation_id,target_stop_order,route_version_id,
                    scoring_state,scored_at) VALUES(?,2,?,'SKIPPED','2026-11-07T03:11Z')
                """).params(source, version).update();
        }
    }

    @Test
    void 상세와_축약_정산_예보_관측을_FK_순서대로_삭제한다() {
        final long first = observation(batch("2026-11-07T03:00Z"));
        final long second = observation(batch("2026-11-07T04:00Z"));
        final long arrival = observation(batch("2026-11-07T04:10Z"));
        forecast(first, false, 0);
        forecast(second, true, arrival);
        MemoryObjects objects = new MemoryObjects();
        service(objects).advance();
        for (String table : List.of("forecast_evaluation_result", "forecast_evaluation_compact", "seat_forecast",
            "forecast_publication", "vehicle_observation", "observation_batch")) {
            assertThat(count(table)).as(table).isZero();
        }
        assertSoftly(softly -> {
            softly.assertThat(objects.values.keySet()).anyMatch(k -> k.contains("forecast_evaluation_compact"));
            softly.assertThat(objects.values.keySet()).anyMatch(k -> k.contains("evidence"));
        });
    }

    @Test
    void 일부_삭제_후_중단해도_검증된_파일에서_다시_시작한다() {
        final long id = observation(batch("2026-11-07T03:00Z"));
        forecast(id, false, 0);
        MemoryObjects objects = new MemoryObjects();
        objects.failPurgeOnce = true;
        service(objects).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("forecast_evaluation_result")).isZero();
            softly.assertThat(count("seat_forecast")).isOne();
        });
        jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity',lease_until='-infinity'").update();
        service(objects).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("seat_forecast")).isZero();
            softly.assertThat(count("vehicle_observation")).isZero();
            softly.assertThat(jdbc.sql("SELECT state FROM operational_archive_job").query(String.class).single())
                .isEqualTo("DONE");
        });
    }

    @Test
    void 보관한_파일이_변조되면_삭제하지_않는다() {
        observation(batch("2026-11-07T03:00Z"));
        MemoryObjects objects = new MemoryObjects();
        ArchiveService exportOnly = new ArchiveService(repository, objects, clock,
            new SimpleMeterRegistry(), "salmonbus/test/lake-v2/", 2, false);
        exportOnly.advance();
        String key = objects.values.keySet().stream().filter(k -> k.contains("vehicle_observation")).findFirst()
            .orElseThrow();
        objects.values.get(key)[0] ^= 1;
        jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity',lease_until='-infinity'").update();
        service(objects).advance();
        assertThat(count("vehicle_observation")).isOne();
    }

    @Test
    void 빈_응답과_실패_수집도_원본으로_보존한다() {
        final long id = batch("2026-11-07T03:00Z");
        jdbc.sql("UPDATE observation_batch SET" + " outcome='FAILED_UNREADABLE',response_received_at=NULL WHERE id=?")
            .param(id).update();
        MemoryObjects objects = new MemoryObjects();
        service(objects).advance();
        assertSoftly(softly -> {
            softly.assertThat(count("observation_batch")).isZero();
            softly.assertThat(objects.values.keySet()).anyMatch(k -> k.contains("observation_batch"));
        });
    }

    @Test
    void 고정_로컬_표본의_검증과_삭제_처리량을_기록한다() throws Exception {
        final long batch = batch("2026-11-07T03:00Z");
        jdbc.sql("""
            INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                stop_order,stop_id,running_state,passed_stop_order,remaining_seats)
            SELECT ?,?,n,concat('v',n),1,'s1',2,1,12 FROM generate_series(1,3000) n
            """).params(batch, version).update();
        MemoryObjects objects = new MemoryObjects();
        final long started = System.nanoTime();
        new ArchiveService(repository, objects, clock, new SimpleMeterRegistry(),
            "salmonbus/test/lake-v2/", 2000, true).advance();
        final long elapsed = System.nanoTime() - started;
        assertThat(count("vehicle_observation")).isZero();
        final long bytes = objects.values.values().stream().mapToLong(v -> v.length).sum();
        Path report = Path.of("build/reports/archive-local-measurement.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report,
            "{\"environment\":\"isolated PostgreSQL18 + in-memory objects, not S3"
                + " throughput\",\"pageSize\":2000,\"observations\":3000,\"objectBytes\":" + bytes + ",\"elapsedMs\":"
                + elapsed / 1000000 + ",\"observationRowsPerSecond\":" + (3000.0 * 1000000000 / elapsed) + "}");
    }

    @Test
    void FK_검사_인덱스가_없으면_운영_원본을_삭제하지_않는다() {
        observation(batch("2026-11-07T03:00Z"));
        jdbc.sql("DROP INDEX ix_archive_compact_arrival_fk").update();
        try {
            service(new MemoryObjects()).advance();
            assertThat(count("vehicle_observation")).isOne();
        } finally {
            jdbc.sql(
                "CREATE INDEX ix_archive_compact_arrival_fk ON forecast_evaluation_compact(arrival_observation_id)")
                .update();
        }
    }

    @Test
    void 날짜_경계에서_보류한_작업은_다음날_새_보관_기준으로_재개한다() {
        final long id = observation(batch("2026-11-07T03:00Z"));
        forecast(id, false, 0);
        jdbc.sql("UPDATE forecast_evaluation_result SET scored_at='2026-11-08T03:00Z'").update();
        MemoryObjects objects = new MemoryObjects();
        service(objects).advance();
        assertThat(count("vehicle_observation")).isOne();
        jdbc.sql("UPDATE operational_archive_job SET retry_at='-infinity',lease_until='-infinity'").update();
        Clock nextDay = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
        new ArchiveService(repository, objects, nextDay, new SimpleMeterRegistry(),
            "salmonbus/test/lake-v2/", 2000, true).advance();
        assertThat(count("vehicle_observation")).isZero();
    }

    static class MemoryObjects implements ArchiveObjects {
        final Map<String, byte[]> values = new HashMap<>();
        boolean failOnce;
        boolean failPurgeOnce;
        boolean published;

        public void putVerified(String key, byte[] bytes) {
            byte[] prior = values.putIfAbsent(key, bytes.clone());
            if (prior != null && !Arrays.equals(prior, bytes)) {
                throw new IllegalStateException("OBJECT_CONFLICT");
            }
            if (failOnce) {
                failOnce = false;
                throw new IllegalStateException("RESPONSE_LOST");
            }
            if (!Arrays.equals(bytes, read(key, bytes.length))) {
                throw new IllegalStateException("READBACK");
            }
        }

        public byte[] read(String key, final int limit) {
            if (key.contains("/manifest-")) {
                published = true;
            }
            if (published && failPurgeOnce && key.contains("-seat_forecast-")) {
                failPurgeOnce = false;
                throw new IllegalStateException("PURGE_INTERRUPTED");
            }
            byte[] bytes = Objects.requireNonNull(values.get(key), key);
            if (bytes.length > limit) {
                throw new IllegalStateException("LIMIT");
            }
            return bytes.clone();
        }
    }
}
