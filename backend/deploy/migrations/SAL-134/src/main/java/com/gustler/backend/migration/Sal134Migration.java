package com.gustler.backend.migration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.flywaydb.core.Flyway;

/** SAL-134 정비 시간에만 사용하는 전환 실행기. 서비스 JAR에는 포함하지 않는다. */
public final class Sal134Migration {
    private static final long LOCK_ID = 134_2026_09L;
    private static final String EMPTY_CURSOR = "{}";
    private static final Set<String> COMMANDS = Set.of("inspect", "prepare", "backfill", "verify", "finalize");
    private static final List<Source> SOURCES = List.of(
            new Source("daily_call_quota", "to_jsonb(t)", "provider, api_service, kst_date"),
            new Source("route", "to_jsonb(t)", "id"),
            new Source("route_version", "to_jsonb(t)", "id"),
            new Source("route_stop", "to_jsonb(t)", "route_version_id, stop_order"),
            new Source("observation_batch", "to_jsonb(t) - 'input_confirmed_at'", "id"),
            new Source("vehicle_observation", "to_jsonb(t)", "id"),
            new Source("seat_forecast", "to_jsonb(t) - 'publication_id'", "vehicle_observation_id, target_stop_order"),
            new Source("stop_demand_statistics", "to_jsonb(t)",
                    "route_version_id, stop_order, time_slot, calculation_version, revision"),
            new Source("same_day_full_outcomes", "to_jsonb(t)", "route_id, outcome_date, stops_to_target"),
            new Source("model_deployment", "to_jsonb(t)", "id"),
            new Source("vehicle_one_way_trip", "to_jsonb(t)", "id"),
            new Source("trip_quality_rebuild", "to_jsonb(t)", "route_version_id, vehicle_id"),
            new Source("historical_import_batch", "to_jsonb(t)", "id"),
            new Source("historical_import_dataset_seal", "to_jsonb(t)", "terminal_manifest_sha256"),
            new Source("historical_import_route_boundary", "to_jsonb(t)", "import_batch_id, model_route"),
            new Source("historical_import_route_binding", "to_jsonb(t)", "import_batch_id, model_route"),
            new Source("historical_import_shard", "to_jsonb(t)", "import_batch_id, shard_sha256"),
            new Source("historical_import_record", "to_jsonb(t)", "import_batch_id, semantic_batch_digest"),
            new Source("migration_source_record", "to_jsonb(t)", "source_account, source_record_id"),
            new Source("forecast_cutover_control", "to_jsonb(t)", "singleton"),
            new Source("temporary_statistics_generation_freeze", "to_jsonb(t)", "id"),
            new Source("training_model_release_exclusion", "to_jsonb(t)", "release_id, bundle_digest"),
            new Source("training_statistics_generation_exclusion", "to_jsonb(t)",
                    "freeze_id, route_version_id, calculation_version, revision, data_until, computed_at"),
            new Source("stop_demand_seed_import", "to_jsonb(t)", "id"),
            new Source("stop_demand_seed_hourly_total", "to_jsonb(t)",
                    "seed_import_id, route_version_id, stop_order, arrival_hour_start"),
            new Source("stop_demand_seed_generation", "to_jsonb(t)", "seed_import_id, route_version_id"),
            new Source("active_stop_demand_seed_hourly_total", "to_jsonb(t)",
                    "route_version_id, stop_order, arrival_hour_start, calculation_version"),
            new Source("forecast_observation_quality", "to_jsonb(t)", "id"),
            new Source("forecast_eligible_observation", "to_jsonb(t)", "id"),
            new Source("quality_eligible_seat_forecast", "to_jsonb(t) - 'publication_id'",
                    "vehicle_observation_id, target_stop_order"),
            new Source("quality_calibration_seat_forecast", "to_jsonb(t) - 'publication_id'",
                    "vehicle_observation_id, target_stop_order"),
            new Source("quality_training_seat_forecast", "to_jsonb(t) - 'publication_id'",
                    "vehicle_observation_id, target_stop_order"),
            new Source("training_eligible_seat_forecast", "to_jsonb(t) - 'publication_id'",
                    "vehicle_observation_id, target_stop_order"),
            new Source("training_eligible_stop_demand_statistics", "to_jsonb(t)",
                    "route_version_id, stop_order, time_slot, calculation_version, revision"));
    private static final List<String> STEPS = List.of(
            "route-quality", "quality-policy", "trip-assignment", "publication", "forecast-publication-link",
            "input-confirmation", "evaluation",
            "statistics-version", "model-exclusion", "statistics-exclusion", "active-model");

    private Sal134Migration() {
    }

    public static void main(final String[] args) {
        try {
            final Options options = Options.parse(args);
            final Settings settings = Settings.read(options.config());
            try {
                run(options, settings);
            } catch (final Exception exception) {
                throw new IllegalStateException(settings.redact(exception.getMessage()), exception);
            }
        } catch (final Exception exception) {
            System.err.println("SAL-134 전환 실패: " + exception.getMessage());
            System.exit(1);
        }
    }

    static void run(final Options options, final Settings settings) throws Exception {
        try (Connection connection = settings.connect()) {
            configure(connection);
            acquireLock(connection);
            if ("inspect".equals(options.command())) {
                inspect(connection);
                return;
            }
            options.requireWriteAttestation();
            if ("prepare".equals(options.command())) {
                requireVersion(connection, 16, 17);
                migrate(settings, "17");
                prepare(connection, options.backupId());
                return;
            }
            requireVersion(connection, 17, "finalize".equals(options.command()) ? 18 : 17);
            requireBackup(connection, options.backupId());
            if (schemaVersion(connection) == 18) {
                verifyTrainingViews(connection);
                System.out.println("FINALIZED: 이미 적용한 V18의 품질·학습 조회 대조 완료.");
                return;
            }
            switch (options.command()) {
                case "backfill" -> backfill(connection, options.chunkSize(), options.maxChunks());
                case "verify" -> verify(connection);
                case "finalize" -> {
                    // 검증 이후 원본이 바뀌었거나 전환 자료가 손상된 경우 최종 DDL을 실행하지 않는다.
                    verify(connection);
                    migrate(settings, "18");
                    verifyTrainingViews(connection);
                    System.out.println("FINALIZED: V18 적용과 학습 조회 대조 완료. 앱 재기동 전 운영 확인이 필요합니다.");
                }
                default -> throw new IllegalArgumentException("지원하지 않는 명령");
            }
        }
    }

    private static void configure(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET application_name = 'sal134-offline-migration'");
            statement.execute("SET timezone = 'UTC'");
            statement.execute("SET datestyle = 'ISO, YMD'");
            statement.execute("SET search_path = public");
            statement.execute("SET lock_timeout = '5s'");
            statement.execute("SET statement_timeout = '10min'");
        }
    }

    private static void acquireLock(final Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_ID);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                require(rows.getBoolean(1), "다른 SAL-134 전환 실행기가 실행 중입니다.");
            }
        }
    }

    private static void migrate(final Settings settings, final String target) {
        Flyway.configure()
                .dataSource(settings.url(), settings.user(), settings.password())
                .locations("classpath:db/migration")
                .schemas("public")
                .defaultSchema("public")
                .target(target)
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    private static void inspect(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT current_database(), inet_server_addr(), inet_server_port()")) {
            rows.next();
            System.out.println("database=" + rows.getString(1) + ", server=" + rows.getString(2) + ":" + rows.getInt(3));
        }
        System.out.println("schemaVersion=" + schemaVersion(connection));
        for (final String table : List.of("observation_batch", "vehicle_observation", "seat_forecast")) {
            if (exists(connection, table)) {
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    System.out.println(table + ".rows=" + rows.getLong(1));
                }
            }
        }
        if (exists(connection, "sal134_transition")) {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT prepared_at, verified_at FROM sal134_transition WHERE singleton")) {
                if (rows.next()) {
                    System.out.println("preparedAt=" + rows.getString(1));
                    System.out.println("verifiedAt=" + rows.getString(2));
                }
            }
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(
                            "SELECT step, processed_rows, completed FROM sal134_transition_progress ORDER BY step")) {
                while (rows.next()) {
                    System.out.println(rows.getString(1) + ": rows=" + rows.getLong(2)
                            + ", completed=" + rows.getBoolean(3));
                }
            }
        }
    }

    private static void prepare(final Connection connection, final String backupId) throws Exception {
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try {
            if (hasPreparedState(connection)) {
                requireBackup(connection, backupId);
                compareSnapshots(connection, false);
                connection.commit();
                System.out.println("PREPARED: 기존 백업 식별과 원본 대조 완료. 저장된 진행 위치를 유지합니다.");
                return;
            }
            // 기존에 임의 복사한 데이터가 있으면 원본 기준점을 새로 승인하지 않는다.
            assertEmptyTargets(connection);
            final Map<String, Snapshot> snapshots = captureSnapshots(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sal134_transition(singleton, backup_id, source_snapshot)
                    VALUES (true, ?, ?::jsonb)
                    """)) {
                statement.setString(1, backupId);
                statement.setString(2, snapshotsJson(snapshots));
                statement.executeUpdate();
            }
            connection.commit();
            System.out.println("PREPARED: 쓰기 중지 후 백업 식별과 원본 전체 SHA-256 기록 완료.");
        } catch (final Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    private static void assertEmptyTargets(final Connection connection) throws SQLException {
        for (final String table : List.of("forecast_publication", "forecast_evaluation", "demand_statistics_version",
                "observation_trip_assignment", "route_data_quality", "route_version_quality_policy",
                "model_training_exclusion", "statistics_training_exclusion", "model_activation_request")) {
            assertZero(connection, "SELECT count(*) FROM " + table,
                    "전환 준비 이전에 대상 테이블에 데이터가 존재합니다: " + table);
        }
        assertZero(connection, "SELECT count(*) FROM model_active_slot WHERE model_deployment_id IS NOT NULL OR version <> 0",
                "전환 준비 이전에 활성 모델이 변경되었습니다.");
    }

    private static void backfill(final Connection connection, final int chunkSize, final int maxChunks)
            throws Exception {
        clearVerification(connection);
        validateLegacy(connection);
        int chunks = 0;
        for (final String step : STEPS) {
            while (!stepCompleted(connection, step)) {
                if (maxChunks > 0 && chunks >= maxChunks) {
                    System.out.println("PAUSED: 지정한 chunk 수를 처리했습니다. 같은 backfill 명령으로 재개합니다.");
                    return;
                }
                processChunk(connection, step, chunkSize);
                chunks++;
            }
        }
        System.out.println("BACKFILLED: 모든 단계 완료. verify 명령으로 원본과 대상 값을 대조하세요.");
    }

    private static void processChunk(final Connection connection, final String step, final int chunkSize)
            throws Exception {
        connection.setAutoCommit(false);
        try {
            final String lower = checkpoint(connection, step);
            String upper = lower;
            int rows = 0;
            final boolean sourceAbsent = ("model-exclusion".equals(step)
                    && !exists(connection, "training_model_release_exclusion"))
                    || ("statistics-exclusion".equals(step)
                    && !exists(connection, "training_statistics_generation_exclusion"));
            try (PreparedStatement query = connection.prepareStatement(sourceAbsent
                    ? "SELECT ?::text WHERE false LIMIT ?" : resource(step + "-keys.sql"))) {
                query.setString(1, lower);
                query.setInt(2, chunkSize);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        upper = result.getString(1);
                        rows++;
                    }
                }
            }
            if (rows > 0) {
                for (final String sql : statements(resource(step + ".sql"))) {
                    try (PreparedStatement write = connection.prepareStatement(sql)) {
                        write.setString(1, lower);
                        write.setString(2, upper);
                        write.executeUpdate();
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sal134_transition_progress(step, cursor, processed_rows, completed)
                    VALUES (?, ?::jsonb, ?, ?)
                    ON CONFLICT (step) DO UPDATE SET cursor = excluded.cursor,
                        processed_rows = sal134_transition_progress.processed_rows + excluded.processed_rows,
                        completed = excluded.completed, updated_at = CURRENT_TIMESTAMP
                    """)) {
                statement.setString(1, step);
                statement.setString(2, upper);
                statement.setLong(3, rows);
                statement.setBoolean(4, rows < chunkSize);
                statement.executeUpdate();
            }
            connection.commit();
            System.out.println(step + ": processed=" + rows + ", completed=" + (rows < chunkSize));
        } catch (final Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void validateLegacy(final Connection connection) throws Exception {
        int check = 0;
        for (final String sql : statements(resource("validate-legacy.sql"))) {
            check++;
            assertZero(connection, sql,
                    "기존 자료가 전환 조건을 만족하지 않습니다. validate-legacy.sql 검사 " + check);
        }
    }

    private static void verify(final Connection connection) throws Exception {
        clearVerification(connection);
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try {
            // 먼저 모든 쓰기를 막고 마지막 잠금 이후의 커밋 상태를 읽는다.
            // REPEATABLE READ에서 잠금 전에 생긴 snapshot을 쓰면 새 변경을 놓칠 수 있다.
            lockVerificationTables(connection);
            for (final String step : STEPS) {
                require(stepCompleted(connection, step), "완료되지 않은 전환 단계: " + step);
            }
            compareSnapshots(connection, false);
            validateLegacy(connection);
            int check = 0;
            for (final String sql : statements(resource("verify.sql"))) {
                check++;
                assertZero(connection, sql, "전환 자료 대조 실패. verify.sql 검사 " + check);
            }
            verifyExclusions(connection, "training_model_release_exclusion", "model_training_exclusion");
            verifyExclusions(connection, "training_statistics_generation_exclusion", "statistics_training_exclusion");
            previewCutover(connection);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sal134_transition SET verified_at = CURRENT_TIMESTAMP WHERE singleton");
            }
            connection.commit();
            System.out.println("VERIFIED: 원본 SHA-256, 전환 누락·중복·값, 품질·학습 조회 대조 완료.");
        } catch (final Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        }
    }

    private static void lockVerificationTables(final Connection connection) throws SQLException {
        final List<String> tables = new ArrayList<>();
        SOURCES.forEach(source -> tables.add(source.table()));
        tables.addAll(List.of("forecast_publication", "forecast_evaluation", "demand_statistics_version",
                "route_data_quality", "route_version_quality_policy", "observation_trip_assignment",
                "model_active_slot", "model_activation_request", "model_training_exclusion",
                "statistics_training_exclusion", "sal134_transition_progress"));
        for (final String table : tables) {
            try (PreparedStatement query = connection.prepareStatement("""
                    SELECT EXISTS (SELECT 1 FROM pg_class WHERE oid = to_regclass(?) AND relkind IN ('r', 'p'))
                    """)) {
                query.setString(1, "public." + table);
                try (ResultSet rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getBoolean(1)) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("LOCK TABLE " + table + " IN ACCESS EXCLUSIVE MODE");
                        }
                    }
                }
            }
        }
    }

    private static void previewCutover(final Connection connection) throws Exception {
        final Savepoint preview = connection.setSavepoint("sal134_cutover_preview");
        try {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE sal134_transition SET verified_at = CURRENT_TIMESTAMP WHERE singleton");
                statement.execute(classpathResource("/db/migration/V18__ddd_storage_cutover.sql"));
            }
            // 실제 V18을 같은 TX 안에서 실행해 바뀐 조회 결과를 전수 대조한다.
            // 아래 rollback으로 컬럼/뷰/트리거를 V17 상태로 복구한다. Flyway 이력은 건드리지 않는다.
            compareSnapshots(connection, true);
        } finally {
            connection.rollback(preview);
            connection.releaseSavepoint(preview);
        }
    }

    private static void verifyExclusions(final Connection connection, final String source, final String target)
            throws SQLException {
        if (!exists(connection, source)) {
            assertZero(connection, "SELECT count(*) FROM " + target, "없는 이관 장부에서 학습 제외 자료를 만들었습니다: " + target);
            return;
        }
        assertZero(connection, "SELECT count(*) FROM ((SELECT to_jsonb(s) FROM " + source
                + " s EXCEPT SELECT to_jsonb(t) FROM " + target + " t) UNION ALL (SELECT to_jsonb(t) FROM "
                + target + " t EXCEPT SELECT to_jsonb(s) FROM " + source + " s)) difference",
                "학습 제외 기록의 누락 또는 값 차이: " + target);
    }

    private static void verifyTrainingViews(final Connection connection) throws Exception {
        connection.setAutoCommit(false);
        try {
            compareSnapshots(connection, true);
            connection.commit();
        } catch (final Exception exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void clearVerification(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE sal134_transition SET verified_at = NULL WHERE singleton");
        }
    }

    private static Map<String, Snapshot> captureSnapshots(final Connection connection) throws Exception {
        final Map<String, Snapshot> result = new LinkedHashMap<>();
        for (final Source source : SOURCES) {
            result.put(source.table(), snapshot(connection, source));
        }
        return result;
    }

    private static Snapshot snapshot(final Connection connection, final Source source)
            throws SQLException, NoSuchAlgorithmException {
        if (!exists(connection, source.table())) {
            return new Snapshot(false, 0, "", "");
        }
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        String lastKey = "";
        final String keyExpression = "jsonb_build_array(" + source.order() + ")::text";
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(1_000);
            try (ResultSet rows = statement.executeQuery("SELECT (" + source.expression() + ")::text, "
                    + keyExpression + " FROM " + source.table() + " t ORDER BY " + source.order())) {
                while (rows.next()) {
                    digest.update(rows.getString(1).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                    count++;
                    lastKey = rows.getString(2);
                }
            }
        }
        return new Snapshot(true, count, HexFormat.of().formatHex(digest.digest()), lastKey);
    }

    private static void compareSnapshots(final Connection connection, final boolean trainingOnly) throws Exception {
        for (final Source source : SOURCES) {
            if (trainingOnly && !source.verifyAfterFinalization()) {
                continue;
            }
            final Snapshot current = snapshot(connection, source);
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT (source_snapshot -> ? ->> 'exists')::boolean,
                           (source_snapshot -> ? ->> 'count')::bigint,
                           source_snapshot -> ? ->> 'sha256', source_snapshot -> ? ->> 'lastKey'
                    FROM sal134_transition WHERE singleton
                    """)) {
                for (int index = 1; index <= 4; index++) {
                    statement.setString(index, source.table());
                }
                try (ResultSet rows = statement.executeQuery()) {
                    require(rows.next(), "prepare 기록이 없습니다.");
                    final Snapshot expected = new Snapshot(rows.getBoolean(1), rows.getLong(2),
                            rows.getString(3), rows.getString(4));
                    // historical 이관이 없던 새 설치에서는 학습 view가 V18에 처음 생긴다.
                    if (trainingOnly && !expected.exists()) {
                        continue;
                    }
                    require(expected.equals(current), "원본 또는 조회 결과 변경 감지: " + source.table());
                }
            }
        }
    }

    private static String snapshotsJson(final Map<String, Snapshot> snapshots) {
        final List<String> entries = new ArrayList<>();
        snapshots.forEach((table, value) -> entries.add(json(table) + ":{\"exists\":" + value.exists()
                + ",\"count\":" + value.count() + ",\"sha256\":" + json(value.sha256())
                + ",\"lastKey\":" + json(value.lastKey()) + "}"));
        return "{" + String.join(",", entries) + "}";
    }

    private static String json(final String value) {
        final StringBuilder result = new StringBuilder("\"");
        for (final char character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32) {
                        result.append("\\u%04x".formatted((int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static boolean hasPreparedState(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM sal134_transition WHERE singleton)")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static void requireBackup(final Connection connection, final String backupId) throws SQLException {
        require(exists(connection, "sal134_transition"), "먼저 prepare 명령을 실행하세요.");
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT backup_id FROM sal134_transition WHERE singleton")) {
            require(rows.next(), "먼저 prepare 명령을 실행하세요.");
            require(backupId.equals(rows.getString(1)), "prepare에 기록한 백업 식별과 다릅니다.");
        }
    }

    private static String checkpoint(final Connection connection, final String step) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT cursor::text FROM sal134_transition_progress WHERE step = ?")) {
            statement.setString(1, step);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : EMPTY_CURSOR;
            }
        }
    }

    private static boolean stepCompleted(final Connection connection, final String step) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT completed FROM sal134_transition_progress WHERE step = ?")) {
            statement.setString(1, step);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() && rows.getBoolean(1);
            }
        }
    }

    private static void assertZero(final Connection connection, final String sql, final String message)
            throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            require(rows.next(), message + ": 검사 결과가 없습니다.");
            final long difference = rows.getLong(1);
            final String examples = rows.getMetaData().getColumnCount() > 1 ? rows.getString(2) : null;
            require(difference == 0, message + ": 불일치 " + difference + "건"
                    + (examples == null ? "" : ", 관측 ID 예시: " + examples));
        }
    }

    private static boolean exists(final Connection connection, final String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            statement.setString(1, "public." + name);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getBoolean(1);
            }
        }
    }

    private static int schemaVersion(final Connection connection) throws SQLException {
        if (!exists(connection, "flyway_schema_history")) {
            return 0;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT coalesce(max(version::integer), 0) FROM flyway_schema_history WHERE success AND version IS NOT NULL")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void requireVersion(final Connection connection, final int minimum, final int maximum)
            throws SQLException {
        final int version = schemaVersion(connection);
        require(version >= minimum && version <= maximum,
                "이 명령은 V" + minimum + "~V" + maximum + "에서만 실행할 수 있습니다. 현재 V" + version);
    }

    private static String resource(final String name) throws IOException {
        return classpathResource("/sal134/" + name);
    }

    private static String classpathResource(final String name) throws IOException {
        try (InputStream stream = Sal134Migration.class.getResourceAsStream(name)) {
            require(stream != null, "전환 SQL 자원이 없습니다: " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // SQL 문자열 안의 세미콜론을 오인하지 않도록 파일의 별도 구분선으로만 나눈다.
    private static List<String> statements(final String sql) {
        return List.of(sql.split("(?m)^-- next-statement\\s*$"));
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Source(String table, String expression, String order) {
        boolean verifyAfterFinalization() {
            return table.startsWith("training_eligible_") || table.startsWith("quality_")
                    || table.equals("forecast_observation_quality") || table.equals("forecast_eligible_observation")
                    || table.equals("active_stop_demand_seed_hourly_total") || table.equals("vehicle_observation");
        }
    }

    private record Snapshot(boolean exists, long count, String sha256, String lastKey) {
    }

    record Settings(String url, String user, String password, String expectedDatabase) {
        static Settings read(final Path config) throws IOException {
            require(Files.isRegularFile(config) && !Files.isSymbolicLink(config), "설정은 일반 파일이어야 합니다.");
            try {
                final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(config);
                require(permissions.stream().noneMatch(permission -> permission.name().startsWith("GROUP_")
                                || permission.name().startsWith("OTHERS_")),
                        "설정 파일은 소유자만 접근할 수 있어야 합니다. chmod 600을 적용하세요.");
            } catch (final UnsupportedOperationException ignored) {
                // POSIX 권한을 지원하지 않는 로컬 환경에서는 운영체제 파일 접근 제어를 사용한다.
            }
            final Properties properties = new Properties();
            try (InputStream stream = Files.newInputStream(config)) {
                properties.load(stream);
            }
            final String url = required(properties, "jdbc.url");
            require(url.startsWith("jdbc:postgresql:"), "PostgreSQL JDBC URL만 지원합니다.");
            require(!url.matches("(?i).*[?&](password|user)=.*"), "JDBC URL에 인증 정보를 넣지 마세요.");
            final String password = System.getenv(required(properties, "jdbc.password-env"));
            require(password != null && !password.isBlank(), "DB 비밀번호 환경 변수가 비어 있습니다.");
            return new Settings(url, required(properties, "jdbc.user"), password, required(properties, "jdbc.expected-database"));
        }

        Connection connect() throws SQLException {
            final Connection connection = DriverManager.getConnection(url, user, password);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT current_database()")) {
                rows.next();
                require(expectedDatabase.equals(rows.getString(1)), "설정한 대상 DB 이름과 실제 연결한 DB 이름이 다릅니다.");
                return connection;
            } catch (final Exception exception) {
                connection.close();
                throw exception;
            }
        }

        String redact(final String message) {
            return message == null ? "원인을 확인할 수 없는 오류" : message.replace(password, "[redacted]").replace(url, "[database]");
        }

        private static String required(final Properties properties, final String key) {
            final String value = properties.getProperty(key);
            require(value != null && !value.isBlank(), "필수 설정 누락: " + key);
            return value;
        }
    }

    record Options(String command, Path config, boolean writersStopped, String backupId, int chunkSize, int maxChunks) {
        static Options parse(final String[] args) {
            require(args.length > 0 && COMMANDS.contains(args[0]),
                    "사용법: inspect|prepare|backfill|verify|finalize --config FILE [--writers-stopped --backup-id ID]"
                            + " [--chunk-size 1000] [--max-chunks N]");
            final Map<String, String> values = new LinkedHashMap<>();
            boolean writersStopped = false;
            for (int index = 1; index < args.length; index++) {
                final String option = args[index];
                if ("--writers-stopped".equals(option)) {
                    require(!writersStopped, "중복 옵션: " + option);
                    writersStopped = true;
                } else {
                    require(Set.of("--config", "--backup-id", "--chunk-size", "--max-chunks").contains(option),
                            "알 수 없는 옵션: " + option);
                    require(index + 1 < args.length && !args[index + 1].startsWith("--"), "옵션 값 누락: " + option);
                    require(values.putIfAbsent(option, args[++index]) == null, "중복 옵션: " + option);
                }
            }
            require(values.containsKey("--config"), "--config가 필요합니다.");
            final int chunkSize = Integer.parseInt(values.getOrDefault("--chunk-size", "1000"));
            final int maxChunks = Integer.parseInt(values.getOrDefault("--max-chunks", "0"));
            require(chunkSize >= 1 && chunkSize <= 10_000, "chunk-size는 1~10000이어야 합니다.");
            require(maxChunks >= 0, "max-chunks는 0 이상이어야 합니다.");
            return new Options(args[0], Path.of(values.get("--config")), writersStopped,
                    values.getOrDefault("--backup-id", ""), chunkSize, maxChunks);
        }

        void requireWriteAttestation() {
            require(writersStopped, "모든 앱과 쓰기 작업을 중지한 뒤 --writers-stopped를 명시하세요.");
            require(!backupId.isBlank(), "쓰기 중지 이후 생성한 복원 지점을 --backup-id로 명시하세요.");
        }
    }
}
