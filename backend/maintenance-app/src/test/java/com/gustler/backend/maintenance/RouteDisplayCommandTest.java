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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.io.TempDir;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;

class RouteDisplayCommandTest {
    @TempDir Path directory;
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
        jdbc.sql("TRUNCATE route_display_generation_job,route_display_automatic_artifact").update();
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

    private Object retry(String key) {
        return retry("204000057", key);
    }

    private Object retry(String routeId, String key) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return tx.execute(status -> new RouteDisplayRetryCommand(jdbc, JSON,
                Clock.fixed(RouteDisplayRetryFixtures.TIME, ZoneOffset.UTC)).retry(routeId, key));
    }

    @Test
    void 실패한_현재작업만_재개하고_누적시도와_실패이력을_보존한다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        assertThat(retry(job.key()).toString()).contains("PENDING", "attemptLimit=10", "attempts=5");
        var row = jdbc.sql("""
                SELECT status,attempts,attempt_limit,input_json::text,last_failure_code,
                       last_failure_at,failed_at,lease_token,lease_until,
                       next_attempt_at<=clock_timestamp() AS due
                FROM route_display_generation_job WHERE generation_key=?
                """).param(job.key()).query((r, n) -> List.of(r.getString(1), r.getInt(2), r.getInt(3),
                        JSON.readTree(r.getString(4)), r.getString(5), r.getTimestamp(6).toInstant(),
                        r.getObject(7)==null, r.getObject(8)==null, r.getObject(9)==null, r.getBoolean(10))).single();
        assertThat(row).containsExactly("PENDING",5,10,JSON.readTree(job.document()),"EXECUTION_ERROR",
                RouteDisplayRetryFixtures.TIME,true,true,true,true);
        assertThat(jdbc.sql("SELECT count(*) FROM route_display_automatic_artifact")
                .query(Integer.class).single()).isZero();
    }

    @Test
    void 일찍_실패한_작업도_추가예산을_현재누적시도에서_정확히_다섯번만_부여한다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc, RouteDisplayRetryFixtures.geometry(jdbc),
                AutomaticRouteDisplay.REVISION,2,10,null);
        retry(job.key());
        assertThat(jdbc.sql("SELECT attempt_limit FROM route_display_generation_job")
                .query(Integer.class).single()).isEqualTo(7);
    }

    @Test
    void 같은_재개명령을_반복해도_예산을_중복으로_늘리지않는다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        retry(job.key());
        assertThatThrownBy(() -> retry(job.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_JOB_NOT_FAILED"));
        assertThat(jdbc.sql("SELECT attempt_limit FROM route_display_generation_job")
                .query(Integer.class).single()).isEqualTo(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING","RUNNING","COMPLETED","SUPERSEDED"})
    void 실패상태가_아닌_작업과_활성소유자의_작업은_재개하지않는다(String state) {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        if (state.equals("RUNNING")) jdbc.sql("""
                UPDATE route_display_generation_job SET status='RUNNING',failed_at=NULL,lease_token=?,
                    lease_until=clock_timestamp()+interval '5 minutes' WHERE generation_key=?
                """).params(UUID.randomUUID(),job.key()).update();
        else jdbc.sql("UPDATE route_display_generation_job SET status=?,failed_at=NULL WHERE generation_key=?")
                .params(state,job.key()).update();
        assertThatThrownBy(() -> retry(job.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_JOB_NOT_FAILED"));
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job")
                .query(String.class).single()).isEqualTo(state);
        assertThat(jdbc.sql("SELECT attempt_limit FROM route_display_generation_job")
                .query(Integer.class).single()).isEqualTo(5);
    }

    @Test
    void 정류장좌표가_바뀐_과거입력의_실패작업은_재개하지않는다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        jdbc.sql("UPDATE route_stop_location_history SET x=x+0.0001 WHERE stop_order=2").update();
        assertThatThrownBy(() -> retry(job.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_SOURCE_CHANGED"));
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job")
                .query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void 다른노선의_작업키로_재시도할수없다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        RouteDisplayDbSamples.seed(jdbc,"204000081");
        assertThatThrownBy(() -> retry("204000081",job.key()))
                .isInstanceOfSatisfying(MaintenanceException.class,e ->
                        assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_JOB_NOT_FOUND"));
        assertThat(jdbc.sql("SELECT status FROM route_display_generation_job")
                .query(String.class).single()).isEqualTo("FAILED");
    }

    @Test
    void 병렬_재시도는_예산을_한번만_부여한다() throws Exception {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        var gate = new CountDownLatch(1);
        try(var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> retryAfterGate(gate,job.key()));
            var second = executor.submit(() -> retryAfterGate(gate,job.key()));
            gate.countDown();
            assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.sql("SELECT attempt_limit FROM route_display_generation_job")
                .query(Integer.class).single()).isEqualTo(10);
    }

    private boolean retryAfterGate(CountDownLatch gate, String key) throws Exception {
        gate.await();
        try { retry(key);return true; }
        catch(MaintenanceException conflict) {
            assertThat(conflict.code()).isEqualTo("ROUTE_DISPLAY_JOB_NOT_FAILED");
            return false;
        }
    }

    @Test
    void 보조근거가_바뀐_과거작업은_재개하지않는다() {
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        var changed = new RouteShapeEvidence("GBIS_API", List.of(new RouteShapeEvidence.Source(
                "GBIS_WEB","UNAVAILABLE",null)),List.of());
        jdbc.sql("UPDATE route_geometry SET evidence=CAST(? AS jsonb)")
                .param(JSON.writeValueAsString(changed)).update();
        assertThatThrownBy(() -> retry(job.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_SOURCE_CHANGED"));
    }

    @Test
    void 폐기된_알고리즘과_손상된_불변입력은_재개하지않는다() {
        var old = RouteDisplayRetryFixtures.failed(jdbc, RouteDisplayRetryFixtures.geometry(jdbc),
                "automatic-quality-v1",5,5,null);
        assertThatThrownBy(() -> retry(old.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_ALGORITHM_RETIRED"));
        var invalid = RouteDisplayRetryFixtures.failed(jdbc, RouteDisplayRetryFixtures.geometry(jdbc),
                AutomaticRouteDisplay.REVISION,5,5,"{}");
        assertThatThrownBy(() -> retry(invalid.key())).isInstanceOfSatisfying(MaintenanceException.class,
                e -> assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_JOB_INVALID"));
    }

    @Test
    void 정상원문_영수증만_갱신한_동일입력은_재개할수있다() {
        var original = RouteDisplayRetryFixtures.geometry(jdbc);
        String first = provenance(original,"CAPTURE_A");
        jdbc.sql("UPDATE route_geometry SET source_manifest_id=?").param(first).update();
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        String next = provenance(RouteDisplayRetryFixtures.geometry(jdbc),"CAPTURE_B");
        assertThat(next).isNotEqualTo(first);
        jdbc.sql("UPDATE route_geometry SET source_manifest_id=?").param(next).update();
        assertThat(retry(job.key()).toString()).contains("PENDING");
    }

    @Test
    void 판본번호만_바뀌고_정규입력과_근거가_같으면_과거의_유효영수증_작업을_재개한다() {
        var original = RouteDisplayRetryFixtures.geometry(jdbc);
        jdbc.sql("UPDATE route_geometry SET source_manifest_id=?")
                .param(provenance(original,"CAPTURE_A")).update();
        var job = RouteDisplayRetryFixtures.failed(jdbc);
        jdbc.sql("UPDATE route_version SET valid_to=clock_timestamp() WHERE id=?")
                .param(original.referenceVersionId()).update();
        long next = jdbc.sql("""
                INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from)
                SELECT route_id,turn_sequence,?,clock_timestamp() FROM route_version WHERE id=? RETURNING id
                """).params("c".repeat(64),original.referenceVersionId()).query(Long.class).single();
        jdbc.sql("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                SELECT ?,stop_order,stop_id,name,direction,boarding_allowed FROM route_stop WHERE route_version_id=?
                """).params(next,original.referenceVersionId()).update();
        jdbc.sql("""
                INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y)
                SELECT ?,stop_order,stop_id,observed_at,x,y FROM route_stop_location_history WHERE route_version_id=?
                """).params(next,original.referenceVersionId()).update();
        jdbc.sql("""
                INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points,evidence)
                SELECT ?,?,clock_timestamp(),content_digest,points,evidence FROM route_geometry WHERE route_version_id=?
                """).params(UUID.randomUUID(),next,original.referenceVersionId()).update();
        var refreshed = RouteDisplayRetryFixtures.geometry(jdbc);
        String receipt = provenance(refreshed,"CAPTURE_B");
        jdbc.sql("UPDATE route_geometry SET source_manifest_id=? WHERE route_version_id=?")
                .params(receipt,next).update();
        assertThat(retry(job.key()).toString()).contains("PENDING");
    }

    private String provenance(com.gustler.backend.routegeometry.engine.api.RouteGeometry g, String purpose) {
        String acquisition = JSON.writeValueAsString(Map.of("format","route-source-manifest-v1",
                "purpose",purpose,"receiptIds",List.of(),"reusedManifestIds",List.of()));
        String acquisitionId = Sha256.of(acquisition);
        jdbc.sql("INSERT INTO route_source_manifest(manifest_id,purpose,document) VALUES (?,?,?) ON CONFLICT DO NOTHING")
                .params(acquisitionId,purpose,acquisition).update();
        long route = jdbc.sql("SELECT route_id FROM route_version WHERE id=?")
                .param(g.referenceVersionId()).query(Long.class).single();
        var input = RouteDisplayGeneration.input(g);
        String document = JSON.writeValueAsString(new RouteInputProvenance("route-input-manifest-v1",route,
                g.referenceVersionId(),acquisitionId,input.digest(),g.shape().evidence().revision(),input,g.shape().evidence()));
        String id = Sha256.of(document);
        jdbc.sql("""
                INSERT INTO route_input_manifest(manifest_id,route_id,reference_version_id,input_digest,
                    evidence_revision,acquisition_manifest_id,document) VALUES (?,?,?,?,?,?,?)
                """).params(id,route,g.referenceVersionId(),input.digest(),g.shape().evidence().revision(),acquisitionId,document).update();
        return id;
    }

    @Test
    void 재시도_CLI가_필수키와_키형식을_DB접속전에_검사한다() {
        assertThatThrownBy(() -> MaintenanceApplication.run(CliArguments.parse(new String[]{
                "route-display-retry","--route-id","204000057","--config","unused"})))
                .isInstanceOfSatisfying(MaintenanceException.class,e ->
                        assertThat(e.code()).isEqualTo("MISSING_ARGUMENT_GENERATION_KEY"));
        assertThatThrownBy(() -> MaintenanceApplication.run(CliArguments.parse(new String[]{
                "route-display-retry","--route-id","204000057","--generation-key","invalid","--config","unused"})))
                .isInstanceOfSatisfying(MaintenanceException.class,e ->
                        assertThat(e.code()).isEqualTo("ROUTE_DISPLAY_GENERATION_KEY_INVALID"));
    }

    @Test
    void 원격_재시도_승인은_명령_노선_작업키_다섯번예산에_결박한다() throws Exception {
        String key="a".repeat(64);
        Path receipt=directory.resolve("retry-approval.json");
        Files.writeString(receipt,CanonicalJson.stringOf(Map.of("schemaVersion","salmonbus-migration-approval-v1",
                "action",RouteDisplayRetryCommand.APPROVAL_ACTION,"artifactSha256",
                RouteDisplayRetryCommand.approvalFingerprint("204000057",key),
                "databaseIdentitySha256","d".repeat(64),"expiresAt",RouteDisplayRetryFixtures.TIME.plusSeconds(60).toString())));
        Files.setPosixFilePermissions(receipt,Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE));
        var args=CliArguments.parse(new String[]{"route-display-retry","--approval",receipt.toString()});
        var env=new DatabaseEnvironment(DatabaseEnvironment.TargetKind.ACADEMY,"unused","unused","unused","d".repeat(64));
        var clock=Clock.fixed(RouteDisplayRetryFixtures.TIME,ZoneOffset.UTC);
        assertThatCode(() -> RouteDisplayRetryCommand.requireApproval(args,env,"204000057",key,clock)).doesNotThrowAnyException();
        for(String[] changed:List.of(new String[]{"204000058",key},new String[]{"204000057","b".repeat(64)})) {
            assertThatThrownBy(() -> RouteDisplayRetryCommand.requireApproval(args,env,changed[0],changed[1],clock))
                    .isInstanceOfSatisfying(MaintenanceException.class,e ->
                            assertThat(e.code()).isEqualTo("APPROVAL_ARTIFACT_MISMATCH"));
        }
        assertThat(RouteDisplayRetryCommand.approvalFingerprint("204000057",key))
                .isEqualTo(Sha256.of("route-display-retry\n204000057\n"+key+"\n5"));
        assertThatCode(() -> RouteDisplayRetryCommand.requireApproval(CliArguments.parse(new String[]{"route-display-retry"}),
                new DatabaseEnvironment(DatabaseEnvironment.TargetKind.LOCAL,"unused","unused","unused","unused"),
                "204000057",key,clock)).doesNotThrowAnyException();
    }
}
