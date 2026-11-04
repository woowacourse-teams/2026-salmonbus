package com.gustler.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.arrival.support.ArrivalRuntime;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteDisplaySamples;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Boot JARs, their real scheduler wiring, and the HTTP reader share only PostgreSQL. */
class RouteDisplayGenerationRuntimeTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final String ROUTE="204000057";
    private static final Map<String,String> WORKER=Map.of("SPRING_APPLICATION_JSON", """
        {"logging":{"level":{"com.gustler.backend.worker.routegeometry":"INFO"}},"collection":{"enabled":false},"forecast":{"enabled":false},"arrivals":{"enabled":false},
         "route-geometry":{"enabled":false,"generation":{"enabled":true,"poll-delay-ms":100}}}
        """);

    @Test @Timeout(120)
    void separate_worker_generates_persisted_geometry_and_API_switches_current_manual_and_automatic_versions() throws Exception {
        lifecycle(false);
    }

    @Test @Timeout(900)
    @EnabledIfSystemProperty(named="route.display.live-browser",matches="true")
    void live_browser_observes_each_real_HTTP_transition() throws Exception {
        lifecycle(true);
    }

    @Test @Timeout(900)
    @EnabledIfSystemProperty(named="route.display.live-browser",matches="true")
    void live_browser_observes_worker_source_and_maintenance_imports() throws Exception {
        lifecycle(true,true);
    }

    @Test @Timeout(90)
    void engine_generated_source_only_artifact_is_served_by_real_HTTP() throws Exception {
        try(var runtime=new ArrivalRuntime(List.of(),Map.of("SPRING_APPLICATION_JSON",
                "{\"arrivals\":{\"enabled\":false},\"route-geometry\":{\"presentation\":{\"enabled\":true}}}"))) {
            seed(runtime);
            installSourceOnly(runtime);
            URI mapUri=runtime.arrivalsUri().resolve("/api/v2/routes/"+ROUTE+"/geometry");
            URI uri=URI.create(mapUri+"?includeDiagnostics=true");
            var response=get(uri);
            assertThat(body(response).at("/displayQuality/status").asString()).isEqualTo("SOURCE_ONLY");
            assertThat(body(response).path("displaySource").asString()).isEqualTo("AUTOMATIC_FALLBACK");
            assertThat(body(response).hasNonNull("displayPaths")).isTrue();
            assertThat(body(response).at("/busStops/0/pathConnection/segmentIndex").asInt()).isZero();
            assertThat(count(runtime,"route_display_automatic_artifact")).isEqualTo(1);
            assertCompact(mapUri,response);
            assertRepresentationCaching(mapUri,uri);
            capture(Path.of("build/reports/route-display-generation-http"),"source-only",response,"LOCAL_ENGINE_FIXTURE_REAL_HTTP");
        }
    }

    @Test @Timeout(120)
    void terminal_failed_jobs_are_distinct_from_pending_and_real_maintenance_restores_source_fallback() throws Exception {
        try (var runtime = new ArrivalRuntime(List.of(), Map.of("SPRING_APPLICATION_JSON",
                "{\"arrivals\":{\"enabled\":false},\"route-geometry\":{\"presentation\":{\"enabled\":true}}}"))) {
            seed(runtime);
            URI mapUri = runtime.arrivalsUri().resolve("/api/v2/routes/" + ROUTE + "/geometry");
            URI diagnosticUri = URI.create(mapUri + "?includeDiagnostics=true");
            var pending = get(mapUri);
            assertThat(body(pending).path("reason").asString()).isEqualTo("GENERATION_PENDING");
            var geometry = seededGeometry(runtime);
            var input = RouteDisplayGeneration.input(geometry);
            String evidence = geometry.shape().evidence().revision();
            String sourceKey = RouteDisplayGeneration.key(input.digest(), evidence, RouteDisplayGeneration.SOURCE_REVISION);
            for (String algorithm : List.of(RouteDisplayGeneration.SOURCE_REVISION, AutomaticRouteDisplay.REVISION)) {
                String key = RouteDisplayGeneration.key(input.digest(), evidence, algorithm);
                try (var connection = runtime.connection(); var insert = connection.prepareStatement("""
                    INSERT INTO route_display_generation_job(generation_key,route_id,input_digest,evidence_revision,
                        algorithm_revision,input_json,status,attempts,attempt_limit,last_failure_code,last_failure_at,failed_at)
                    VALUES (?,?,?,?,?,CAST(? AS jsonb),'FAILED',5,5,'ATTEMPTS_EXHAUSTED',clock_timestamp(),clock_timestamp())
                    """)) {
                    insert.setString(1, key); insert.setString(2, ROUTE); insert.setString(3, input.digest());
                    insert.setString(4, evidence); insert.setString(5, algorithm); insert.setString(6, JSON.writeValueAsString(geometry));
                    insert.executeUpdate();
                }
            }
            var failed = get(mapUri);
            assertThat(body(failed).path("reason").asString()).isEqualTo("DISPLAY_WITHHELD");
            assertThat(body(failed).path("geometryRevision")).isNotEqualTo(body(pending).path("geometryRevision"));
            assertCompact(mapUri, get(diagnosticUri));
            var retry = runtime.retryDisplayGeneration(ROUTE, sourceKey);
            assertThat(retry.at("/result/status").asString()).isEqualTo("PENDING");
            assertThat(retry.at("/result/attempts").asInt()).isEqualTo(5);
            assertThat(retry.at("/result/attemptLimit").asInt()).isEqualTo(10);
            assertThat(body(get(mapUri)).path("reason").asString()).isEqualTo("GENERATION_PENDING");
            runtime.startWorker(false, WORKER);
            var available = await(mapUri, b -> "READY".equals(b.path("status").asString()));
            assertThat(body(available).path("paths").isNull()).isFalse();
            var sourceOnly = get(diagnosticUri);
            assertThat(body(sourceOnly).at("/displayQuality/status").asString()).isEqualTo("SOURCE_ONLY");
            assertCompact(mapUri, sourceOnly);
            try (var connection = runtime.connection(); var query = connection.createStatement(); var result = query.executeQuery(
                    "SELECT status,attempts,attempt_limit FROM route_display_generation_job WHERE generation_key='" + sourceKey + "'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo("COMPLETED");
                assertThat(result.getInt(2)).isEqualTo(6);
                assertThat(result.getInt(3)).isEqualTo(10);
            }
            assertThat(count(runtime, "route_display_automatic_artifact")).isEqualTo(1);
            capture(Path.of("build/reports/route-display-generation-http"), "terminal-failed", failed, "LOCAL_FAILED_JOB_FIXTURE_REAL_HTTP");
            capture(Path.of("build/reports/route-display-generation-http"), "retried-source", available, "LOCAL_MAINTENANCE_RETRY_WORKER_REAL_HTTP");
        }
    }

    private static void lifecycle(boolean live) throws Exception { lifecycle(live,false); }
    private static void lifecycle(boolean live,boolean realMaintenance) throws Exception {
        try(var runtime=new ArrivalRuntime(List.of(),Map.of("SPRING_APPLICATION_JSON", """
            {"arrivals":{"enabled":false},"route-geometry":{"presentation":{"enabled":true}}}
            """))) {
            seed(runtime);
            URI mapUri=runtime.arrivalsUri().resolve("/api/v2/routes/"+ROUTE+"/geometry");
            URI uri=URI.create(mapUri+"?includeDiagnostics=true");
            var pending=get(uri);
            assertThat(body(pending).at("/displayQuality/status").asString()).isEqualTo("WITHHELD");
            assertThat(body(pending).at("/displayQuality/issues/0/code").asString()).isEqualTo("GENERATION_PENDING");
            assertThat(count(runtime,"route_display_generation_job")).as("API never queues or generates on GET").isZero();
            assertCompact(mapUri,pending);

            observe(runtime,live,"pending",uri,pending,"LOCAL_SYNTHETIC_REAL_HTTP");
            if(realMaintenance) {
                holdFullGeneration(runtime);
                runtime.startWorker(false,WORKER);
                var sourceOnly=await(uri,b -> "SOURCE_ONLY".equals(b.at("/displayQuality/status").asString()));
                assertCompact(mapUri,sourceOnly);
                observe(runtime,true,"source-only",uri,sourceOnly,"LOCAL_WORKER_GENERATED_REAL_HTTP");
                releaseFullGeneration(runtime);
            } else {
                if(live) {
                    installSourceOnly(runtime);
                    var sourceOnly=get(uri);
                    assertThat(body(sourceOnly).at("/displayQuality/status").asString()).isEqualTo("SOURCE_ONLY");
                    assertCompact(mapUri,sourceOnly);
                    observe(runtime,true,"source-only",uri,sourceOnly,"LOCAL_ENGINE_FIXTURE_REAL_HTTP");
                }
                runtime.startWorker(false,WORKER);
            }
            var automaticA=await(uri,b -> "AUTOMATIC_FALLBACK".equals(b.path("displaySource").asString())
                && "PASSED".equals(b.at("/displayQuality/status").asString()));
            assertThat(body(automaticA).at("/busStops/0/pathConnection/segmentIndex").asInt()).isZero();
            assertThat(body(automaticA).path("displayAccuracyGrade").asString()).isEqualTo("UNEVALUATED");
            assertThat(count(runtime,"route_display_automatic_artifact")).isEqualTo(2);
            String automaticEtag=automaticA.headers().firstValue("ETag").orElseThrow();
            assertCompact(mapUri,automaticA);
            assertRepresentationCaching(mapUri,uri);
            observe(runtime,live,"automatic-a",uri,automaticA,"LOCAL_SYNTHETIC_REAL_HTTP");

            // The API still serves the generated result after the generator process exits.
            runtime.stopWorker();
            assertThat(get(uri).headers().firstValue("ETag")).contains(automaticEtag);
            installManual(runtime,RouteDisplaySamples.input(),0.0001,realMaintenance,"manual-a");
            var manualA=await(uri,b -> "MANUAL_REVIEWED".equals(b.path("displaySource").asString()));
            assertThat(body(manualA).at("/busStops/0/displayPoint/x").asDouble()).isEqualTo(127.0001);
            assertThat(manualA.headers().firstValue("ETag")).isNotEqualTo(automaticA.headers().firstValue("ETag"));
            assertCompact(mapUri,manualA);

            observe(runtime,live,"manual-a",uri,manualA,realMaintenance ? "LOCAL_MAINTENANCE_IMPORT_REAL_HTTP" : "LOCAL_MANUAL_FIXTURE_SQL_REAL_HTTP");
            // A new source invalidates both old manual and automatic data while the worker is stopped.
            try(var c=runtime.connection();var s=c.createStatement()) {
                s.executeUpdate("UPDATE route_stop_location_history SET x=127.0101 WHERE stop_order=2");
            }
            var changed=get(uri);
            assertThat(body(changed).path("displaySource").asString()).isEqualTo("AUTOMATIC_FALLBACK");
            assertThat(body(changed).at("/displayQuality/status").asString()).isEqualTo("WITHHELD");
            assertThat(body(changed).hasNonNull("displayPaths")).isFalse();
            assertThat(body(changed).at("/busStops/1/point/x").asDouble()).isEqualTo(127.0101);
            assertCompact(mapUri,changed);
            observe(runtime,live,"changed",uri,changed,"LOCAL_SYNTHETIC_REAL_HTTP");

            runtime.startWorker(false,WORKER);
            var automaticB=await(uri,b -> "AUTOMATIC_FALLBACK".equals(b.path("displaySource").asString())
                && "PASSED".equals(b.at("/displayQuality/status").asString()));
            assertThat(automaticB.headers().firstValue("ETag")).isNotEqualTo(automaticA.headers().firstValue("ETag"));
            assertThat(count(runtime,"route_display_automatic_artifact")).isEqualTo(4);
            assertCompact(mapUri,automaticB);
            observe(runtime,live,"automatic-b",uri,automaticB,"LOCAL_SYNTHETIC_REAL_HTTP");
            var a=RouteDisplaySamples.input();
            var visits=a.visits().stream().map(v -> v.sequence()==2
                ? new RouteDisplayInput.Visit(v.stationId(),v.sequence(),v.direction(),v.boardingAllowed(),
                    new RouteDisplayInput.Point(127.0101,37.01)) : v).toList();
            var inputB=new RouteDisplayInput(a.routeId(),a.turnSequence(),a.points(),visits);
            installManual(runtime,inputB,0.0002,realMaintenance,"manual-b");
            var manualB=await(uri,b -> "MANUAL_REVIEWED".equals(b.path("displaySource").asString()));
            assertThat(body(manualB).at("/busStops/0/displayPoint/x").asDouble()).isEqualTo(127.0002);
            assertThat(manualB.headers().firstValue("ETag")).isNotEqualTo(manualA.headers().firstValue("ETag"));
            assertCompact(mapUri,manualB);
            assertThat(count(runtime,"route_display_generation_job")).isEqualTo(4);
            observe(runtime,live,"manual-b",uri,manualB,realMaintenance ? "LOCAL_MAINTENANCE_IMPORT_REAL_HTTP" : "LOCAL_MANUAL_FIXTURE_SQL_REAL_HTTP");
            assertThat(runtime.source.unexpected()).isEmpty();
            assertThat(runtime.source.geometryCalls()).isZero();
            assertThat(runtime.source.locationCalls()).isZero();
            assertThat(runtime.routeReservations()).isZero();
            var captures=java.util.Map.of("pending",pending,"automatic-a",automaticA,"manual-a",manualA,
                "changed",changed,"automatic-b",automaticB,"manual-b",manualB);
            var captureDir=java.nio.file.Path.of("build/reports/route-display-generation-http");
            java.nio.file.Files.createDirectories(captureDir);
            for(var entry:captures.entrySet())
                capture(captureDir,entry.getKey(),entry.getValue(),"LOCAL_SYNTHETIC_REAL_HTTP");
            var events=new java.util.ArrayList<String>();
            for(int workerIndex=1;workerIndex<=2;workerIndex++) {
                events.addAll(java.nio.file.Files.readAllLines(java.nio.file.Path.of("build/reports/arrival-runtime-logs/worker-"+workerIndex+".log"))
                    .stream().filter(line -> line.contains("event=route_display_")).toList());
            }
            assertThat(events).anyMatch(line -> line.contains("event=route_display_generation_started") && line.contains("ageAtClaimMs="));
            assertThat(events).anyMatch(line -> line.contains("event=route_display_generation_completed") && line.contains("generationMs="));
            assertThat(events).anyMatch(line -> line.contains("event=route_display_publication") && line.contains("outcome=PUBLISHED") && line.contains("publicationMs="));
            java.nio.file.Files.write(java.nio.file.Path.of("build/reports/route-display-generation-runtime.log"),events);
            System.out.println("route_display_runtime separate_boot_jars=true generated_artifacts=4 manual_versions=2 live_provider_calls=0");
        }
    }

    private static void observe(ArrivalRuntime runtime,boolean live,String phase,URI uri,HttpResponse<String> response,String origin) throws Exception {
        if(!live) return;
        Path dir=Path.of(System.getProperty("route.display.live-browser-dir")).toAbsolutePath();
        Files.createDirectories(dir);
        Path ack=dir.resolve(phase+".ack");
        Files.deleteIfExists(ack);
        capture(dir,phase,response,origin);
        var metadata=JSON.createObjectNode();
        metadata.put("phase",phase);metadata.put("geometryUri",uri.toString());
        metadata.put("apiOrigin",uri.resolve("/").toString());metadata.put("routeId",ROUTE);
        metadata.put("etag",response.headers().firstValue("ETag").orElseThrow());
        metadata.put("httpStatus",response.statusCode());metadata.put("origin",origin);
        metadata.put("ackPath",ack.toString());metadata.put("deadlineSeconds",120);
        metadata.set("databaseSnapshot",databaseSnapshot(runtime));
        Files.writeString(dir.resolve(phase+"-state.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        Files.writeString(dir.resolve("phase.tmp"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        Files.move(dir.resolve("phase.tmp"),dir.resolve("phase.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        System.out.println("route_display_live_phase="+phase+" metadata="+dir.resolve("phase.json"));
        ArrivalRuntime.await(() -> Files.exists(ack),Duration.ofSeconds(120),"Browser acknowledgement for "+phase);
    }
    private static JsonNode databaseSnapshot(ArrivalRuntime runtime) throws Exception {
        var result=JSON.createObjectNode();
        result.put("consistency","REPEATABLE_READ_AFTER_HTTP_NOT_SAME_HTTP_TRANSACTION");
        try(var c=runtime.connection()) {
            c.setAutoCommit(false);c.setReadOnly(true);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT transaction_timestamp()::text")) {
                rows.next();result.put("databaseTime",rows.getString(1));
            }
            var jobs=result.putArray("jobs");
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT generation_key,input_digest,evidence_revision,algorithm_revision,status,source_manifest_id,attempts FROM route_display_generation_job ORDER BY generation_key")) {
                while(rows.next()) { var row=jobs.addObject();row.put("generationKey",rows.getString(1));row.put("inputDigest",rows.getString(2));
                    row.put("evidenceRevision",rows.getString(3));row.put("algorithmRevision",rows.getString(4));row.put("status",rows.getString(5));
                    row.put("sourceManifestId",rows.getString(6));row.put("attempts",rows.getInt(7)); }
            }
            var artifacts=result.putArray("artifacts");
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT generation_key,input_digest,algorithm_revision,document_sha256,source_manifest_id FROM route_display_automatic_artifact ORDER BY generation_key")) {
                while(rows.next()) { var row=artifacts.addObject();row.put("generationKey",rows.getString(1));row.put("inputDigest",rows.getString(2));
                    row.put("algorithmRevision",rows.getString(3));row.put("documentSha256",rows.getString(4));row.put("sourceManifestId",rows.getString(5)); }
            }
            var manuals=result.putArray("manuals");
            try(var s=c.createStatement();var rows=s.executeQuery("SELECT input_digest,content_digest,registered_at::text,disabled_at IS NULL FROM route_display_geometry ORDER BY id")) {
                while(rows.next()) { var row=manuals.addObject();row.put("inputDigest",rows.getString(1));row.put("contentDigest",rows.getString(2));
                    row.put("registeredAt",rows.getString(3));row.put("enabled",rows.getBoolean(4)); }
            }
            result.put("jobCount",jobs.size());result.put("artifactCount",artifacts.size());result.put("manualCount",manuals.size());
            c.commit();
        }
        return result;
    }
    private static void capture(Path directory,String name,HttpResponse<String> response,String origin) throws Exception {
        Files.createDirectories(directory);
        var envelope=JSON.createObjectNode();envelope.put("origin",origin);
        envelope.put("httpStatus",response.statusCode());envelope.put("etag",response.headers().firstValue("ETag").orElse(null));
        envelope.put("bodyUtf8",response.body());envelope.set("body",body(response));
        Files.writeString(directory.resolve(name+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(envelope));
    }
    private static RouteGeometry seededGeometry(ArrivalRuntime runtime) throws Exception {
        var input=RouteDisplaySamples.input();
        try(var c=runtime.connection()) {
            long version;UUID shapeId;
            try(var query=c.createStatement();var rows=query.executeQuery("SELECT route_version_id,id FROM route_geometry LIMIT 1")) {
                rows.next();version=rows.getLong(1);shapeId=rows.getObject(2,UUID.class);
            }
            var geometry=new RouteGeometry(ROUTE,version,"3330","A","B",input.turnSequence(),
                input.visits().stream().map(v -> new RouteGeometry.Stop(v.stationId(),v.sequence(),"stop "+v.sequence(),v.direction(),
                    v.boardingAllowed(),new RouteGeometry.Point(v.sourcePoint().x(),v.sourcePoint().y()))).toList(),
                new RouteGeometry.Shape(shapeId,Instant.parse("2026-10-14T00:00:00Z"),
                    input.points().stream().map(p -> new RouteGeometry.Point(p.x(),p.y())).toList(),RouteShapeEvidence.EMPTY));
            return RouteDisplayGeneration.at(geometry,Instant.now());
        }
    }
    private static void installSourceOnly(ArrivalRuntime runtime) throws Exception {
        var input=RouteDisplaySamples.input();
        var geometry=seededGeometry(runtime);
        try(var c=runtime.connection()) {
            String evidence=geometry.shape().evidence().revision(),algorithm=RouteDisplayGeneration.SOURCE_REVISION;
            String key=RouteDisplayGeneration.key(input.digest(),evidence,algorithm);
            String document=JSON.writeValueAsString(AutomaticRouteDisplay.sourceOnly(geometry,input.digest()));
            try(var insert=c.prepareStatement("INSERT INTO route_display_generation_job(generation_key,route_id,input_digest,evidence_revision,algorithm_revision,input_json,status) VALUES (?,?,?,?,?,CAST(? AS jsonb),'COMPLETED')")) {
                insert.setString(1,key);insert.setString(2,ROUTE);insert.setString(3,input.digest());insert.setString(4,evidence);
                insert.setString(5,algorithm);insert.setString(6,JSON.writeValueAsString(geometry));insert.executeUpdate();
            }
            try(var insert=c.prepareStatement("INSERT INTO route_display_automatic_artifact(generation_key,route_id,input_digest,evidence_revision,algorithm_revision,document,document_sha256) VALUES (?,?,?,?,?,?,?)")) {
                insert.setString(1,key);insert.setString(2,ROUTE);insert.setString(3,input.digest());insert.setString(4,evidence);
                insert.setString(5,algorithm);insert.setString(6,document);insert.setString(7,RouteDisplayGeneration.sha256(document));insert.executeUpdate();
            }
        }
    }
    private static void seed(ArrivalRuntime runtime) throws Exception {
        try(Connection c=runtime.connection();var statement=c.createStatement()) {
            statement.executeUpdate("TRUNCATE route CASCADE");
            long route;
            try(var insert=c.prepareStatement("INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES (?,'GBIS',?,'3330','A','B') RETURNING id")) {
                insert.setString(1,ROUTE);insert.setString(2,ROUTE);
                try(var rows=insert.executeQuery()) { rows.next();route=rows.getLong(1); }
            }
            long version;
            try(var insert=c.prepareStatement("INSERT INTO route_version(route_id,turn_sequence,content_digest,valid_from) VALUES (?,2,?,'2026-10-14T00:00:00Z') RETURNING id")) {
                insert.setLong(1,route);insert.setString(2,"a".repeat(64));
                try(var rows=insert.executeQuery()) { rows.next();version=rows.getLong(1); }
            }
            try(var insert=c.prepareStatement("INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES (?,?,?,?,?,true)")) {
                for(var visit:RouteDisplaySamples.input().visits()) {
                    insert.setLong(1,version);insert.setInt(2,visit.sequence());insert.setString(3,visit.stationId());
                    insert.setString(4,"stop "+visit.sequence());insert.setString(5,visit.direction());insert.addBatch();
                }
                insert.executeBatch();
            }
            try(var insert=c.prepareStatement("INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y) VALUES (?,?,?,'2026-10-14T00:00:00Z',?,?)")) {
                for(var visit:RouteDisplaySamples.input().visits()) {
                    insert.setLong(1,version);insert.setInt(2,visit.sequence());insert.setString(3,visit.stationId());
                    insert.setDouble(4,visit.sourcePoint().x());insert.setDouble(5,visit.sourcePoint().y());insert.addBatch();
                }
                insert.executeBatch();
            }
            try(var insert=c.prepareStatement("INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points) VALUES (?,?,'2026-10-14T00:00:00Z',?,CAST(? AS jsonb))")) {
                insert.setObject(1,UUID.randomUUID());insert.setLong(2,version);insert.setString(3,"b".repeat(64));
                insert.setString(4,JSON.writeValueAsString(RouteDisplaySamples.input().points()));insert.executeUpdate();
            }
        }
    }
    private static void holdFullGeneration(ArrivalRuntime runtime) throws Exception {
        try(var c=runtime.connection();var s=c.createStatement()) {
            s.execute("CREATE FUNCTION runtime_hold_full_generation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.algorithm_revision NOT LIKE '%/source-only' THEN NEW.next_attempt_at=clock_timestamp()+interval '1 day'; END IF; RETURN NEW; END; $$");
            s.execute("CREATE TRIGGER runtime_hold_full_generation BEFORE INSERT ON route_display_generation_job FOR EACH ROW EXECUTE FUNCTION runtime_hold_full_generation()");
        }
    }
    private static void releaseFullGeneration(ArrivalRuntime runtime) throws Exception {
        try(var c=runtime.connection();var s=c.createStatement()) {
            s.execute("DROP TRIGGER runtime_hold_full_generation ON route_display_generation_job");
            s.execute("DROP FUNCTION runtime_hold_full_generation()");
            s.executeUpdate("UPDATE route_display_generation_job SET next_attempt_at=clock_timestamp() WHERE status='PENDING'");
        }
    }
    private static void installManual(ArrivalRuntime runtime,RouteDisplayInput input,double offset,boolean realMaintenance,String phase) throws Exception {
        if(!realMaintenance) { installManual(runtime,input,offset);return; }
        var manual=RouteDisplaySamples.manual(input,offset);manual.validateFor(input);
        Path dir=Path.of(System.getProperty("route.display.live-browser-dir"));Files.createDirectories(dir);
        Path artifact=dir.resolve(phase+"-reviewed-fixture.json");
        Files.writeString(artifact,JSON.writeValueAsString(manual));
        var reply=runtime.importReviewedDisplay(artifact);
        assertThat(reply.at("/result/source").asString()).isEqualTo("MANUAL_REVIEWED");
        assertThat(reply.at("/result/inputDigest").asString()).isEqualTo(input.digest());
        Files.writeString(dir.resolve(phase+"-maintenance-result.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(reply));
    }
    private static void installManual(ArrivalRuntime runtime,RouteDisplayInput input,double offset) throws Exception {
        var manual=RouteDisplaySamples.manual(input,offset);manual.validateFor(input);
        String document=JSON.writeValueAsString(manual);
        String sha=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.getBytes(StandardCharsets.UTF_8)));
        try(var c=runtime.connection();var insert=c.prepareStatement("INSERT INTO route_display_geometry(route_id,input_digest,content_digest,document) SELECT id,?,?,CAST(? AS jsonb) FROM route WHERE public_route_id=?")) {
            insert.setString(1,input.digest());insert.setString(2,sha);insert.setString(3,document);insert.setString(4,ROUTE);
            insert.executeUpdate();
        }
    }
    private static int count(ArrivalRuntime runtime,String table) throws Exception {
        try(var c=runtime.connection();var s=c.createStatement();var rows=s.executeQuery("SELECT count(*) FROM "+table)) {
            rows.next();return rows.getInt(1);
        }
    }
    /** The normal client consumes the original shape plus one optional visit connection. */
    private static void assertCompact(URI mapUri,HttpResponse<String> diagnosticResponse) throws Exception {
        var response=get(mapUri);var compact=body(response);var diagnostic=body(diagnosticResponse);
        var fields=List.of("routeId","referenceVersionId","geometryRevision","name","startPoint","endPoint",
                "status","reason","coordinateSystem","turningPoint","busStops","points","paths","boundary");
        assertThat(compact.size()).isEqualTo(fields.size());
        fields.forEach(field -> assertThat(compact.has(field)).as("compact root: "+field).isTrue());
        assertThat(compact.path("geometryRevision")).isNotEqualTo(diagnostic.path("geometryRevision"));
        boolean withheld="WITHHELD".equals(diagnostic.at("/displayQuality/status").asString());
        if(withheld) {
            assertThat(compact.path("status").asString()).isEqualTo("UNAVAILABLE");
            boolean generating = false;
            for (var issue : diagnostic.at("/displayQuality/issues"))
                if ("GENERATION_PENDING".equals(issue.path("code").asString())) generating = true;
            assertThat(compact.path("reason").asString()).isEqualTo(generating ? "GENERATION_PENDING" : "DISPLAY_WITHHELD");
            assertThat(compact.path("points")).isEmpty();
            assertThat(compact.path("paths").isNull()).isTrue();
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.headers().firstValue("ETag")).isEmpty();
        } else {
            assertThat(compact.path("points")).isEqualTo(diagnostic.path("points"));
            if(diagnostic.hasNonNull("displayPaths"))
                assertThat(compact.path("paths")).isEqualTo(diagnostic.path("displayPaths"));
            else assertThat(compact.path("paths").isNull()).isTrue();
        }
        assertThat(compact.path("busStops")).hasSize(diagnostic.path("busStops").size());
        var stopFields=List.of("stationId","stopSequence","name","direction","boardingAllowed","point");
        for(int i=0;i<compact.path("busStops").size();i++) {
            var stop=compact.path("busStops").get(i);var original=diagnostic.path("busStops").get(i);
            stopFields.forEach(field -> assertThat(stop.path(field)).as("original stop field: "+field).isEqualTo(original.path(field)));
            assertThat(stop.has("displayPoint")).isFalse();
            assertThat(stop.size()).isEqualTo(stopFields.size()+(stop.has("pathConnection")?1:0));
            if(withheld) assertThat(stop.has("pathConnection")).isFalse();
            else if(original.has("pathConnection")) {
                assertThat(stop.path("pathConnection")).isEqualTo(original.path("pathConnection"));
                var connection=stop.path("pathConnection");
                var points=compact.path("paths").path(stop.path("direction").asString()).path("points");
                int segment=connection.path("segmentIndex").asInt();double fraction=connection.path("segmentFraction").asDouble();
                assertThat(segment).isBetween(0,points.size()-2);assertThat(fraction).isBetween(0d,1d);
                for(String axis:List.of("x","y")) {
                    double a=points.get(segment).path(axis).asDouble(),b=points.get(segment+1).path(axis).asDouble();
                    assertThat(connection.path("point").path(axis).asDouble())
                            .isCloseTo(a+(b-a)*fraction,org.assertj.core.data.Offset.offset(1e-9));
                }
            }
        }
    }

    private static void assertRepresentationCaching(URI mapUri,URI diagnosticUri) throws Exception {
        String compact=get(mapUri).headers().firstValue("ETag").orElseThrow();
        String diagnostic=get(diagnosticUri).headers().firstValue("ETag").orElseThrow();
        assertThat(compact).isNotEqualTo(diagnostic);
        assertThat(conditional(mapUri,compact).statusCode()).isEqualTo(304);
        assertThat(conditional(diagnosticUri,diagnostic).statusCode()).isEqualTo(304);
        assertThat(conditional(mapUri,diagnostic).statusCode()).isEqualTo(200);
        assertThat(conditional(diagnosticUri,compact).statusCode()).isEqualTo(200);
    }
    private static HttpResponse<String> conditional(URI uri,String etag) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("If-None-Match",etag)
                .GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> get(URI uri) throws Exception {
        var response=HTTP.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);return response;
    }
    private static JsonNode body(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private static HttpResponse<String> await(URI uri,Predicate<JsonNode> condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
        HttpResponse<String> response;
        do { response=get(uri);if(condition.test(body(response))) return response;Thread.sleep(100); } while(System.nanoTime()<end);
        throw new AssertionError("Display transition timed out: "+response.body());
    }
}
