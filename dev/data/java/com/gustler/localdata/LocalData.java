package com.gustler.localdata;

import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public final class LocalData {
    private static final OffsetDateTime BASELINE = OffsetDateTime.parse("2026-10-06T00:00:00Z");

    public static void main(String[] args) {
        String step = "INPUT";
        try {
            var target = DataTarget.resolve(args, System.getenv());
            final boolean recover = args.length == 3 && args[2].equals("--recover-collector-only");
            final boolean reset = args.length == 3 && args[2].equals("--reset-generated");
            String password = System.getenv("DB_PASSWORD");
            Path routeFile = Path.of(args[0]);
            RouteDataset data = RouteDataset.read(routeFile);
            Path catalogFile = routeFile.resolveSibling("catalog-routes.json");
            RouteDataset catalog = RouteDataset.readCatalog(catalogFile);
            String catalogDigest = ReferenceBundle.sha(Files.readAllBytes(catalogFile));
            String routeDigest = ReferenceBundle.sha(Files.readAllBytes(routeFile));
            step = "MODEL";
            var inspection = ReferenceBundle.prepare(routeFile.resolveSibling("model-reference"), Path.of(args[1]));
            if (target.shared()) {
                step = "MIGRATION";
                // 준비 도구에서는 DB 접속 정보 대신 결과 코드만 남긴다.
                var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("org.flywaydb");
                logger.setLevel(ch.qos.logback.classic.Level.OFF);
                Flyway.configure().dataSource(target.url(), target.username(), password)
                    .locations("classpath:db/migration").loggers("slf4j").cleanDisabled(true)
                    .jdbcProperties(Map.of("connectTimeout", "5", "socketTimeout", "30",
                        "options", "-c statement_timeout=25000 -c lock_timeout=1000"))
                    .baselineOnMigrate(false).load().migrate();
            }
            step = "DATABASE";
            Properties properties = new Properties();
            properties.setProperty("user", target.username());
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "30");
            properties.setProperty("ApplicationName", target.shared() ? "salmonbus-shared-dev-data" : "salmonbus-local-data");
            boolean existing;
            try (Connection connection = DriverManager.getConnection(target.url(), properties)) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, "SET LOCAL statement_timeout = '10s'");
                    execute(connection, "SET LOCAL lock_timeout = '1s'");
                    scalar(connection, "SELECT pg_advisory_xact_lock(16320261006)");
                    require(target.username().equals(scalar(connection, "SELECT current_database()")), "LOCAL_DATABASE_NAME");
                    require(!scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success").equals("0"), "MIGRATION_REQUIRED");
                    execute(connection, """
                        CREATE TABLE IF NOT EXISTS local_development_fixture (
                            singleton boolean PRIMARY KEY CHECK(singleton), version text NOT NULL,
                            route_digest char(64) NOT NULL, bundle_digest char(64) NOT NULL,
                            prepared_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)
                        """);
                    existing = !scalar(connection, "SELECT count(*) FROM local_development_fixture").equals("0");
                    if (existing) {
                        require(scalar(connection, """
                            SELECT count(*) FROM local_development_fixture
                            WHERE singleton AND version=? AND route_digest=? AND bundle_digest=?
                            """, ReferenceBundle.VERSION, routeDigest, inspection.bundleDigest()).equals("1"), "FIXTURE_VERSION_CHANGED");
                        if (reset) {
                            step = "RESET";
                            require(scalar(connection, """
                                SELECT count(*) FROM model_deployment WHERE state='ACTIVE' AND bundle_digest<>?
                                """, inspection.bundleDigest()).equals("0"), "UNOWNED_ACTIVE_MODEL");
                            verify(connection, data, args[1]);
                            verify(connection, catalog, args[1], false);
                            resetGenerated(connection, data);
                        }
                    } else {
                        require(!reset, "FIXTURE_REQUIRED_FOR_RESET");
                        step = "SEED";
                        if (recover) {
                            recoverCollectorOnly(connection, data);
                        } else {
                            require(scalar(connection, "SELECT count(*) FROM route").equals("0")
                                && scalar(connection, "SELECT count(*) FROM model_deployment").equals("0"), "UNOWNED_LOCAL_DATA");
                            seed(connection, data);
                        }
                        execute(connection, "INSERT INTO local_development_fixture VALUES (true, ?, ?, ?, CURRENT_TIMESTAMP)",
                            ReferenceBundle.VERSION, routeDigest, inspection.bundleDigest());
                    }
                    step = "CATALOG";
                    prepareCatalog(connection, data, catalog, catalogDigest, args[1]);
                    step = "VERIFY";
                    require(scalar(connection, """
                        SELECT count(*) FROM model_deployment WHERE state='ACTIVE' AND bundle_digest<>?
                        """, inspection.bundleDigest()).equals("0"),
                        "UNOWNED_ACTIVE_MODEL");
                    verify(connection, data, args[1]);
                    verify(connection, catalog, args[1], false);
                    var anchors = LocalForecastHistory.prepare(connection,inspection.routes());
                    connection.commit();
                    step="HISTORY";
                    LocalForecastHistory.write(anchors,Path.of(args[1]).getParent().resolve("alighting-history"));
                } catch (Exception error) {
                    connection.rollback();
                    throw error;
                }
            }
            System.out.println(new String(ReferenceBundle.bytes(Map.of("status", "ok",
                "result", reset ? "reset" : existing ? "preserved" : recover ? "recovered" : "prepared", "routeCount", data.routes().size() + catalog.routes().size(),
                "catalogRouteCount", catalog.routes().size(), "modelRouteCount", inspection.routes().size(),
                "stopCount", data.routes().stream().mapToInt(route -> route.stops().size()).sum()
                    + catalog.routes().stream().mapToInt(route -> route.stops().size()).sum(),
                "statisticsCellCount", 0,
                "featureContract", inspection.featureContractVersion(), "bundleDigest", inspection.bundleDigest(),
                "modelActivated", false)), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception error) {
            String code = error instanceof FixtureError ? error.getMessage() : step + "_FAILED";
            String sqlState = error instanceof java.sql.SQLException sql && sql.getSQLState() != null
                && sql.getSQLState().matches("[0-9A-Z]{5}") ? sql.getSQLState() : "UNKNOWN";
            System.err.println("{\"status\":\"failed\",\"code\":\"" + code + "\",\"type\":\""
                + error.getClass().getSimpleName() + "\",\"sqlState\":\"" + sqlState + "\"}");
            System.exit(1);
        }
    }

    private static void resetGenerated(Connection connection, RouteDataset data) throws Exception {
        execute(connection, """
            TRUNCATE daily_call_quota, observation_batch, vehicle_observation, seat_forecast,
                forecast_publication, forecast_evaluation_work, forecast_evaluation_result, forecast_evaluation_compact,
                alighting_forecast_evaluation_work, alighting_forecast_evaluation_result, alighting_evaluation_archive,
                alighting_settlement_batch_work, alighting_settlement_observation, alighting_settlement_pass,
                alighting_settlement_projection, alighting_settlement_generation,
                operational_archive_file, operational_archive_job, operational_archive_boundary, archive_legacy_member, archive_legacy_batch,
                file_statistics_publication,
                stop_demand_statistics, demand_statistics_version;
            UPDATE alighting_settlement_queue_scan SET observation_id=0,stop_order=0,target_kind='',label_version='',complete=false;
            """);
        execute(connection, "UPDATE route SET data_revision=1");

    }

    static long seedRoute(Connection connection, RouteDataset.Route route) throws Exception {
        final long routeId = Long.parseLong(scalar(connection, """
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES (?,'GBIS',?,?,?,?) RETURNING id
            """, route.routeId(), route.routeId(), route.displayName(), route.startStopName(), route.endStopName()));
        RouteDataset.Timetable time = route.timetable();
        final long versionId = Long.parseLong(scalar(connection, """
            INSERT INTO route_version(route_id,turn_sequence,up_first_departure_time,up_last_departure_time,
                down_first_departure_time,down_last_departure_time,content_digest,valid_from)
            VALUES (?,?,?,?,?,?,?,?) RETURNING id
            """, routeId, route.turnSequence(), time.upFirstDepartureTime(), time.upLastDepartureTime(),
            time.downFirstDepartureTime(), time.downLastDepartureTime(), route.versionDigest(), BASELINE));
        for (RouteDataset.Stop stop : route.stops()) {
            execute(connection, """
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                VALUES (?,?,?,?,?,?)
                """, versionId, stop.sequence(), stop.stationId(), stop.name(), stop.direction(), stop.boardingAllowed());
            execute(connection, "INSERT INTO route_stop_location_history VALUES (?,?,?,?,?,?)",
                versionId, stop.sequence(), stop.stationId(), BASELINE, stop.x(), stop.y());
        }
        return versionId;
    }

    private static void seed(Connection connection, RouteDataset data) throws Exception {
        for (RouteDataset.Route route : data.routes()) {
            seedRoute(connection, route);
        }
    }

    private static void prepareCatalog(Connection connection, RouteDataset data, RouteDataset catalog,
                                       String digest, String modelDirectory) throws Exception {
        execute(connection, """
            CREATE TABLE IF NOT EXISTS local_development_catalog (
                singleton boolean PRIMARY KEY CHECK(singleton), version text NOT NULL,
                content_digest char(64) NOT NULL, route_count integer NOT NULL,
                prepared_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP)
            """);
        final boolean exists = !scalar(connection, "SELECT count(*) FROM local_development_catalog").equals("0");
        if (exists) {
            require(scalar(connection, """
                SELECT count(*) FROM local_development_catalog
                WHERE singleton AND version=? AND content_digest=? AND route_count=?
                """, catalog.version(), digest, catalog.routes().size()).equals("1"), "CATALOG_VERSION_CHANGED");
        } else {
            require(scalar(connection, "SELECT count(*) FROM route").equals(String.valueOf(data.routes().size())),
                "UNOWNED_CATALOG_DATA");
            verify(connection, data, modelDirectory);
            for (RouteDataset.Route route : catalog.routes()) {
                seedRoute(connection, route);
            }
            execute(connection, "INSERT INTO local_development_catalog VALUES (true,?,?,?,CURRENT_TIMESTAMP)",
                catalog.version(), digest, catalog.routes().size());
        }
        require(scalar(connection, "SELECT count(*) FROM route")
            .equals(String.valueOf(data.routes().size() + catalog.routes().size())), "CATALOG_DATA_CHANGED");
    }

    private static void recoverCollectorOnly(Connection connection, RouteDataset data) throws Exception {
        require(scalar(connection, "SELECT count(*) FROM route").equals(String.valueOf(data.routes().size())), "RECOVERY_STATE_UNSUPPORTED");
        require(scalar(connection, "SELECT count(*) FROM model_deployment").equals("0")
            && scalar(connection, "SELECT count(*) FROM seat_forecast").equals("0")
            && scalar(connection, "SELECT count(*) FROM forecast_publication").equals("0")
            && scalar(connection, "SELECT count(*) FROM stop_demand_statistics").equals("0"), "RECOVERY_STATE_UNSUPPORTED");
        require(scalar(connection, """
            SELECT count(*) FROM vehicle_observation
            WHERE vehicle_id IS NULL OR plate_number IS NULL
               OR vehicle_id !~ '^L[0-9a-f]{8}-[0-9]{9}-[0-9]+[UD]$'
               OR plate_number !~ '^LOCAL-[0-9]+(-[0-5])?-[UD]$'
            """).equals("0") && !scalar(connection, "SELECT count(*) FROM vehicle_observation").equals("0"), "RECOVERY_REQUIRES_MOCK_OBSERVATIONS");
        require(scalar(connection, "SELECT count(*) FROM route WHERE data_revision<>1").equals("0"), "RECOVERY_STATE_UNSUPPORTED");
        verify(connection, data, ReferenceBundle.DIRECTORY);
    }

    private static void verify(Connection connection, RouteDataset data, String modelDirectory) throws Exception {
        verify(connection, data, modelDirectory, true);
    }

    static void verify(Connection connection, RouteDataset data, String modelDirectory, final boolean modelSupported) throws Exception {
        JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        var release = modelSupported ? new FileModelBundleLoader().filesUnder(modelDirectory).load() : null;
        for (RouteDataset.Route route : data.routes()) {
            final long versionId;
            try (var query = statement(connection, """
                SELECT v.id, r.public_route_id, r.source_id, r.display_name, r.start_stop_name, r.end_stop_name,
                    v.turn_sequence,v.up_first_departure_time,v.up_last_departure_time,
                    v.down_first_departure_time,v.down_last_departure_time,v.content_digest
                FROM route r JOIN route_version v ON v.route_id=r.id
                WHERE r.source_route_id=? AND v.valid_to IS NULL
                """, route.routeId()); var rows = query.executeQuery()) {
                require(rows.next(), "ROUTE_VERSION_MISSING");
                versionId = rows.getLong(1);
                List<Object> actual = new ArrayList<>();
                for (int column = 2; column <= 12; column++) {
                    actual.add(rows.getObject(column));
                }
                var time = route.timetable();
                require(actual.equals(java.util.Arrays.asList(route.routeId(), "GBIS", route.displayName(),
                    route.startStopName(), route.endStopName(), route.turnSequence(), time.upFirstDepartureTime(),
                    time.upLastDepartureTime(), time.downFirstDepartureTime(), time.downLastDepartureTime(), route.versionDigest()))
                    && !rows.next(), "ROUTE_DATA_CHANGED");
            }
            List<com.gustler.backend.forecasting.domain.model.RouteStop> modelStops = new ArrayList<>();
            try (var query = statement(connection, """
                SELECT s.stop_order,s.stop_id,s.name,s.direction,s.boarding_allowed,l.x,l.y
                FROM route_stop s LEFT JOIN LATERAL (
                    SELECT x,y FROM route_stop_location_history h
                    WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order AND h.stop_id=s.stop_id
                    ORDER BY h.observed_at DESC LIMIT 1) l ON true
                WHERE s.route_version_id=? ORDER BY s.stop_order
                """, versionId); var rows = query.executeQuery()) {
                for (RouteDataset.Stop stop : route.stops()) {
                    require(rows.next() && rows.getInt(1) == stop.sequence() && rows.getString(2).equals(stop.stationId())
                        && rows.getString(3).equals(stop.name()) && rows.getString(4).equals(stop.direction())
                        && rows.getBoolean(5) == stop.boardingAllowed() && Objects.equals(rows.getObject(6), stop.x())
                        && Objects.equals(rows.getObject(7), stop.y()), "STOP_DATA_CHANGED");
                    modelStops.add(new com.gustler.backend.forecasting.domain.model.RouteStop(versionId, stop.sequence(),
                        stop.stationId(), stop.boardingAllowed(), RouteDirection.valueOf(stop.direction())));
                }
                require(!rows.next(), "STOP_DATA_CHANGED");
            }
            if (!modelSupported) {
                // 노선과 정류장 기준만 검증한다. 수집 후 생긴 관측과 통계는 재시작 시 보존한다.
                continue;
            }
            var stops = new com.gustler.backend.forecasting.domain.model.RouteStops(
                versionId,route.routeId(),modelStops,route.displayName());
            if (release.scope().coversSourceRoute(route.routeId())) {
                require(release.model().supportsRoute(stops), "MODEL_ROUTE_REFERENCE_CHANGED");
            }
        }
    }


    private static PreparedStatement statement(Connection connection, String sql, Object... args) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
        return statement;
    }

    private static void execute(Connection connection, String sql, Object... args) throws Exception {
        try (var statement = statement(connection, sql, args)) {
            statement.execute();
        }
    }

    private static String scalar(Connection connection, String sql, Object... args) throws Exception {
        try (var statement = statement(connection, sql, args); var rows = statement.executeQuery()) {
            require(rows.next(), "LOCAL_QUERY_RESULT");
            return rows.getString(1);
        }
    }

    static void require(final boolean condition, String code) {
        if (!condition) {
            throw new FixtureError(code);
        }
    }

    private static final class FixtureError extends RuntimeException {
        private FixtureError(String code) {
            super(code);
        }
    }
}
