package com.gustler.backend.maintenance;

import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.*;

import static org.assertj.core.api.Assertions.*;

import com.gustler.backend.routegeometry.api.*;
import com.gustler.backend.support.TestDatabase;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

class RouteDisplayCommandTest {
    private static TestDatabase database;
    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void start() {
        database = TestDatabase.create();
        dataSource =
                new DriverManagerDataSource(
                        database.jdbcUrl(), database.username(), database.password());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void close() {
        if (database != null) database.close();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE route CASCADE").update();
        RouteDisplayDbSamples.seed(jdbc, "204000057");
    }

    private Object transaction(java.util.function.Function<RouteDisplayCommand, Object> action) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> action.apply(new RouteDisplayCommand(jdbc, JSON)));
    }

    private byte[] artifact(RouteDisplayInput input, double offset) {
        return JSON.writeValueAsBytes(manual(input, offset));
    }

    @Test
    void 멱등등록과_현재원본_내보내기_비활성화를_검증한다() {
        byte[] bytes = artifact(input(), 0.0001);
        transaction(command -> command.install(bytes));
        transaction(command -> command.install(bytes));
        assertThat(
                        jdbc.sql("SELECT count(*) FROM route_display_geometry")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
        assertThat(transaction(command -> command.status("204000057")).toString())
                .contains("MANUAL_REVIEWED");
        assertThat(transaction(command -> command.export("204000057")).toString())
                .contains(input().digest());
        assertThatThrownBy(
                        () -> transaction(command -> command.disable("204000057", "0".repeat(64))))
                .isInstanceOf(MaintenanceException.class);
        transaction(command -> command.disable("204000057", input().digest()));
        assertThat(transaction(command -> command.status("204000057")).toString())
                .contains("AUTOMATIC_FALLBACK");
    }

    @Test
    void 최신좌표가_바뀌면_과거수동의_등록을_거부한다() {
        jdbc.sql("UPDATE route_stop_location_history SET x=x+0.00001 WHERE stop_order=2").update();
        assertThatThrownBy(() -> transaction(command -> command.install(artifact(input(), 0))))
                .isInstanceOf(MaintenanceException.class);
        assertThat(
                        jdbc.sql("SELECT count(*) FROM route_display_geometry")
                                .query(Integer.class)
                                .single())
                .isZero();
    }

    @Test
    void preview와_다른원본의_해시갈아끼우기를_거부한다() {
        byte[] preview = JSON.writeValueAsBytes(manual(input(), 0));
        assertThatThrownBy(
                        () ->
                                transaction(
                                        command ->
                                                command.install(
                                                        new String(preview)
                                                                .replace(
                                                                        "REVIEWED",
                                                                        "UNREVIEWED_LOCAL_PREVIEW")
                                                                .getBytes())))
                .isInstanceOf(MaintenanceException.class);
        var original = input();
        var visits = new ArrayList<>(original.visits());
        visits.set(
                1,
                new RouteDisplayInput.Visit(
                        "wrong-station", 2, "UP", true, original.visits().get(1).sourcePoint()));
        var wrong = new RouteDisplayInput(original.routeId(), 2, original.points(), visits);
        byte[] fake =
                JSON.writeValueAsString(manual(wrong, 0))
                        .replace(wrong.digest(), original.digest())
                        .getBytes();
        assertThatThrownBy(() -> transaction(command -> command.install(fake)))
                .isInstanceOf(MaintenanceException.class);
    }

    @Test
    void 병렬_수동등록이_공유정류장_표시좌표의_충돌을_놓치지않는다() throws Exception {
        RouteDisplayDbSamples.seed(jdbc, "204000081");
        var second =
                new RouteDisplayInput(
                        "204000081", input().turnSequence(), input().points(), input().visits());
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(gate, artifact(input(), 0.0001)));
            var other = executor.submit(() -> attempt(gate, artifact(second, 0.0002)));
            gate.countDown();
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), other.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(
                        jdbc.sql("SELECT count(*) FROM route_display_geometry")
                                .query(Integer.class)
                                .single())
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 공유정류장의_핀없는_경유방문과_승차핀은_등록순서와무관하게_공존한다(boolean nonboardingFirst) {
        RouteDisplayDbSamples.seed(jdbc, "204000081");
        jdbc.sql("""
UPDATE route_stop SET boarding_allowed=false
WHERE stop_order=2 AND route_version_id IN (
    SELECT version.id FROM route_version version JOIN route ON route.id=version.route_id
    WHERE route.public_route_id='204000081' AND route.source_id='GBIS'
)
""").update();
        var base = input();
        var sourceVisits = new ArrayList<>(base.visits());
        var original = sourceVisits.get(1);
        sourceVisits.set(1, new RouteDisplayInput.Visit(original.stationId(), original.sequence(),
                original.direction(), false, original.sourcePoint()));
        var passThrough = new RouteDisplayInput("204000081", base.turnSequence(), base.points(), sourceVisits);
        var document = manual(passThrough, 0.0001);
        var visits = new ArrayList<>(document.visits());
        var visit = visits.get(1);
        visits.set(1, new ManualRouteDisplay.Visit(visit.stationId(), visit.sequence(), visit.direction(),
                false, visit.sourcePoint(), null));
        var noMarker = new ManualRouteDisplay(document.format(), document.status(), document.algorithm(),
                document.routeId(), document.inputDigest(), document.displayPaths(), document.connections(),
                visits, document.review());
        byte[] boarding = artifact(base, 0.0001);
        byte[] nonboarding = JSON.writeValueAsBytes(noMarker);
        transaction(command -> command.install(nonboardingFirst ? nonboarding : boarding));
        transaction(command -> command.install(nonboardingFirst ? boarding : nonboarding));
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_geometry").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(transaction(command -> command.status(base.routeId())).toString()).contains("MANUAL_REVIEWED");
        assertThat(transaction(command -> command.status(passThrough.routeId())).toString()).contains("MANUAL_REVIEWED");
        var stored = JSON.readValue(jdbc.sql("""
SELECT display.document::text FROM route_display_geometry display JOIN route ON route.id=display.route_id
WHERE route.public_route_id='204000081'
""").query(String.class).single(), ManualRouteDisplay.class);
        assertThat(stored.visits().get(1).displayPoint()).isNull();
        assertThat(stored.connections().get("2")).isEqualTo(document.connections().get("2"));
    }

    private boolean attempt(CountDownLatch gate, byte[] bytes) throws Exception {
        gate.await();
        try {
            transaction(command -> command.install(bytes));
            return true;
        } catch (MaintenanceException conflict) {
            return false;
        }
    }
}
