package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.support.TestDatabase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

/**
 * 공유 PG 서버의 격리 DB 에서 옛 테이블 내보내기를 실제 옛 테이블과 시험용 테이블로 검증한다.
 *
 * <p>마이그레이션을 끝까지 적용한 뒤 FK 와 사용자 트리거를 지운다. 이 시험은 테이블 사이 관계가 아니라 한 테이블의 줄을 S3 조각으로
 * 빠짐없이 옮기는지를 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LegacyExportIntegrationTest {
    static final String PREFIX = "salmonbus/test/lake-v3-legacy/";
    static final long MIN_SEGMENT = LegacyExportSettings.MIN_SEGMENT_BYTES;
    /** 쓰지 않게 된 테이블을 지우는 마이그레이션 바로 앞 버전이다. 그 뒤로는 줄이 있는 DB에만 이 테이블들이 남는다. */
    static final String BEFORE_RETIREMENT = "82";
    static final List<String> USED = List.of("seat_forecast", "model_training_exclusion",
        "statistics_training_exclusion", "alighting_settlement_generation", "legacy_types", "legacy_without_key",
        "legacy_interval");
    TestDatabase database;
    DriverManagerDataSource source;
    JdbcClient jdbc;
    final List<Duration> pauses = new ArrayList<>();
    Clock clock = Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC);

    @BeforeAll
    void migrate() {
        database = TestDatabase.create();
        source = new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password());
        jdbc = JdbcClient.create(source);
        Flyway.configure().dataSource(source).target(BEFORE_RETIREMENT).load().migrate();
        jdbc.sql("""
            INSERT INTO model_training_exclusion(release_id, bundle_digest, calculation_version,
                statistics_baseline_count, statistics_baseline_not_after, classification, decision_reference,
                recorded_at)
            VALUES ('kept', repeat('a', 64), 'c', 0, now(), 'OPERATOR_EXCLUDED', 'd', now())
            """).update();
        Flyway.configure().dataSource(source).load().migrate();
        jdbc.sql("""
            DO $$ DECLARE r record; BEGIN
                FOR r IN SELECT conrelid::regclass AS relation, conname FROM pg_constraint
                    WHERE contype = 'f' AND connamespace = 'public'::regnamespace LOOP
                    EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', r.relation, r.conname);
                END LOOP;
                FOR r IN SELECT tgrelid::regclass AS relation, tgname FROM pg_trigger WHERE NOT tgisinternal LOOP
                    EXECUTE format('DROP TRIGGER %I ON %s', r.tgname, r.relation);
                END LOOP;
            END $$
            """).update();
        jdbc.sql("""
            CREATE TABLE legacy_types (
                key_text varchar(40) NOT NULL, key_time timestamptz NOT NULL, key_uuid uuid NOT NULL,
                c_bigint bigint, c_integer integer, c_smallint smallint, c_boolean boolean, c_text text,
                c_varchar varchar(12), c_char character(5), c_uuid uuid, c_date date, c_timestamptz timestamptz,
                c_timestamp timestamp, c_double double precision, c_real real, c_numeric numeric,
                c_numeric_scaled numeric(12,4), c_jsonb jsonb, c_bytea bytea, c_xid8 xid8, c_bigints bigint[],
                c_doubles double precision[], c_texts text[], c_grid integer[], c_numerics numeric[],
                PRIMARY KEY (key_text, key_time, key_uuid))
            """).update();
        jdbc.sql("CREATE TABLE legacy_without_key (id bigint)").update();
        jdbc.sql("CREATE TABLE legacy_interval (id bigint PRIMARY KEY, span interval)").update();
    }

    @AfterAll
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("""
            DO $$ DECLARE r record; BEGIN
                FOR r IN SELECT tablename FROM pg_tables WHERE tablename LIKE 'restored_%' LOOP
                    EXECUTE format('DROP TABLE %I', r.tablename);
                END LOOP;
            END $$
            """).update();
        jdbc.sql("TRUNCATE daily_export, " + String.join(", ", USED)).update();
        pauses.clear();
        clock = Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC);
    }

    LegacyExportSettings settings(final long segmentBytes, final long maxRows, final int pageSize) {
        return new LegacyExportSettings(PREFIX, Duration.ofMinutes(1), pageSize, Duration.ofMillis(7), segmentBytes,
            maxRows, Duration.ofMinutes(10), Duration.ofSeconds(30));
    }

    LegacyExportRepository repository(List<String> tables) {
        return new LegacyExportRepository(jdbc, new JdbcTransactionManager(source), Duration.ofSeconds(30), tables);
    }

    LegacyExportService service(
        PartitionExportIntegrationTest.MemoryObjects objects,
        List<String> tables,
        LegacyExportSettings settings,
        SimpleMeterRegistry metrics
    ) {
        return new LegacyExportService(repository(tables), objects, clock, metrics, settings, pauses::add);
    }

    LegacyExportService service(
        PartitionExportIntegrationTest.MemoryObjects objects,
        List<String> tables,
        LegacyExportSettings settings
    ) {
        return service(objects, tables, settings, new SimpleMeterRegistry());
    }

    /**
     * {@code vehicle_observation_id} 가 {@code from..to} 이고 정류장 순서가 1·2 인 예측을 넣는다. {@code feature_snapshot} 은 SQL NULL,
     * JSON {@code null}, {@code pad} 바이트를 더한 객체가 섞인다.
     */
    void seatForecasts(final int from, final int to, final int pad) {
        jdbc.sql("""
            INSERT INTO seat_forecast(vehicle_observation_id, target_stop_order, route_version_id, stops_to_target,
                model_deployment_id, demand_statistics_revision, seat_full_chance_raw, seat_full_chance, expected_seats,
                generated_at, publication_id, label_version, feature_snapshot, inference_bundle_hash)
            SELECT n, s, 1, s, 1, 0, 0.30000000000000004, 1.0 / 3, CASE WHEN n % 3 = 0 THEN NULL ELSE 5e-324 END,
                timestamptz '2026-11-01 00:00:00.123456+00' + n * interval '1 second', 1, 'v' || n,
                CASE WHEN n % 5 = 0 THEN NULL WHEN n % 5 = 1 THEN 'null'::jsonb
                    ELSE jsonb_build_object('pad', repeat('x', ?), 'n', n, 'nested', jsonb_build_array(1.50, 'é', NULL))
                    END,
                repeat('c', 64)
            FROM generate_series(?, ?) n CROSS JOIN generate_series(1, 2) s
            """).params(pad, from, to).update();
    }

    void exclusion(String release, final int digest) {
        jdbc.sql("""
            INSERT INTO model_training_exclusion VALUES (?, lpad(to_hex(?), 64, '0'), NULL, ? || '/' || ?, NULL, NULL,
                3, '2026-11-01 00:00:00+00', 'OPERATOR_EXCLUDED', 'note', '2026-11-02 00:00:00.5+00')
            """).params(release, digest, release, digest).update();
    }

    List<Map<String, Object>> ledger(String table) {
        return jdbc.sql("SELECT * FROM daily_export WHERE table_name = ? ORDER BY segment").param(table).query()
            .listOfRows();
    }

    long ledgerRows(String table) {
        return count("SELECT coalesce(sum(row_count), 0) FROM daily_export WHERE table_name = '" + table + "'");
    }

    long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    long restore(PartitionExportIntegrationTest.MemoryObjects objects, String table, String target) {
        List<String[]> segments = ledger(table).stream()
            .map(row -> new String[] { (String) row.get("object_key"), ((String) row.get("sha256")).trim() }).toList();
        return PartitionRestore.restoreLegacy(jdbc, objects, table, segments, target);
    }

    /** 두 테이블의 줄이 줄 단위로, 그리고 텍스트 표기까지 같은지 본다. */
    void assertSameRows(String original, String restored) {
        assertSoftly(softly -> {
            softly.assertThat(count("SELECT count(*) FROM ((SELECT * FROM " + original + " EXCEPT ALL SELECT * FROM "
                + restored + ") UNION ALL (SELECT * FROM " + restored + " EXCEPT ALL SELECT * FROM " + original
                + ")) d")).isZero();
            softly.assertThat(jdbc.sql("SELECT string_agg(t::text, E'\\n' ORDER BY t::text) FROM " + restored + " t")
                .query(String.class).single()).isEqualTo(jdbc.sql("SELECT string_agg(t::text, E'\\n' ORDER BY t::text)"
                + " FROM " + original + " t").query(String.class).single());
        });
    }

    double gauge(SimpleMeterRegistry metrics, String name, String table) {
        return metrics.get(name).tag("table", table).gauge().value();
    }

    @Test
    void 옛_테이블을_기본_키_순서의_여러_조각으로_내보내고_장부_합이_줄_수와_같으며_S3_조각만으로_되살린다() {
        // given
        seatForecasts(1, 40, 4000);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        LegacyExportService.RunReport report = service(objects, List.of("seat_forecast"),
            settings(MIN_SEGMENT, 1_000_000, 7), metrics).run();

        // then
        List<Map<String, Object>> ledger = ledger("seat_forecast");
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(report.rows()).isEqualTo(80);
            softly.assertThat(ledger).hasSizeGreaterThanOrEqualTo(3).hasSize(report.segments());
            softly.assertThat(ledgerRows("seat_forecast")).isEqualTo(80);
            softly.assertThat(count("SELECT count(*) FROM seat_forecast")).isEqualTo(80);
            softly.assertThat(ledger.getFirst().get("key_from")).isEqualTo("[1, 1]");
            softly.assertThat(ledger.getLast().get("key_to")).isEqualTo("[40, 2]");
            for (int i = 0; i < ledger.size(); i++) {
                Map<String, Object> row = ledger.get(i);
                softly.assertThat(row.get("segment")).isEqualTo(i);
                softly.assertThat(row.get("source_date")).isEqualTo(Date.valueOf(LocalDate.of(2000, 1, 1)));
                softly.assertThat(row.get("verified_at")).isNotNull();
                softly.assertThat(row.get("dropped_at")).isNull();
                softly.assertThat((String) row.get("object_key"))
                    .startsWith(PREFIX + "seat_forecast/segment=" + String.format("%06d", i) + "/manifest-");
            }
            softly.assertThat(gauge(metrics, "salmonbus_archive_legacy_complete", "seat_forecast")).isOne();
            softly.assertThat(gauge(metrics, "salmonbus_archive_legacy_exported_rows", "seat_forecast"))
                .isEqualTo(80);
            softly.assertThat(gauge(metrics, "salmonbus_archive_legacy_segments", "seat_forecast"))
                .isEqualTo(ledger.size());
        });
        LegacySegmentManifest second = LegacySegmentManifest.parse(objects.values.get(
            (String) ledger.get(1).get("object_key")));
        assertSoftly(softly -> {
            softly.assertThat(second.after()).isEqualTo(ledger.get(0).get("key_to"));
            softly.assertThat(second.orderBy()).containsExactly("vehicle_observation_id", "target_stop_order");
        });
        assertThat(restore(objects, "seat_forecast", "restored_seat_forecast")).isEqualTo(80);
        assertSameRows("seat_forecast", "restored_seat_forecast");
    }

    @Test
    void 조각마다_첫_페이지를_읽기_직전_시각을_장부에_남긴다() {
        // given
        seatForecasts(1, 40, 4000);
        Instant start = Instant.parse("2026-11-15T03:00:00Z");
        AtomicLong pagesRead = new AtomicLong();
        clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return start.plusSeconds(pagesRead.get());
            }
        };
        LegacyExportRepository reading = new LegacyExportRepository(jdbc, new JdbcTransactionManager(source),
            Duration.ofSeconds(30), List.of("seat_forecast")) {
            @Override
            public LegacyExportRepository.Page page(
                String table,
                List<PartitionManifest.Column> columns,
                List<String> keys,
                String after,
                final int limit
            ) {
                pagesRead.incrementAndGet();
                return super.page(table, columns, keys, after, limit);
            }
        };
        LegacyExportService service = new LegacyExportService(reading,
            new PartitionExportIntegrationTest.MemoryObjects(), clock, new SimpleMeterRegistry(),
            settings(MIN_SEGMENT, 1_000_000, 7), pauses::add);

        // when
        service.run();

        // then
        List<Map<String, Object>> ledger = ledger("seat_forecast");
        assertSoftly(softly -> {
            softly.assertThat(ledger).hasSizeGreaterThanOrEqualTo(2);
            softly.assertThat(((Timestamp) ledger.getFirst().get("read_started_at")).toInstant()).isEqualTo(start);
            for (Map<String, Object> row : ledger) {
                softly.assertThat(((Timestamp) row.get("read_started_at")).toInstant())
                    .isBefore(((Timestamp) row.get("exported_at")).toInstant());
            }
        });
    }

    @Test
    void 회차_줄_수_상한에서_멈추고_다음_회차가_겹치거나_빠지지_않게_이어_간다() {
        // given
        seatForecasts(1, 5, 10);
        exclusion("release", 1);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportService service = service(objects, List.of("seat_forecast", "model_training_exclusion"),
            settings(MIN_SEGMENT, 3, 2));

        // when
        LegacyExportService.RunReport first = service.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(first.rows()).isEqualTo(3);
            softly.assertThat(first.stoppedBy()).isEqualTo(LegacyExportService.StopReason.ROW_LIMIT);
            softly.assertThat(first.tables()).containsOnlyKeys("seat_forecast")
                .containsEntry("seat_forecast", LegacyExportService.TableStatus.IN_PROGRESS);
            softly.assertThat(ledger("seat_forecast")).singleElement().satisfies(row -> {
                assertThat(row.get("row_count")).isEqualTo(3L);
                assertThat(row.get("key_from")).isEqualTo("[1, 1]");
                assertThat(row.get("key_to")).isEqualTo("[2, 1]");
            });
            softly.assertThat(pauses).containsExactly(Duration.ofMillis(7), Duration.ofMillis(7));
        });

        // when
        List<LegacyExportService.RunReport> later = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            later.add(service.run());
        }

        // then
        List<Map<String, Object>> ledger = ledger("seat_forecast");
        assertSoftly(softly -> {
            softly.assertThat(later).allSatisfy(report -> assertThat(report.rows()).isLessThanOrEqualTo(3));
            softly.assertThat(ledger).extracting(row -> row.get("row_count")).containsExactly(3L, 3L, 3L, 1L);
            softly.assertThat(ledger).extracting(row -> row.get("key_from"))
                .containsExactly("[1, 1]", "[2, 2]", "[4, 1]", "[5, 2]");
            softly.assertThat(ledger).extracting(row -> row.get("key_to"))
                .containsExactly("[2, 1]", "[3, 2]", "[5, 1]", "[5, 2]");
            softly.assertThat(later.getLast().tables())
                .containsEntry("seat_forecast", LegacyExportService.TableStatus.COMPLETE)
                .containsEntry("model_training_exclusion", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(ledgerRows("model_training_exclusion")).isOne();
        });
        assertThat(restore(objects, "seat_forecast", "restored_seat_forecast")).isEqualTo(10);
        assertSameRows("seat_forecast", "restored_seat_forecast");
    }

    @Test
    void 회차_시간_상한에서_멈춘다() {
        // given
        seatForecasts(1, 10, 10);
        AtomicLong ticks = new AtomicLong();
        clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.parse("2026-11-15T03:00:00Z").plusSeconds(ticks.getAndIncrement());
            }
        };
        LegacyExportSettings settings = new LegacyExportSettings(PREFIX, Duration.ofMinutes(1), 2,
            Duration.ofMillis(7), MIN_SEGMENT, 1_000_000, Duration.ofSeconds(3), Duration.ofSeconds(30));

        // when
        LegacyExportService.RunReport report = service(new PartitionExportIntegrationTest.MemoryObjects(),
            List.of("seat_forecast"), settings).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.stoppedBy()).isEqualTo(LegacyExportService.StopReason.TIME_LIMIT);
            softly.assertThat(report.rows()).isPositive().isLessThan(20);
            softly.assertThat(ledgerRows("seat_forecast")).isEqualTo(report.rows());
        });
    }

    @Test
    void 조각을_올리다_끊기면_장부에_적지_않고_다음_회차가_같은_키부터_다시_만든다() {
        // given
        seatForecasts(1, 3, 10);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        objects.failManifestOnce = true;
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        LegacyExportService service = service(objects, List.of("seat_forecast"), settings(MIN_SEGMENT, 1000, 100),
            metrics);

        // when
        LegacyExportService.RunReport failed = service.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(failed.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.FAILED);
            softly.assertThat(ledger("seat_forecast")).isEmpty();
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("reason", "RESPONSE_LOST")
                .counter().count()).isOne();
        });

        // when
        LegacyExportService.RunReport retried = service.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(retried.tables())
                .containsEntry("seat_forecast", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(ledger("seat_forecast")).singleElement().satisfies(row -> {
                assertThat(row.get("segment")).isEqualTo(0);
                assertThat(row.get("row_count")).isEqualTo(6L);
            });
            softly.assertThat(count("SELECT count(*) FROM seat_forecast")).isEqualTo(6);
        });
    }

    @Test
    void 같은_키에_다른_내용의_파일이_이미_있으면_조각을_장부에_적지_않는다() {
        // given
        seatForecasts(1, 3, 10);
        PartitionExportIntegrationTest.MemoryObjects earlier = new PartitionExportIntegrationTest.MemoryObjects();
        service(earlier, List.of("seat_forecast"), settings(MIN_SEGMENT, 1000, 100)).run();
        String file = earlier.values.keySet().stream()
            .filter(k -> k.endsWith(".jsonl.gz"))
            .findFirst()
            .orElseThrow();
        jdbc.sql("TRUNCATE daily_export").update();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        objects.values.put(file, ArchiveFiles.encode(List.of("{\"changed\":true}")));
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        LegacyExportService.RunReport report =
            service(objects, List.of("seat_forecast"), settings(MIN_SEGMENT, 1000, 100), metrics).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.FAILED);
            softly.assertThat(ledger("seat_forecast")).isEmpty();
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("reason", "FILE_HASH_MISMATCH")
                .counter().count()).isOne();
            softly.assertThat(count("SELECT count(*) FROM seat_forecast")).isEqualTo(6);
        });
    }

    @Test
    void 텍스트_복합_키와_uuid_시각_날짜_키_테이블도_조금씩_이어_가며_빠짐없이_내보낸다() {
        // given
        List<String> releases = List.of("a", "B", "b ", "b", "Ä", "가", "z-1", "z_1", "Z");
        for (int i = 0; i < releases.size(); i++) {
            exclusion(releases.get(i), i);
            exclusion(releases.get(i), i + 100);
        }
        jdbc.sql("""
            INSERT INTO statistics_training_exclusion
            SELECT ('00000000-0000-0000-0000-' || lpad(to_hex(n % 3), 12, '0'))::uuid, 'r', repeat('d', 64), n % 2,
                'calc-' || (n % 4), 1 + n % 2, timestamptz '2026-11-01 00:00:00.000001+09' + n * interval '1 microsecond',
                timestamptz '2026-11-02 12:00:00+00' - n * interval '1 hour', 1, now()
            FROM generate_series(1, 23) n
            """).update();
        jdbc.sql("""
            INSERT INTO alighting_settlement_generation
            SELECT n % 3, DATE '2026-10-30' + n, n FROM generate_series(1, 11) n
            """).update();
        List<String> tables = List.of("model_training_exclusion", "statistics_training_exclusion",
            "alighting_settlement_generation");
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportService service = service(objects, tables, settings(MIN_SEGMENT, 4, 3));

        // when
        LegacyExportService.RunReport last = null;
        for (int run = 0; run < 30; run++) {
            last = service.run();
            if (last.tables().size() == 3 && last.tables().values().stream()
                .allMatch(s -> s == LegacyExportService.TableStatus.COMPLETE)) {
                break;
            }
        }

        // then
        assertThat(last.tables().values()).containsOnly(LegacyExportService.TableStatus.COMPLETE);
        for (String table : tables) {
            assertThat(ledger(table)).as(table).hasSizeGreaterThan(1);
            assertThat(ledgerRows(table)).as(table).isEqualTo(count("SELECT count(*) FROM " + table));
            restore(objects, table, "restored_" + table);
            assertSameRows(table, "restored_" + table);
        }
    }

    @Test
    void 지원하는_모든_타입이_JSON을_거쳐_같은_값과_같은_텍스트로_돌아온다() {
        // given
        jdbc.sql("""
            INSERT INTO legacy_types VALUES
            ('first "quoted" \\ key', '2026-11-15 12:34:56.123456+09', '00000000-0000-0000-0000-000000000001',
                -9223372036854775808, -2147483648, 32767, true, E'줄\\n바꿈 "따옴표" \\\\ 탭\\t 😀', 'v', 'ab',
                '6f1c5b1e-3c33-4d6a-9a43-3e2a1c6b9f00', '0001-01-01', 'infinity', '2026-11-15 12:34:56.000001', 'NaN',
                3.4028235e38, '123456789012345678901234567890.000000000000000000000000000001', 12345678.1234,
                '{"k": "v\\n", "n": 1.50, "big": 12345678901234567890, "a": [null, true, {"é": []}]}',
                (SELECT decode(string_agg(lpad(to_hex(i), 2, '0'), ''), 'hex') FROM generate_series(0, 255) i),
                '18446744073709551615', '{1,NULL,9223372036854775807}', '{NaN,Infinity,-Infinity,5e-324,0.1}',
                '{"a,b","c\\"d",NULL,""}', '{{1,2},{3,4}}', '{NaN,Infinity,1.10}'),
            ('', '-infinity', '00000000-0000-0000-0000-000000000002', 9223372036854775807, NULL, NULL, false, '',
                '', '', NULL, '-infinity', '-infinity', 'infinity', 'Infinity', 'NaN', 'NaN', -0.0001, '[]', '\\x',
                '0', '{}', '{1.7976931348623157e308,-Infinity}', '{}', '{}', '{}'),
            ('null', '1970-01-01 00:00:00+00', '00000000-0000-0000-0000-000000000003', NULL, NULL, NULL, NULL, NULL,
                NULL, NULL, NULL, NULL, NULL, NULL, 0.1, 1.1754944e-38, '-Infinity', NULL, 'null', NULL, NULL, NULL,
                NULL, NULL, NULL, NULL)
            """).update();
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();

        // when
        LegacyExportService.RunReport report = service(objects, List.of("legacy_types"),
            settings(MIN_SEGMENT, 1000, 1)).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("legacy_types", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(ledger("legacy_types")).singleElement().satisfies(row -> {
                assertThat(row.get("key_from"))
                    .isEqualTo("[\"\", \"-infinity\", \"00000000-0000-0000-0000-000000000002\"]");
                assertThat(row.get("row_count")).isEqualTo(3L);
            });
        });
        assertThat(restore(objects, "legacy_types", "restored_legacy_types")).isEqualTo(3);
        assertSameRows("legacy_types", "restored_legacy_types");
        Set<String> covered = repository(List.of("legacy_types")).columns("legacy_types").stream()
            .map(c -> c.type().replaceAll("\\(\\d+(,\\d+)?\\)", "").replace("[]", "")).collect(Collectors.toSet());
        assertThat(covered).containsAll(LegacyExportRepository.SUPPORTED_TYPES);
    }

    @Test
    void 옛_테이블_25개는_모두_기본_키가_있고_열_타입은_모두_왕복을_확인한_타입이다() {
        // given
        LegacyExportRepository repository = repository(LegacyExportRepository.TABLES);

        // then
        assertSoftly(softly -> {
            for (String table : LegacyExportRepository.TABLES) {
                softly.assertThat(repository.exists(table)).as(table).isTrue();
                softly.assertThat(repository.primaryKey(table)).as(table).isNotEmpty();
                softly.assertThat(LegacyExportRepository.unsupportedColumns(repository.columns(table))).as(table)
                    .isEmpty();
            }
        });
    }

    @Test
    void 내보내는_중에_커서_뒤로_줄이_더_들어오면_다음_회차에_따라잡는다() {
        // given
        seatForecasts(1, 3, 10);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportService service = service(objects, List.of("seat_forecast"), settings(MIN_SEGMENT, 4, 10));
        service.run();

        // when
        seatForecasts(10, 11, 10);
        LegacyExportService.RunReport second = service.run();
        LegacyExportService.RunReport third = service.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(second.rows()).isEqualTo(4);
            softly.assertThat(third.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(ledgerRows("seat_forecast")).isEqualTo(10)
                .isEqualTo(count("SELECT count(*) FROM seat_forecast"));
        });
        assertThat(restore(objects, "seat_forecast", "restored_seat_forecast")).isEqualTo(10);
        assertSameRows("seat_forecast", "restored_seat_forecast");
    }

    @Test
    void 이미_내보낸_범위의_줄이_지워지면_장부를_고치지_않고_어긋남을_지표와_로그로_알린다() {
        // given
        seatForecasts(1, 5, 10);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportSettings settings = settings(MIN_SEGMENT, 4, 10);
        LegacyExportService first = service(objects, List.of("seat_forecast"), settings);
        for (int run = 0; run < 4; run++) {
            first.run();
        }
        List<Map<String, Object>> before = ledger("seat_forecast");
        jdbc.sql("DELETE FROM seat_forecast WHERE vehicle_observation_id = 2").update();
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        LegacyExportService.RunReport report = service(objects, List.of("seat_forecast"), settings, metrics).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.MISMATCH);
            softly.assertThat(ledger("seat_forecast")).isEqualTo(before).hasSize(3);
            softly.assertThat(ledgerRows("seat_forecast")).isEqualTo(10);
            softly.assertThat(count("SELECT count(*) FROM seat_forecast")).isEqualTo(8);
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("table", "seat_forecast")
                .tag("reason", "ROWS_REMOVED").counter().count()).isOne();
            softly.assertThat(gauge(metrics, "salmonbus_archive_legacy_mismatched_segments", "seat_forecast"))
                .isOne();
            softly.assertThat(gauge(metrics, "salmonbus_archive_legacy_complete", "seat_forecast")).isZero();
        });
    }

    @Test
    void 커서_앞에_끼어든_줄은_그_조각의_범위를_다시_세어_알아본다() {
        // given
        exclusion("b", 1);
        exclusion("d", 1);
        exclusion("f", 1);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportService service = service(objects, List.of("model_training_exclusion"),
            settings(MIN_SEGMENT, 2, 10));
        service.run();
        exclusion("a", 1);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        service.run();
        LegacyExportService.RunReport report = service(objects, List.of("model_training_exclusion"),
            settings(MIN_SEGMENT, 2, 10), metrics).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("model_training_exclusion",
                LegacyExportService.TableStatus.MISMATCH);
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("reason", "ROWS_ADDED_BEHIND_CURSOR")
                .counter().count()).isOne();
            softly.assertThat(ledgerRows("model_training_exclusion")).isEqualTo(3);
        });
    }

    @Test
    void 기본_키가_없거나_확인하지_않은_타입이_있는_테이블은_실패로_남기고_다음_테이블을_계속한다() {
        // given
        jdbc.sql("INSERT INTO legacy_without_key VALUES (1)").update();
        jdbc.sql("INSERT INTO legacy_interval VALUES (1, interval '1 day')").update();
        seatForecasts(1, 2, 10);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();

        // when
        LegacyExportService.RunReport report = service(new PartitionExportIntegrationTest.MemoryObjects(),
            List.of("legacy_absent", "legacy_without_key", "legacy_interval", "seat_forecast"),
            settings(MIN_SEGMENT, 1000, 10), metrics).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "legacy_absent", LegacyExportService.TableStatus.ABSENT,
                "legacy_without_key", LegacyExportService.TableStatus.FAILED,
                "legacy_interval", LegacyExportService.TableStatus.FAILED,
                "seat_forecast", LegacyExportService.TableStatus.COMPLETE));
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("table", "legacy_without_key")
                .tag("reason", "PRIMARY_KEY_REQUIRED").counter().count()).isOne();
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("table", "legacy_interval")
                .tag("reason", "UNSUPPORTED_COLUMN_TYPE").counter().count()).isOne();
            softly.assertThat(count("SELECT count(*) FROM daily_export WHERE table_name LIKE 'legacy_%'")).isZero();
        });
    }

    @Test
    void 다른_실행이_먼저_조각을_적으면_겹쳐_적지_않고_다음_회차에_장부를_다시_읽어_이어_간다() {
        // given
        seatForecasts(1, 4, 10);
        PartitionExportIntegrationTest.MemoryObjects objects = new PartitionExportIntegrationTest.MemoryObjects();
        LegacyExportSettings settings = settings(MIN_SEGMENT, 1000, 3);
        LegacyExportService other = service(objects, List.of("seat_forecast"), settings);
        LegacyExportRepository racing = new LegacyExportRepository(jdbc, new JdbcTransactionManager(source),
            Duration.ofSeconds(30), List.of("seat_forecast")) {
            @Override
            public LegacyExportRepository.RecordResult record(
                String table,
                LegacyExportRepository.Segment segment,
                String expectedAfter
            ) {
                other.run();
                return super.record(table, segment, expectedAfter);
            }
        };
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        LegacyExportService mine = new LegacyExportService(racing, objects, clock, metrics, settings, pauses::add);

        // when
        LegacyExportService.RunReport report = mine.run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(report.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.FAILED);
            softly.assertThat(metrics.get("salmonbus_archive_legacy_failures").tag("reason", "LEDGER_CHANGED")
                .counter().count()).isOne();
            softly.assertThat(ledger("seat_forecast")).singleElement()
                .satisfies(row -> assertThat(row.get("row_count")).isEqualTo(8L));
        });

        // when
        LegacyExportService.RunReport next = service(objects, List.of("seat_forecast"), settings).run();

        // then
        assertSoftly(softly -> {
            softly.assertThat(next.tables()).containsEntry("seat_forecast", LegacyExportService.TableStatus.COMPLETE);
            softly.assertThat(next.segments()).isZero();
            softly.assertThat(ledgerRows("seat_forecast")).isEqualTo(8);
        });
    }
}
