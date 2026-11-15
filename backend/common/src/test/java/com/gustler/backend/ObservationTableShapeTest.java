package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.support.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/**
 * 관측 두 테이블({@code observation_batch}, {@code vehicle_observation})의 모양이 DB를 만든 길과 상관없이 같은지 확인한다.
 *
 * <p>줄이 없는 DB는 마이그레이션이 두 테이블을 날짜 파티션 테이블로 다시 만든다. 줄이 있는 DB는 마이그레이션이 손대지 않고, 수동 전환
 * SQL({@code deploy/sql/sal434-04a·04})이 바꾼 뒤 이관 앱이 바꾸기 전 줄을 담은 첫 파티션을 내보내 지운다. 두 길의 끝을 카탈로그에서
 * 읽어 견준다.
 */
class ObservationTableShapeTest {
    /** 쓰지 않는 객체를 지우는 마이그레이션까지 적용한, 관측 테이블을 다시 만들기 바로 앞 버전이다. */
    private static final String BEFORE_PARTITIONING = "83";
    private static final List<String> TABLES = List.of("observation_batch", "vehicle_observation");
    private static final String ROUTE = """
        INSERT INTO route(id,public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            OVERRIDING SYSTEM VALUE VALUES (1,'p1','s1','sr1','1','a','b');
        INSERT INTO route_version(id,route_id,content_digest,valid_from) OVERRIDING SYSTEM VALUE
            VALUES (1,1,repeat('0',64),'2026-01-01T00:00:00Z');
        INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
            SELECT 1,n,'st'||n,'s'||n,'UP',true FROM generate_series(1,3) n
        """;
    private static final String BATCHES = """
        INSERT INTO observation_batch(route_version_id,scheduled_at,attempt_number,attempt_key,outcome,
            normalization_version,collection_strategy_version,ingestion_origin,response_received_at,stored_rows)
        SELECT 1,timestamptz '2026-11-01T00:00:00Z'+n*interval '20 seconds',1,'k'||n,'SUCCESS_ROWS','n1','c1',
            'LIVE',timestamptz '2026-11-01T00:00:01Z'+n*interval '20 seconds',2
        FROM generate_series(1,3) n
        """;
    private static final String OBSERVATIONS = """
        INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
            stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
        SELECT b.id,1,r,'v'||r,1+r,'st'||(1+r),1,10,0 FROM observation_batch b CROSS JOIN generate_series(0,1) r
        """;
    /** 두 테이블과 그 파티션이다. 뒤에 붙는 조회가 {@code rel}로 읽는다. */
    private static final String RELATIONS = """
        WITH RECURSIVE tree AS (
            SELECT c.oid FROM pg_class c
            WHERE c.relnamespace = current_schema()::regnamespace
                AND c.relname IN ('observation_batch', 'vehicle_observation')
            UNION ALL
            SELECT i.inhrelid FROM pg_inherits i JOIN tree ON tree.oid = i.inhparent
        ), rel AS (SELECT c.* FROM tree JOIN pg_class c ON c.oid = tree.oid)
        """;
    /** 견주는 항목과 그 조회다. 조회마다 한 줄이 객체 하나이고, OID와 저장 파일 번호처럼 DB마다 다른 값은 넣지 않는다. */
    private static final Map<String, String> CATALOG = catalogQueries();

    @Test
    void 줄이_없어_마이그레이션이_다시_만든_두_테이블은_전환_SQL로_바꾸고_첫_파티션을_지운_두_테이블과_카탈로그가_같다()
        throws Exception {
        // given
        try (TestDatabase migrated = TestDatabase.create(); TestDatabase converted = TestDatabase.create();
            Connection actual = migrated.connection(); Connection expected = converted.connection()) {
            LocalDate beforeMigration = TransitionSqlTest.koreanDate(actual);

            // when
            migrate(migrated, null);
            LocalDate afterMigration = TransitionSqlTest.koreanDate(actual);
            migrate(converted, BEFORE_PARTITIONING);
            execute(expected, ROUTE + ";" + BATCHES + ";" + OBSERVATIONS);
            migrate(converted, null);
            LocalDate convertedOn = convertWithManualSql(expected);
            dropInitialPartitions(expected);

            // then
            LocalDate migratedOn = TransitionSqlTest.firstPartitionDate(actual);
            Map<String, List<String>> expectedCatalog = relativeCatalog(expected, convertedOn);
            Map<String, List<String>> actualCatalog = relativeCatalog(actual, migratedOn);
            SoftAssertions softly = new SoftAssertions();
            for (String item : CATALOG.keySet()) {
                softly.assertThat(actualCatalog.get(item)).as(item).isNotEmpty()
                    .containsExactlyElementsOf(expectedCatalog.get(item));
            }
            softly.assertThat(migratedOn).as("마이그레이션이 만든 첫 날짜 파티션").isBetween(beforeMigration, afterMigration);
            softly.assertThat(scalar(actual, incomingForeignKeys())).as("두 테이블을 가리키는 FK").isNull();
            softly.assertThat(scalar(expected, incomingForeignKeys())).as("두 테이블을 가리키는 FK").isNull();
            softly.assertAll();
        }
    }

    @Test
    void 다시_만든_두_테이블에_넣은_줄은_그날_파티션으로_가고_두_테이블_사이_FK_없이_날짜_안에서만_유일하다() throws Exception {
        // given
        try (TestDatabase migrated = TestDatabase.create()) {
            migrate(migrated, null);
            try (Connection target = migrated.connection()) {
                // when
                execute(target, "BEGIN");
                LocalDate insertedOn = TransitionSqlTest.koreanDate(target);
                execute(target, ROUTE + ";" + BATCHES + ";" + OBSERVATIONS);
                execute(target, """
                    INSERT INTO vehicle_observation(observation_batch_id,route_version_id,source_row_number,vehicle_id,
                        stop_order,stop_id,running_state,remaining_seats,passed_stop_order)
                    VALUES (999,1,0,'v0',1,'st1',1,10,0);
                    INSERT INTO observation_batch(source_date,route_version_id,scheduled_at,attempt_number,attempt_key,
                        outcome,normalization_version,collection_strategy_version)
                    VALUES ((now() AT TIME ZONE 'Asia/Seoul')::date + 1,1,now(),1,'k1','RESERVED','n1','c1')
                    """);
                execute(target, "COMMIT");

                // then
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(scalar(target, partitionAndDateOfRows("observation_batch")))
                    .isEqualTo(partitionAndDate("observation_batch", insertedOn) + ","
                        + partitionAndDate("observation_batch", insertedOn.plusDays(1)));
                softly.assertThat(scalar(target, partitionAndDateOfRows("vehicle_observation")))
                    .isEqualTo(partitionAndDate("vehicle_observation", insertedOn));
                softly.assertThat(scalar(target, "SELECT count(*) FROM vehicle_observation"
                    + " WHERE observation_batch_id = 999")).isEqualTo("1");
                softly.assertThat(scalar(target, "SELECT count(*) FROM observation_batch WHERE attempt_key = 'k1'"))
                    .isEqualTo("2");
                softly.assertThat(scalar(target, "SELECT count(*) FROM operational_observation")).isEqualTo("7");
                softly.assertAll();
            }
        }
    }

    @Test
    void 관측_배치에_줄이_하나라도_있으면_마이그레이션은_두_테이블을_그대로_둔다() throws Exception {
        // given
        try (TestDatabase database = TestDatabase.create()) {
            migrate(database, BEFORE_PARTITIONING);
            try (Connection target = database.connection()) {
                execute(target, ROUTE + ";" + BATCHES);
                String storage = scalar(target, storage());
                Map<String, List<String>> before = catalog(target);

                // when
                migrate(database, null);

                // then
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(scalar(target, storage())).isEqualTo(storage)
                    .startsWith("observation_batch:r:").contains(",vehicle_observation:r:");
                softly.assertThat(catalog(target)).isEqualTo(before);
                softly.assertThat(scalar(target, "SELECT count(*) FROM observation_batch")).isEqualTo("3");
                softly.assertAll();
            }
        }
    }

    @Test
    void 전환_SQL로_이미_바꾼_DB는_마이그레이션이_그대로_두고_첫_파티션의_줄도_남는다() throws Exception {
        // given
        try (TestDatabase database = TestDatabase.create()) {
            migrate(database, BEFORE_PARTITIONING);
            try (Connection target = database.connection()) {
                execute(target, ROUTE + ";" + BATCHES + ";" + OBSERVATIONS);
                convertWithManualSql(target);
                String storage = scalar(target, storage());
                Map<String, List<String>> before = catalog(target);

                // when
                migrate(database, null);

                // then
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(scalar(target, storage())).isEqualTo(storage).startsWith("observation_batch:p:");
                softly.assertThat(catalog(target)).isEqualTo(before);
                softly.assertThat(scalar(target, "SELECT count(*) FROM vehicle_observation_initial")).isEqualTo("6");
                softly.assertAll();
            }
        }
    }

    @Test
    void 마이그레이션이_다시_만든_DB에서_파티션_전환_SQL을_실행하면_알리기만_하고_아무것도_바꾸지_않는다() throws Exception {
        // given
        try (TestDatabase migrated = TestDatabase.create()) {
            migrate(migrated, null);
            try (Connection target = migrated.connection()) {
                Map<String, List<String>> before = catalog(target);

                // when
                List<String> notices = new ArrayList<>();
                partitionWithManualSql(target, notices);

                // then
                SoftAssertions softly = new SoftAssertions();
                softly.assertThat(notices).anySatisfy(notice -> assertThat(notice).contains("이미 파티션 테이블이다"));
                softly.assertThat(catalog(target)).isEqualTo(before);
                softly.assertAll();
            }
        }
    }

    private static void migrate(TestDatabase database, String target) {
        Flyway.configure().dataSource(database.jdbcUrl(), database.username(), database.password())
            .target(targetOrLatest(target)).load().migrate();
    }

    private static String targetOrLatest(String target) {
        if (target == null) {
            return "latest";
        }
        return target;
    }

    /** 준비 SQL과 파티션 전환 SQL을 실행하고, 전환 SQL이 날짜 파티션을 만들 때 기준으로 삼은 한국 날짜를 돌려준다. */
    private static LocalDate convertWithManualSql(Connection target) throws IOException, SQLException {
        run(target, "sal434-04a-prepare-observation-partition.sql");
        return partitionWithManualSql(target, new ArrayList<>());
    }

    private static LocalDate partitionWithManualSql(Connection target, List<String> notices)
        throws IOException, SQLException {
        execute(target, "SET sal434.count_rows = 'exact'");
        return TransitionSqlTest.runConfirmed(target, notices,
            "sal434-04-partition-observation-tables.sql", "sal434.confirm_partition");
    }

    /** 이관 앱이 첫 파티션을 내보내 확인한 뒤 하는 것처럼 떼어 지운다. */
    private static void dropInitialPartitions(Connection target) throws SQLException {
        for (String table : TABLES) {
            execute(target, "ALTER TABLE " + table + " DETACH PARTITION " + table + "_initial");
            execute(target, "DROP TABLE " + table + "_initial");
        }
    }

    /** SQL 파일을 psql처럼 한 연결에서 문장 단위로 실행한다. */
    private static void run(Connection target, String file) throws IOException, SQLException {
        String script =
            Files.readString(TransitionSqlTest.backend().resolve("deploy/sql").resolve(file));
        for (String statement : TransitionSqlTest.statements(script)) {
            execute(target, statement);
        }
    }

    private static void execute(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Map<String, List<String>> catalog(Connection target) throws SQLException {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        for (Map.Entry<String, String> query : CATALOG.entrySet()) {
            catalog.put(query.getKey(), lines(target, query.getValue()));
        }
        return catalog;
    }

    private static List<String> lines(Connection target, String sql) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                lines.add(rows.getString(1));
            }
        }
        return lines;
    }

    private static String scalar(Connection target, String sql) throws SQLException {
        try (Statement statement = target.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        }
    }

    /**
     * 날짜 파티션의 날짜를 첫 날짜 파티션에서 며칠 뒤인지로 바꿔 쓴 카탈로그다.
     *
     * @param first 그 DB의 첫 날짜 파티션의 날짜
     */
    private static Map<String, List<String>> relativeCatalog(Connection target, LocalDate first)
        throws SQLException {
        Map<String, List<String>> relative = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> item : catalog(target).entrySet()) {
            relative.put(item.getKey(), item.getValue().stream()
                .map(line -> TransitionSqlTest.relativeToFirstPartition(line, first))
                .toList());
        }
        return relative;
    }

    /** 테이블의 줄이 든 파티션과 그 줄의 source_date를 겹치지 않게 날짜 순서로 잇는 조회다. */
    private static String partitionAndDateOfRows(String table) {
        return "SELECT string_agg(placed, ',' ORDER BY placed) FROM (SELECT DISTINCT"
            + " tableoid::regclass::text || ' ' || source_date AS placed FROM " + table + ") placed_rows";
    }

    private static String partitionAndDate(String table, LocalDate date) {
        return table + "_p" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + " " + date;
    }

    /** 두 테이블의 종류와 저장 파일 번호다. 테이블을 다시 만들거나 다시 쓰면 달라진다. */
    private static String storage() {
        return "SELECT string_agg(relname || ':' || relkind::text || ':' || relfilenode, ',' ORDER BY relname)"
            + " FROM pg_class WHERE relnamespace = current_schema()::regnamespace"
            + " AND relname IN ('observation_batch', 'vehicle_observation')";
    }

    private static String incomingForeignKeys() {
        return RELATIONS + "SELECT string_agg(k.conrelid::regclass || '.' || k.conname, ',')"
            + " FROM pg_constraint k JOIN rel c ON c.oid = k.confrelid WHERE k.contype = 'f'";
    }

    /**
     * 견주는 항목이다. IDENTITY 시퀀스의 시작 값만은 넣지 않는다. 전환 SQL은 있던 줄의 다음 번호부터 잇고 다시 만든 테이블은 1부터
     * 시작하기 때문이다.
     */
    private static Map<String, String> catalogQueries() {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("스키마의 모든 관계 이름", """
            SELECT c.relname || ' ' || c.relkind::text FROM pg_class c
            WHERE c.relnamespace = current_schema()::regnamespace ORDER BY 1
            """);
        queries.put("테이블과 파티션 경계", RELATIONS + """
            SELECT concat_ws(' | ', c.relname, c.relkind, c.relpersistence, c.relispartition,
                coalesce(pg_get_partkeydef(c.oid), '-'), coalesce(pg_get_expr(c.relpartbound, c.oid), '-'),
                coalesce((SELECT p.relname FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent
                    WHERE i.inhrelid = c.oid), '-'),
                coalesce(c.reloptions::text, '-'), c.relrowsecurity, c.relforcerowsecurity, c.relreplident,
                coalesce(obj_description(c.oid, 'pg_class'), '-'), pg_get_userbyid(c.relowner),
                coalesce(c.relacl::text, '-'))
            FROM rel c ORDER BY c.relname
            """);
        queries.put("열", RELATIONS + """
            SELECT concat_ws(' | ', c.relname, row_number() OVER (PARTITION BY c.oid ORDER BY a.attnum), a.attname,
                format_type(a.atttypid, a.atttypmod), a.attnotnull, coalesce(pg_get_expr(d.adbin, d.adrelid), '-'),
                'identity=' || a.attidentity::text, 'generated=' || a.attgenerated::text, a.attstorage,
                'compression=' || a.attcompression::text, a.attcollation::regcollation,
                coalesce(a.attstattarget::text, '-'), coalesce(a.attoptions::text, '-'),
                coalesce(col_description(c.oid, a.attnum), '-'), a.attislocal, a.attinhcount)
            FROM rel c
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            ORDER BY c.relname, a.attnum
            """);
        queries.put("제약", RELATIONS + """
            SELECT concat_ws(' | ', c.relname, k.conname, k.contype, pg_get_constraintdef(k.oid), k.convalidated,
                k.conislocal, k.coninhcount, k.connoinherit, k.condeferrable, k.condeferred,
                coalesce((SELECT p.conname FROM pg_constraint p WHERE p.oid = k.conparentid), '-'),
                coalesce(obj_description(k.oid, 'pg_constraint'), '-'))
            FROM rel c JOIN pg_constraint k ON k.conrelid = c.oid ORDER BY c.relname, k.conname
            """);
        queries.put("인덱스", RELATIONS + """
            SELECT concat_ws(' | ', c.relname, x.relname, pg_get_indexdef(i.indexrelid), i.indisunique, i.indisprimary,
                i.indisvalid, i.indisready, i.indnullsnotdistinct, i.indisreplident, i.indisclustered,
                coalesce((SELECT p.relname FROM pg_inherits h JOIN pg_class p ON p.oid = h.inhparent
                    WHERE h.inhrelid = i.indexrelid), '-'),
                coalesce(obj_description(i.indexrelid, 'pg_class'), '-'))
            FROM rel c JOIN pg_index i ON i.indrelid = c.oid JOIN pg_class x ON x.oid = i.indexrelid
            ORDER BY c.relname, x.relname
            """);
        queries.put("IDENTITY 시퀀스", RELATIONS + """
            SELECT concat_ws(' | ', c.relname, a.attname, s.relname, format_type(q.seqtypid, NULL), q.seqincrement,
                q.seqmin, q.seqmax, q.seqcache, q.seqcycle)
            FROM rel c
            JOIN pg_attribute a ON a.attrelid = c.oid AND a.attidentity <> ''
            JOIN pg_depend d ON d.refobjid = c.oid AND d.refobjsubid = a.attnum
                AND d.classid = 'pg_class'::regclass AND d.deptype = 'i'
            JOIN pg_class s ON s.oid = d.objid AND s.relkind = 'S'
            JOIN pg_sequence q ON q.seqrelid = s.oid
            ORDER BY c.relname, a.attname
            """);
        queries.put("두 테이블을 읽는 뷰", RELATIONS + """
            SELECT concat_ws(' | ', v.relname, pg_get_viewdef(v.oid),
                (SELECT string_agg(DISTINCT source.name, ',' ORDER BY source.name)
                    FROM pg_depend d
                    JOIN pg_rewrite w ON w.oid = d.objid
                    JOIN pg_class t ON t.oid = d.refobjid
                    CROSS JOIN LATERAL (SELECT t.relname || ':' || t.relkind::text AS name) source
                    WHERE w.ev_class = v.oid AND d.refclassid = 'pg_class'::regclass AND t.oid <> v.oid),
                coalesce(v.reloptions::text, '-'), coalesce(v.relacl::text, '-'))
            FROM pg_class v
            WHERE v.relkind IN ('v', 'm')
                AND EXISTS (SELECT 1 FROM pg_depend d
                    JOIN pg_rewrite w ON w.oid = d.objid
                    JOIN rel c ON c.oid = d.refobjid
                    WHERE w.ev_class = v.oid AND d.classid = 'pg_rewrite'::regclass
                        AND d.refclassid = 'pg_class'::regclass)
            ORDER BY v.relname
            """);
        queries.put("트리거와 규칙과 행 보안 정책과 확장 통계", RELATIONS + """
            SELECT 'trigger ' || c.relname || '.' || t.tgname FROM pg_trigger t JOIN rel c ON c.oid = t.tgrelid
            WHERE NOT t.tgisinternal
            UNION ALL
            SELECT 'rule ' || c.relname || '.' || r.rulename FROM pg_rewrite r JOIN rel c ON c.oid = r.ev_class
            UNION ALL
            SELECT 'policy ' || c.relname || '.' || p.polname FROM pg_policy p JOIN rel c ON c.oid = p.polrelid
            UNION ALL
            SELECT 'statistics ' || c.relname || '.' || s.stxname
            FROM pg_statistic_ext s JOIN rel c ON c.oid = s.stxrelid
            UNION ALL
            SELECT 'none'
            ORDER BY 1
            """);
        return queries;
    }
}
