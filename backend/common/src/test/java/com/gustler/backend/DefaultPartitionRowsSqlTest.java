package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.support.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 기본 파티션에 든 줄을 제 날짜 파티션으로 옮기는 수동 SQL({@code deploy/sql/move-partition-default-rows.sql})을 PostgreSQL 18에서
 * 확인한다.
 *
 * <p>마이그레이션을 끝까지 적용해 일곱 테이블이 모두 날짜 파티션 테이블인 DB를 원본으로 한 번 만들고, 시험마다 그 원본을 복제한 새
 * DB를 쓴다. SQL 파일은 psql처럼 한 연결에서 문장 단위로 실행하고, 실패하면 그 연결의 트랜잭션을 되돌린다.
 */
class DefaultPartitionRowsSqlTest {
    private static final String FILE = "move-partition-default-rows.sql";
    private static final List<String> TABLES = List.of("forecast_run", "forecast_record", "stop_pass", "pass_label",
        "forecast_unresolved", "observation_batch", "vehicle_observation");
    /** 날짜 파티션이 없는 지난 날짜다. 이 날짜의 줄은 기본 파티션으로 들어간다. */
    private static final LocalDate FIRST = LocalDate.parse("2026-10-01");
    private static final LocalDate SECOND = LocalDate.parse("2026-10-02");
    /** 시험이 날짜 파티션을 미리 만들어 두는 날짜다. 이 날짜의 줄은 옮기는 대상이 아니다. */
    private static final LocalDate KEPT = LocalDate.parse("2026-10-03");
    /** 쓰지 않는 객체를 지우는 마이그레이션까지 적용한, 관측 테이블을 파티션 테이블로 다시 만들기 바로 앞 버전이다. */
    private static final String BEFORE_PARTITIONING = "83";
    private static final String ROUTE = """
        INSERT INTO route(id,public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            OVERRIDING SYSTEM VALUE VALUES (1,'p1','s1','sr1','1','a','b');
        INSERT INTO route_version(id,route_id,content_digest,valid_from) OVERRIDING SYSTEM VALUE
            VALUES (1,1,repeat('0',64),'2026-01-01T00:00:00Z');
        INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT 1,n,'st'||n,'s'||n,'UP',true FROM generate_series(1,3) n
        """;
    /** 일곱 테이블에 한 날짜의 줄을 넣는다. 배열에는 NaN, 무한대, NULL, 가장 작은 수를 섞는다. */
    private static final String ROWS = """
        INSERT INTO forecast_run SELECT DATE '%1$s', n, 1, 1, timestamptz '2026-10-01T00:00:00.123456Z', now(), NULL,
            'EMPTY', 0, false, 0 FROM generate_series(1,3) n;
        INSERT INTO forecast_record SELECT DATE '%1$s', n, 10, 1, 1, 1, 'v', 3, 100, 100, true, 45, false, ARRAY[1,2,3],
            '{0.1,NaN,Infinity}'::float8[], '{5e-324,NULL,0.30000000000000004}'::float8[] FROM generate_series(1,3) n;
        INSERT INTO stop_pass SELECT DATE '%1$s', 1, n, 2, 1, 'v0', 1, DATE '%1$s', 1, 10, now(), now(), 1, now(), 2, 9,
            now(), now(), NULL, 4, 2, now() FROM generate_series(1,3) n;
        INSERT INTO pass_label SELECT DATE '%1$s', 1, n, 2, 45, 'label1', 1, 'SETTLED', NULL, 1.5, NULL, NULL, NULL,
            now() FROM generate_series(1,3) n;
        INSERT INTO forecast_unresolved SELECT DATE '%1$s', n, 5, 7, 'LOST', 'PASS_NOT_SEEN', now()
            FROM generate_series(1,3) n;
        INSERT INTO observation_batch(source_date,route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
            normalization_version,collection_strategy_version)
        SELECT DATE '%1$s', 1, now(), 1, 'k'||n, 'RESERVED', 'n1', 'c1' FROM generate_series(1,3) n;
        INSERT INTO vehicle_observation(source_date,observation_batch_id,route_version_id,source_row_number,vehicle_id,
            stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
        SELECT b.source_date, b.id, 1, r, 'v'||r, 1+r, 'st'||(1+r), 1, 10, 0
        FROM observation_batch b CROSS JOIN generate_series(0,1) r WHERE b.source_date = DATE '%1$s'
        """;
    /** 한 파티션의 열, 인덱스, 제약, 경계, 소유자를 한 줄씩 낸다. 물음표 자리에는 파티션 이름이 들어간다. */
    private static final String PARTITION_SHAPE = """
        SELECT 'column ' || a.attname || ' ' || format_type(a.atttypid, a.atttypmod) || ' notnull=' || a.attnotnull
            || ' identity=' || a.attidentity::text || ' generated=' || a.attgenerated::text || ' default='
            || coalesce(pg_get_expr(d.adbin, d.adrelid), '-') AS line
        FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
        WHERE a.attrelid = '%1$s'::regclass AND a.attnum > 0 AND NOT a.attisdropped
        UNION ALL
        SELECT 'index ' || pg_get_indexdef(i.indexrelid) || ' primary=' || i.indisprimary || ' valid=' || i.indisvalid
            || ' of='
            || coalesce((SELECT inhparent::regclass::text FROM pg_inherits WHERE inhrelid = i.indexrelid), '-')
        FROM pg_index i WHERE i.indrelid = '%1$s'::regclass
        UNION ALL
        SELECT 'constraint ' || c.conname || ' ' || c.contype::text || ' ' || pg_get_constraintdef(c.oid)
            || ' validated=' || c.convalidated || ' local=' || c.conislocal || ' inherited=' || c.coninhcount
        FROM pg_constraint c WHERE c.conrelid = '%1$s'::regclass
        UNION ALL
        SELECT 'relation ' || c.relname || ' ' || pg_get_expr(c.relpartbound, c.oid) || ' owner='
            || pg_get_userbyid(c.relowner) || ' persistence=' || c.relpersistence::text || ' options='
            || coalesce(c.reloptions::text, '-')
        FROM pg_class c WHERE c.oid = '%1$s'::regclass
        ORDER BY 1
        """;

    private static TestDatabase template;

    private final List<String> notices = new ArrayList<>();
    private String name;
    private String url;
    private Connection connection;

    @BeforeEach
    void 일곱_테이블이_날짜_파티션_테이블인_원본을_복제한다() throws Exception {
        TestDatabase source = template();
        name = "c_" + UUID.randomUUID().toString().replace("-", "");
        admin(source, "CREATE DATABASE " + name + " TEMPLATE " + databaseName(source));
        url = source.jdbcUrl().replace(databaseName(source), name);
        connection = connect();
    }

    @AfterEach
    void 복제한_DB를_지운다() throws Exception {
        connection.close();
        admin(template, "DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    @AfterAll
    static void 원본을_지운다() {
        if (template != null) {
            template.close();
            template = null;
        }
    }

    @Test
    void 테이블을_고르지_않으면_기본_파티션에_든_줄_수를_날짜별로_알리고_아무것도_바꾸지_않는다() throws Exception {
        // given
        seed(FIRST);
        seed(SECOND);
        String catalog = catalogFingerprint();
        String rows = placedRows();

        // when
        summarize();

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(notices).contains(
            "DEFAULT-ROWS: forecast_run 기본 파티션에 2026-10-01 의 줄 3 개",
            "DEFAULT-ROWS: forecast_run 기본 파티션에 2026-10-02 의 줄 3 개",
            "DEFAULT-ROWS: vehicle_observation 기본 파티션에 2026-10-01 의 줄 6 개");
        softly.assertThat(notices.getLast()).startsWith("DEFAULT-ROWS: 바꾼 것이 없다");
        softly.assertThat(catalogFingerprint()).isEqualTo(catalog);
        softly.assertThat(placedRows()).isEqualTo(rows);
        softly.assertAll();
    }

    @Test
    void 일곱_테이블_모두_기본_파티션의_줄을_제_날짜_파티션으로_옮기고_줄_수와_값이_옮기기_전과_같다() throws Exception {
        // given
        for (String table : TABLES) {
            createPartition(table, KEPT);
        }
        seed(FIRST);
        seed(SECOND);
        seed(KEPT);
        for (String table : TABLES) {
            execute("CREATE TABLE expected_" + table + " AS SELECT * FROM " + table);
        }

        // when
        for (String table : TABLES) {
            move(table);
        }

        // then
        SoftAssertions softly = new SoftAssertions();
        for (String table : TABLES) {
            softly.assertThat(number("SELECT count(*) FROM " + table + "_default")).as(table + " 기본 파티션").isZero();
            softly.assertThat(placement(table)).as(table + " 줄이 든 파티션").isEqualTo(
                placed(table, FIRST) + "," + placed(table, SECOND) + "," + placed(table, KEPT));
            softly.assertThat(bound(table, FIRST)).isEqualTo("FOR VALUES FROM ('2026-10-01') TO ('2026-10-02')");
            softly.assertThat(bound(table, SECOND)).isEqualTo("FOR VALUES FROM ('2026-10-02') TO ('2026-10-03')");
            softly.assertThat(differingRows("expected_" + table, table)).as(table + " 값이 다른 줄").isZero();
            softly.assertThat(text(table)).as(table + " 줄의 글 표기").isEqualTo(text("expected_" + table));
            softly.assertThat(notices).as(table + " 알림").anyMatch(notice -> notice.startsWith("DEFAULT-ROWS: " + table
                + " 날짜 2 개를 옮기고 0 개를 건너뛰었다. 기본 파티션에 남은 줄 0 개"));
        }
        softly.assertAll();
    }

    @Test
    void 옮긴_뒤에도_IDENTITY_열은_옮긴_줄의_id와_겹치지_않는_다음_값을_준다() throws Exception {
        // given
        seed(FIRST);
        final long largest = number("SELECT max(id) FROM observation_batch");

        // when
        move("observation_batch");
        execute("""
            INSERT INTO observation_batch(source_date,route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                normalization_version,collection_strategy_version)
            VALUES (DATE '2026-10-01', 1, now(), 1, 'after-move', 'RESERVED', 'n1', 'c1')
            """);

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(number("SELECT max(id) FROM observation_batch_p20261001 WHERE attempt_key <> 'after-move'"))
            .as("옮긴 줄의 가장 큰 id").isEqualTo(largest);
        softly.assertThat(number("SELECT id FROM observation_batch_p20261001 WHERE attempt_key = 'after-move'"))
            .as("옮긴 뒤 넣은 줄의 id").isGreaterThan(largest);
        softly.assertAll();
    }

    @Test
    void 옮기기_전에는_유지_작업의_문장이_실패하고_옮긴_뒤에는_통과하며_새_줄이_그_날짜_파티션으로_간다() throws Exception {
        // given
        seed(FIRST);

        // when
        Throwable before = catchThrowable(() -> execute(maintenanceStatement("stop_pass", FIRST)));

        // then
        assertThat(before).isInstanceOfSatisfying(SQLException.class, failure -> {
            assertThat(failure.getSQLState()).isEqualTo("23514");
            assertThat(failure.getMessage()).contains("updated partition constraint for default partition"
                + " \"stop_pass_default\" would be violated by some row");
        });

        // when
        move("stop_pass");
        Throwable after = catchThrowable(() -> execute(maintenanceStatement("stop_pass", FIRST)));
        execute("INSERT INTO stop_pass SELECT DATE '2026-10-01', 1, 9, 2, 1, 'v0', 1, DATE '2026-10-01', 1, 10, now(),"
            + " now(), 1, now(), 2, 9, now(), now(), NULL, 4, 2, now()");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(after).isNull();
        softly.assertThat(placement("stop_pass")).isEqualTo(placed("stop_pass", FIRST));
        softly.assertThat(number("SELECT count(*) FROM stop_pass_p20261001")).isEqualTo(4);
        softly.assertAll();
    }

    @Test
    void 옮기며_만든_파티션은_유지_작업의_문장으로_만든_파티션과_카탈로그가_같다() throws Exception {
        // given
        seed(FIRST);

        // when
        for (String table : TABLES) {
            move(table);
            execute(maintenanceStatement(table, SECOND));
        }

        // then
        SoftAssertions softly = new SoftAssertions();
        for (String table : TABLES) {
            softly.assertThat(shape(table, FIRST)).as(table).isNotEmpty()
                .containsExactlyElementsOf(shape(table, SECOND));
        }
        softly.assertAll();
    }

    @Test
    void 다시_실행하면_옮길_줄이_없다고_알리고_아무것도_바꾸지_않는다() throws Exception {
        // given
        seed(FIRST);
        move("forecast_record");
        String catalog = catalogFingerprint();
        String rows = placedRows();
        notices.clear();

        // when
        move("forecast_record");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(notices)
            .containsExactly("DEFAULT-ROWS: forecast_record 기본 파티션에 옮길 줄이 없다. 바꾸는 것 없이 끝난다");
        softly.assertThat(catalogFingerprint()).isEqualTo(catalog);
        softly.assertThat(placedRows()).isEqualTo(rows);
        softly.assertAll();
    }

    @Test
    void 날짜를_고르면_그_날짜의_줄만_옮긴다() throws Exception {
        // given
        seed(FIRST);
        seed(SECOND);

        // when
        move("forecast_run", "SET salmonbus.default_rows_date = '2026-10-02'");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(placement("forecast_run"))
            .isEqualTo("forecast_run_default 2026-10-01," + placed("forecast_run", SECOND));
        softly.assertThat(exists("forecast_run_p20261001")).isFalse();
        softly.assertAll();
    }

    @Test
    void 한_날짜에서_실패하면_먼저_옮긴_날짜까지_되돌려_아무것도_바뀌지_않는다() throws Exception {
        // given
        seed(FIRST);
        seed(SECOND);
        execute("CREATE TABLE pass_label_p20261002 (unrelated integer)");
        String catalog = catalogFingerprint();
        String rows = placedRows();

        // when
        Throwable actual = catchThrowable(() -> move("pass_label"));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual).hasMessageContaining("DEFAULT-ROWS: pass_label_p20261002 라는 관계가 이미 있는데");
        softly.assertThat(exists("pass_label_p20261001")).isFalse();
        softly.assertThat(catalogFingerprint()).isEqualTo(catalog);
        softly.assertThat(placedRows()).isEqualTo(rows);
        softly.assertAll();
    }

    @Test
    void 새_파티션에_들어간_값이_옮기기_전과_다르면_스스로_알아채고_되돌린다() throws Exception {
        // given
        seed(FIRST);
        execute("""
            CREATE FUNCTION shift_label_value() RETURNS trigger LANGUAGE plpgsql AS $shift$
            BEGIN
                NEW.label_value := NEW.label_value + 1e-9;
                RETURN NEW;
            END $shift$
            """);
        execute("CREATE TRIGGER shift_label_value BEFORE INSERT ON pass_label FOR EACH ROW"
            + " EXECUTE FUNCTION shift_label_value()");
        String catalog = catalogFingerprint();
        String rows = placedRows();

        // when
        Throwable actual = catchThrowable(() -> move("pass_label"));

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual).hasMessageContaining("DEFAULT-ROWS: pass_label 의 2026-10-01 를 옮긴 결과가 맞지 않아"
            + " 되돌린다(지운 줄 3, 넣은 줄 3, 새 파티션의 줄 3, 기본 파티션에 남은 줄 0, 값까지 같은 줄 0)");
        softly.assertThat(catalogFingerprint()).isEqualTo(catalog);
        softly.assertThat(placedRows()).isEqualTo(rows);
        softly.assertAll();
    }

    @Test
    void 제한_시간을_넘기면_되돌리고_잠금이_풀린_뒤_다시_실행하면_옮긴다() throws Exception {
        // given
        seed(FIRST);
        String rows = placedRows();
        try (Connection reader = connect()) {
            execute(reader, new ArrayList<>(), "BEGIN");
            execute(reader, new ArrayList<>(), "SELECT count(*) FROM forecast_unresolved");

            // when
            Throwable actual = catchThrowable(() ->
                move("forecast_unresolved", "SET salmonbus.default_rows_timeout = '300ms'"));

            // then
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(actual).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).as("statement_timeout").isEqualTo("57014"));
            softly.assertThat(exists("forecast_unresolved_p20261001")).isFalse();
            softly.assertThat(placedRows()).isEqualTo(rows);
            softly.assertAll();

            // when
            execute(reader, new ArrayList<>(), "COMMIT");
            move("forecast_unresolved");

            // then
            assertThat(placement("forecast_unresolved")).isEqualTo(placed("forecast_unresolved", FIRST));
        }
    }

    @Test
    void 다른_트랜잭션이_테이블을_읽고_있으면_줄을_건드리기_전에_부모_잠금부터_기다리다_5초_뒤에_멈춘다() throws Exception {
        // given
        seed(FIRST);
        String rows = placedRows();
        AtomicReference<Throwable> actual = new AtomicReference<>();
        Thread mover = new Thread(() -> actual.set(catchThrowable(() -> move("forecast_run"))));
        try (Connection reader = connect()) {
            execute(reader, new ArrayList<>(), "BEGIN");
            execute(reader, new ArrayList<>(), "SELECT count(*) FROM forecast_run");

            // when
            mover.start();
            final boolean waited = waitsForLock(reader, "forecast_run");
            String waitingFor = scalarOn(reader, "SELECT string_agg(mode, ',') FROM pg_locks"
                + " WHERE NOT granted AND relation = 'forecast_run'::regclass");
            String writeLocksOnDefault = scalarOn(reader, "SELECT count(*) FROM pg_locks WHERE granted"
                + " AND relation = 'forecast_run_default'::regclass AND mode <> 'AccessShareLock'");
            mover.join();

            // then
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(waited).as("옮기는 쪽이 부모 테이블의 잠금을 기다렸다").isTrue();
            softly.assertThat(waitingFor).isEqualTo("AccessExclusiveLock");
            softly.assertThat(writeLocksOnDefault).as("기다리는 동안 기본 파티션에 잡은 쓰기 잠금").isEqualTo("0");
            softly.assertThat(actual.get()).isInstanceOfSatisfying(SQLException.class,
                failure -> assertThat(failure.getSQLState()).as("lock_timeout").isEqualTo("55P03"));
            softly.assertThat(scalarOn(reader, "SELECT to_regclass('public.forecast_run_p20261001')::text")).isNull();
            softly.assertAll();
        }
        assertThat(placedRows()).isEqualTo(rows);
    }

    @Test
    void 옮기는_동안_기다린_쓰기는_커밋_뒤_새_날짜_파티션으로_들어간다() throws Exception {
        // given
        seed(FIRST);
        List<String> script = script();
        assertThat(script.getLast()).as(FILE + "의 마지막 문장").isEqualToIgnoringCase("COMMIT");
        execute("SET salmonbus.default_rows_table = 'forecast_unresolved'");
        for (String statement : script.subList(0, script.size() - 1)) {
            execute(statement);
        }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try (Connection other = connect()) {
                execute(other, new ArrayList<>(), "INSERT INTO forecast_unresolved"
                    + " VALUES (DATE '2026-10-01', 99, 5, 7, 'LOST', 'PASS_NOT_SEEN', now())");
            } catch (SQLException thrown) {
                failure.set(thrown);
            }
        });

        // when
        writer.start();
        final boolean waited = waitsForLock(connection, "forecast_unresolved");
        execute("COMMIT");
        writer.join();

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(waited).as("쓰기가 부모 테이블의 잠금을 기다렸다").isTrue();
        softly.assertThat(failure.get()).as("기다린 쓰기의 오류").isNull();
        softly.assertThat(scalar("SELECT tableoid::regclass::text FROM forecast_unresolved"
            + " WHERE vehicle_observation_id = 99")).isEqualTo("forecast_unresolved_p20261001");
        softly.assertThat(number("SELECT count(*) FROM forecast_unresolved_default")).isZero();
        softly.assertAll();
    }

    @Test
    void 내보내기_장부에_이미_있는_날짜의_줄은_옮기지_않고_경고를_남기며_다른_날짜는_옮긴다() throws Exception {
        // given
        seed(FIRST);
        seed(SECOND);
        execute("INSERT INTO daily_export(table_name, source_date, object_key, row_count, sha256, exported_at,"
            + " verified_at, dropped_at) VALUES ('forecast_run', DATE '2026-10-01', 'k', 5, repeat('a', 64), now(),"
            + " now(), now())");

        // when
        move("forecast_run");

        // then
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(notices).anyMatch(notice -> notice.startsWith(
            "DEFAULT-ROWS: forecast_run 의 2026-10-01 는 내보내기 장부에 이미 있다"));
        softly.assertThat(notices.getLast())
            .isEqualTo("DEFAULT-ROWS: forecast_run 날짜 1 개를 옮기고 1 개를 건너뛰었다. 기본 파티션에 남은 줄 3 개");
        softly.assertThat(placement("forecast_run"))
            .isEqualTo("forecast_run_default 2026-10-01," + placed("forecast_run", SECOND));
        softly.assertThat(exists("forecast_run_p20261001")).isFalse();
        softly.assertAll();
    }

    @Test
    void 목록에_없는_테이블이거나_테이블_소유자가_아니면_멈춘다() throws Exception {
        // given
        seed(FIRST);
        String rows = placedRows();
        String role = "default_rows_" + UUID.randomUUID().toString().replace("-", "");
        execute("CREATE ROLE " + role);
        try {
            // when
            Throwable unknown = catchThrowable(() -> move("route"));
            execute("SET ROLE " + role);
            Throwable notOwner = catchThrowable(() -> move("forecast_run"));
            execute("RESET ROLE");

            // then
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(unknown).hasMessageContaining("DEFAULT-ROWS: route 는 날짜 파티션을 두는 테이블이 아니다");
            softly.assertThat(notOwner).hasMessageContaining("DEFAULT-ROWS: forecast_run 의 소유자로 실행해야 한다");
            softly.assertThat(placedRows()).isEqualTo(rows);
            softly.assertAll();
        } finally {
            execute("RESET ROLE");
            execute("DROP ROLE " + role);
        }
    }

    @Test
    void 관측_테이블이_아직_일반_테이블이면_알리고_아무것도_바꾸지_않는다() throws Exception {
        // given
        try (TestDatabase plain = TestDatabase.create()) {
            flyway(plain).target(BEFORE_PARTITIONING).load().migrate();
            try (Connection target = plain.connection()) {
                List<String> told = new ArrayList<>();
                execute(target, told, ROUTE);
                execute(target, told, """
                    INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
                        normalization_version,collection_strategy_version)
                    VALUES (1, now(), 1, 'k1', 'RESERVED', 'n1', 'c1')
                    """);
                flyway(plain).load().migrate();

                // when
                run(target, told);
                run(target, told, "SET salmonbus.default_rows_table = 'observation_batch'");

                // then
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(told).contains(
                    "DEFAULT-ROWS: observation_batch 는 기본 파티션을 둔 파티션 테이블이 아니다. 대상이 아니다",
                    "DEFAULT-ROWS: observation_batch 는 파티션 테이블이 아니다. 바꾸는 것 없이 끝난다");
                softly.assertThat(scalarOn(target, "SELECT relkind::text FROM pg_class"
                    + " WHERE oid = 'observation_batch'::regclass")).isEqualTo("r");
                softly.assertThat(scalarOn(target, "SELECT count(*) FROM observation_batch")).isEqualTo("1");
                softly.assertAll();
            }
        }
    }

    private void seed(LocalDate date) throws SQLException {
        execute(ROWS.formatted(date));
    }

    private void createPartition(String table, LocalDate date) throws SQLException {
        execute("CREATE TABLE " + partitionName(table, date) + " PARTITION OF " + table + " FOR VALUES FROM ('" + date
            + "') TO ('" + date.plusDays(1) + "')");
    }

    /** worker의 파티션 유지 작업이 날짜 파티션을 만들 때 쓰는 문장이다. */
    private static String maintenanceStatement(String table, LocalDate date) {
        return "CREATE TABLE IF NOT EXISTS " + partitionName(table, date) + " PARTITION OF " + table
            + " FOR VALUES FROM ('" + date + "') TO ('" + date.plusDays(1) + "')";
    }

    private static String partitionName(String table, LocalDate date) {
        return table + "_p" + date.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    /** 테이블을 고르지 않고 실행한다. */
    private void summarize() throws IOException, SQLException {
        run(connection, notices, "RESET salmonbus.default_rows_table");
    }

    /**
     * 한 테이블의 기본 파티션 줄을 옮긴다. 앞선 실행이 세션에 남긴 날짜와 제한 시간은 지우고 시작한다.
     *
     * @param settings 테이블 이름 말고 더 넣을 설정 문장
     */
    private void move(String table, String... settings) throws IOException, SQLException {
        List<String> all = new ArrayList<>(List.of("RESET salmonbus.default_rows_date",
            "RESET salmonbus.default_rows_timeout", "SET salmonbus.default_rows_table = '" + table + "'"));
        all.addAll(List.of(settings));
        run(connection, notices, all.toArray(String[]::new));
    }

    /** 설정 문장을 실행한 뒤 SQL 파일을 psql처럼 문장 단위로 실행한다. 실패하면 그 연결의 트랜잭션을 되돌린다. */
    private static void run(Connection target, List<String> notices, String... settings)
        throws IOException, SQLException {
        for (String setting : settings) {
            execute(target, notices, setting);
        }
        try {
            for (String statement : script()) {
                execute(target, notices, statement);
            }
        } catch (SQLException failure) {
            execute(target, notices, "ROLLBACK");
            throw failure;
        }
    }

    private static List<String> script() throws IOException {
        return TransitionSqlTest.statements(
            Files.readString(TransitionSqlTest.backend().resolve("deploy/sql").resolve(FILE)));
    }

    /**
     * 어느 연결이 그 테이블의 잠금을 기다리기 시작할 때까지 길어야 4초 본다. 옮기는 SQL의 잠금 대기 시간(5초)보다 짧다.
     *
     * @param observer 잠금 목록을 읽을 연결
     */
    private static boolean waitsForLock(Connection observer, String table) throws SQLException, InterruptedException {
        for (int attempt = 0; attempt < 80; attempt++) {
            if (!"0".equals(scalarOn(observer, "SELECT count(*) FROM pg_locks WHERE NOT granted AND relation = '"
                + table + "'::regclass"))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, template.username(), template.password());
    }

    /** 테이블의 줄이 든 파티션과 그 줄의 날짜를 겹치지 않게 날짜 순서로 잇는다. */
    private String placement(String table) throws SQLException {
        return scalar("SELECT string_agg(placed, ',' ORDER BY placed) FROM (SELECT DISTINCT"
            + " tableoid::regclass::text || ' ' || source_date AS placed FROM " + table + ") placed_rows");
    }

    private static String placed(String table, LocalDate date) {
        return partitionName(table, date) + " " + date;
    }

    private String bound(String table, LocalDate date) throws SQLException {
        return scalar("SELECT pg_get_expr(relpartbound, oid) FROM pg_class WHERE oid = '" + partitionName(table, date)
            + "'::regclass");
    }

    /** 한쪽에만 있는 줄의 수다. 같은 줄이 여러 번 있으면 그 횟수까지 견준다. */
    private long differingRows(String expected, String actual) throws SQLException {
        return number("SELECT count(*) FROM ((SELECT * FROM " + expected + " EXCEPT ALL SELECT * FROM " + actual
            + ") UNION ALL (SELECT * FROM " + actual + " EXCEPT ALL SELECT * FROM " + expected + ")) different");
    }

    private String text(String table) throws SQLException {
        return scalar("SELECT string_agg(t::text, E'\\n' ORDER BY t::text) FROM " + table + " t");
    }

    /** 일곱 테이블의 모든 줄을 그 줄이 든 파티션 이름과 함께 한 글로 낸다. */
    private String placedRows() throws SQLException {
        StringBuilder rows = new StringBuilder();
        for (String table : TABLES) {
            rows.append(table).append('=').append(scalar("SELECT md5(coalesce(string_agg(tableoid::regclass::text"
                + " || t::text, ',' ORDER BY t::text), '')) FROM " + table + " t")).append(';');
        }
        return rows.toString();
    }

    /** 날짜 파티션의 열, 인덱스, 제약, 경계, 소유자다. 이름과 경계에 든 날짜는 그 파티션의 날짜에서 며칠 뒤인지로 바꿔 쓴다. */
    private List<String> shape(String table, LocalDate date) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(PARTITION_SHAPE.formatted(partitionName(table, date)))) {
            while (rows.next()) {
                lines.add(rows.getString(1)
                    .replace(date.plusDays(1).toString(), "<day+1>")
                    .replace(date.toString(), "<day>")
                    .replace(date.format(DateTimeFormatter.BASIC_ISO_DATE), "<day>"));
            }
        }
        Collections.sort(lines);
        return lines;
    }

    private String catalogFingerprint() throws SQLException {
        return scalar("SELECT md5(string_agg(c.oid::text || c.relname || c.relkind::text, ',' ORDER BY c.oid))"
            + " FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace");
    }

    private boolean exists(String relation) throws SQLException {
        return scalar("SELECT to_regclass('public." + relation + "')::text") != null;
    }

    private void execute(String sql) throws SQLException {
        execute(connection, notices, sql);
    }

    private static void execute(Connection target, List<String> notices, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(warning.getMessage());
            }
        }
    }

    private long number(String sql) throws SQLException {
        return Long.parseLong(scalar(sql));
    }

    private String scalar(String sql) throws SQLException {
        return scalarOn(connection, sql);
    }

    private static String scalarOn(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    private static synchronized TestDatabase template() throws SQLException {
        if (template != null) {
            return template;
        }
        TestDatabase database = TestDatabase.create();
        flyway(database).load().migrate();
        try (Connection setup = database.connection()) {
            execute(setup, new ArrayList<>(), ROUTE);
        }
        template = database;
        return template;
    }

    private static FluentConfiguration flyway(TestDatabase database) {
        return Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password());
    }

    private static void admin(TestDatabase database, String sql) throws SQLException {
        String maintenance = database.jdbcUrl().replace(databaseName(database), "postgres");
        try (Connection admin = DriverManager.getConnection(maintenance, database.username(), database.password());
            Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String databaseName(TestDatabase database) {
        String jdbcUrl = database.jdbcUrl();
        return jdbcUrl.substring(jdbcUrl.lastIndexOf('/') + 1);
    }
}
