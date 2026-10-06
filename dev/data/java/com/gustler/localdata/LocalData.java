package com.gustler.localdata;

import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteDataQualityAccess;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcStopDemandStatisticsRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

public final class LocalData {
    private static final String URL = "jdbc:postgresql://postgres:5432/salmonbus_local";
    private static final String USER = "salmonbus_local";
    private static final String STATISTICS = ForecastFeatureContract.STATISTICS_CALCULATION_VERSION;
    private static final OffsetDateTime BASELINE = Instant.parse(DevelopmentBundle.DATA_THROUGH).atOffset(ZoneOffset.UTC);

    public static void main(String[] args) {
        String step = "INPUT";
        try {
            require(args.length == 2 && args[0].equals("/local/data/routes.json")
                && args[1].equals("/local/models/development-v1"), "LOCAL_PATHS");
            require(URL.equals(System.getenv("DB_URL")) && USER.equals(System.getenv("DB_USERNAME")), "LOCAL_DATABASE_ONLY");
            String password = System.getenv("DB_PASSWORD");
            require(password != null && password.matches("[0-9a-f]{48}"), "LOCAL_CREDENTIALS");
            require("local-only-placeholder".equals(System.getenv("GBIS_SERVICE_KEY")), "LOCAL_KEY_ONLY");
            Path routeFile = Path.of(args[0]);
            RouteDataset data = RouteDataset.read(routeFile);
            String routeDigest = DevelopmentBundle.sha(Files.readAllBytes(routeFile));
            step = "MODEL";
            var inspection = DevelopmentBundle.prepare(data, Path.of(args[1]));
            step = "DATABASE";
            Properties properties = new Properties();
            properties.setProperty("user", USER);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", "5");
            properties.setProperty("socketTimeout", "30");
            properties.setProperty("ApplicationName", "salmonbus-local-data");
            boolean existing;
            try (Connection connection = DriverManager.getConnection(URL, properties)) {
                connection.setAutoCommit(false);
                try {
                    execute(connection, "SET LOCAL statement_timeout = '10s'");
                    execute(connection, "SET LOCAL lock_timeout = '1s'");
                    scalar(connection, "SELECT pg_advisory_xact_lock(16320261006)");
                    require(USER.equals(scalar(connection, "SELECT current_database()")), "LOCAL_DATABASE_NAME");
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
                            """, DevelopmentBundle.VERSION, routeDigest, inspection.bundleDigest()).equals("1"), "FIXTURE_VERSION_CHANGED");
                    } else {
                        require(scalar(connection, "SELECT count(*) FROM route").equals("0")
                            && scalar(connection, "SELECT count(*) FROM model_deployment").equals("0"), "UNOWNED_LOCAL_DATA");
                        step = "SEED";
                        seed(connection, data);
                        execute(connection, "INSERT INTO local_development_fixture VALUES (true, ?, ?, ?, CURRENT_TIMESTAMP)",
                            DevelopmentBundle.VERSION, routeDigest, inspection.bundleDigest());
                    }
                    step = "VERIFY";
                    verify(connection, data, args[1]);
                    connection.commit();
                } catch (Exception error) {
                    connection.rollback();
                    throw error;
                }
            }
            System.out.println(new String(DevelopmentBundle.bytes(Map.of("status", "ok",
                "result", existing ? "preserved" : "prepared", "routeCount", data.routes().size(),
                "stopCount", data.routes().stream().mapToInt(route -> route.stops().size()).sum(),
                "statisticsCellCount", data.routes().stream().flatMap(route -> route.stops().stream()).filter(RouteDataset.Stop::boardingAllowed).count() * 3,
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

    private static void seed(Connection connection, RouteDataset data) throws Exception {
        for (int index = 0; index < data.routes().size(); index++) {
            RouteDataset.Route route = data.routes().get(index);
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
                time.downFirstDepartureTime(), time.downLastDepartureTime(), route.contentDigest(), BASELINE));
            execute(connection, "INSERT INTO route_data_quality(route_id) VALUES (?)", routeId);
            final long cellCount = route.stops().stream().filter(RouteDataset.Stop::boardingAllowed).count() * 3;
            execute(connection, """
                INSERT INTO demand_statistics_version(route_version_id,calculation_version,revision,data_until,
                    computed_at,quality_revision,cell_count) VALUES (?,?,1,?,?,1,?)
                """, versionId, STATISTICS, BASELINE.minusDays(1), BASELINE, cellCount);
            for (RouteDataset.Stop stop : route.stops()) {
                execute(connection, """
                    INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                    VALUES (?,?,?,?,?,?)
                    """, versionId, stop.sequence(), stop.stationId(), stop.name(), stop.direction(), stop.boardingAllowed());
                execute(connection, "INSERT INTO route_stop_location_history VALUES (?,?,?,?,?,?)",
                    versionId, stop.sequence(), stop.stationId(), BASELINE, stop.x(), stop.y());
                if (stop.boardingAllowed()) {
                    for (TimeSlot slot : TimeSlot.values()) {
                        execute(connection, """
                            INSERT INTO stop_demand_statistics(route_version_id,stop_order,time_slot,calculation_version,
                                revision,average_fill_rate,average_net_boarding_rate,sample_count,day_count,
                                data_until,computed_at,quality_revision)
                            VALUES (?,?,?,?,1,?,?,120,7,?,?,1)
                            """, versionId, stop.sequence(), slot.name().toLowerCase(java.util.Locale.ROOT), STATISTICS,
                            fillRate(route, stop, slot, index), netBoarding(route, stop, slot), BASELINE.minusDays(1), BASELINE);
                    }
                }
            }
        }
    }

    private static double fillRate(RouteDataset.Route route, RouteDataset.Stop stop, TimeSlot slot, final int index) {
        final double position = (stop.sequence() - 1.0) / (route.stops().size() - 1.0);
        return .25 + .25 * Math.sin(position * Math.PI) + index * .01 + slot.ordinal() * .05;
    }

    private static double netBoarding(RouteDataset.Route route, RouteDataset.Stop stop, TimeSlot slot) {
        final double position = (stop.sequence() - 1.0) / (route.stops().size() - 1.0);
        return .12 * Math.cos(position * Math.PI * 2) * (slot == TimeSlot.OTHER ? .5 : 1);
    }

    private static void verify(Connection connection, RouteDataset data, String modelDirectory) throws Exception {
        require(scalar(connection, "SELECT count(*) FROM route").equals("8"), "ROUTE_DATA_CHANGED");
        JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
        var statistics = new JdbcStopDemandStatisticsRepository(jdbc, new JdbcRouteDataQualityAccess(jdbc));
        var release = new FileModelBundleLoader().filesUnder(modelDirectory).load();
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
                    time.upLastDepartureTime(), time.downFirstDepartureTime(), time.downLastDepartureTime(), route.contentDigest()))
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
            release.routeReference().requireMatches(new com.gustler.backend.forecasting.domain.model.RouteStops(
                versionId, route.routeId(), modelStops, route.displayName()));
            require(statistics.readAsOf(versionId, TimeSlot.OTHER, STATISTICS, BASELINE.minusSeconds(1).toInstant())
                .revision() == 0, "STATISTICS_AS_OF");
            for (TimeSlot slot : TimeSlot.values()) {
                var cells = statistics.readAsOf(versionId, slot, STATISTICS, BASELINE.plusSeconds(1).toInstant());
                require(cells.revision() == 1
                    && cells.cells().size() == route.stops().stream().filter(RouteDataset.Stop::boardingAllowed).count(), "STATISTICS_FIXTURE_CHANGED");
                for (var cell : cells.cells()) {
                    RouteDataset.Stop stop = route.stops().get(cell.stopOrder() - 1);
                    final int index = RouteDataset.MODEL_ROUTES.indexOf(route.displayName());
                    require(Math.abs(cell.averageFillRate() - fillRate(route, stop, slot, index)) < 1e-12
                        && Math.abs(cell.averageNetBoardingRate() - netBoarding(route, stop, slot)) < 1e-12
                        && cell.sampleCount() == 120 && cell.dayCount() == 7, "STATISTICS_FIXTURE_CHANGED");
                }
            }
            for (int ahead = 1; ahead <= 12; ahead++) {
                double[] vector = new double[release.features().featureNames().size()];
                vector[0] = 1;
                vector[4] = .25;
                var cells = statistics.readAsOf(versionId, TimeSlot.OTHER, STATISTICS, BASELINE.plusSeconds(1).toInstant());
                vector[28] = cells.fillRateScoreAt(ahead + 1);
                vector[29] = cells.netBoardingSegmentScoreOf(ahead + 1, ahead);
                var prediction = release.predictor().predict(new SeatDistributionInput(vector, route.displayName(), ahead, 10, 40, null));
                final double fullChance = prediction.distribution().fullChance();
                final double seats = prediction.distribution().expectedSeats();
                require(Double.isFinite(fullChance) && fullChance >= 0 && fullChance <= 1
                    && Double.isFinite(seats) && seats >= 0 && seats <= 70, "MODEL_CALCULATION");
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
