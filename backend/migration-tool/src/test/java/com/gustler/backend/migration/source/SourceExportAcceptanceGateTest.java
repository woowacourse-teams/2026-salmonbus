package com.gustler.backend.migration.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.DatabaseEnvironment;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.migration.archive.ArchiveVerifier;
import com.gustler.backend.migration.db.ArchiveStager;
import com.gustler.backend.migration.db.ImportSettings;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class SourceExportAcceptanceGateTest {

    private static final String ACCOUNT = "827325854159";
    private static final String BUCKET = "salmonbus-collector-fixture";
    private static final String ROUTE_ID = "204000057";
    private static final String MODEL_ROUTE = "3330";
    private static final String REFERENCE_VERSION = "gbis-2026-08-19";
    private static final String KNOWN_STOP = "205000227";
    private static final String UNKNOWN_STOP = "205000999";
    private static final Instant CUTOFF = Instant.parse("2026-08-15T00:00:00Z");
    private static final Instant SEAM_3330 = Instant.parse("2026-09-02T10:27:51.330754Z");
    private static final Instant SEAM_1650 = Instant.parse("2026-09-02T12:49:33.041299Z");
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void marksTheArchiveCompleteOnlyWhenEveryAcceptedAndQuarantinedCountMatchesTheApprovedNumbers(
        @TempDir Path directory
    ) {
        List<Sample> samples = List.of(
            Sample.accepted("aaaaaaaa", 0),
            Sample.accepted("bbbbbbbb", 1),
            Sample.quarantined("cccccccc", 2));

        SourceArchiveExporter.BuildResult matched = build(
            directory.resolve("matched"), samples, policy(2, 2, 1, 1));

        assertThat(matched.complete()).isTrue();
        assertThat(matched.acceptedBatches()).isEqualTo(2);
        assertThat(matched.acceptedObservations()).isEqualTo(2);
        assertThat(matched.rejectsByCode()).containsExactly(Map.entry("ROUTE_ROSTER_STATION_MISMATCH", 1L));

        SourceArchiveExporter.BuildResult offByOne = build(
            directory.resolve("off-by-one"), samples, policy(2, 2, 2, 1));

        assertThat(offByOne.complete()).isFalse();
        assertThat(offByOne.acceptedBatches()).isEqualTo(2);
        assertThat(offByOne.rejectsByCode()).containsExactly(Map.entry("ROUTE_ROSTER_STATION_MISMATCH", 1L));
    }

    @Test
    void refusesToStageAnArchiveWhoseQuarantineCountMissedTheApprovedNumber(
        @TempDir Path directory
    ) {
        Path archive = directory.resolve("archive");
        SourceArchiveExporter.BuildResult built = build(archive, List.of(
            Sample.accepted("aaaaaaaa", 0),
            Sample.quarantined("cccccccc", 2)), policy(1, 1, 2, 2));

        assertThat(built.complete()).isFalse();

        ArchiveVerifier.Verification verification = new ArchiveVerifier().verify(archive);
        assertThat(verification.manifest().summary().quarantinedBatchCount()).isEqualTo(1);
        assertThatThrownBy(() -> new ArchiveStager().stage(settings(archive), verification))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_INCOMPLETE_DATES_OR_REJECTS");
    }

    @Test
    void marksTheArchiveIncompleteWhenARejectIsNotAStationMismatch(
        @TempDir Path directory
    ) {
        Path archive = directory.resolve("archive");
        SourceArchiveExporter.BuildResult built = build(archive, List.of(
            Sample.accepted("aaaaaaaa", 0),
            Sample.accepted("bbbbbbbb", 1),
            Sample.foreignReject("dddddddd", 2)), policy(2, 2, 0, 0));

        assertThat(built.acceptedBatches()).isEqualTo(2);
        assertThat(built.rejectsByCode())
            .containsExactly(Map.entry("SOURCE_RECORD_SCHEMA_VERSION_INVALID", 1L));
        assertThat(built.complete()).isFalse();

        ArchiveVerifier.Verification verification = new ArchiveVerifier().verify(archive);
        assertThatThrownBy(() -> new ArchiveStager().stage(settings(archive), verification))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_INCOMPLETE_DATES_OR_REJECTS");
    }

    @Test
    void keepsTwoRecordsThatShareOneSemanticBatchDigestOutOfTheArchiveAndOutOfTheDatabase(
        @TempDir Path directory
    ) {
        Path archive = directory.resolve("archive");
        Sample original = Sample.accepted("aaaaaaaa", 0);
        SourceArchiveExporter.BuildResult built = build(archive, List.of(
            original, Sample.semanticTwin(original, "eeeeeeee")), policy(1, 1, 0, 0));

        assertThat(built.acceptedBatches()).isEqualTo(1);
        assertThat(built.rejectsByCode())
            .containsExactly(Map.entry("SOURCE_DUPLICATE_SEMANTIC_BATCH", 1L));
        assertThat(built.complete()).isFalse();

        ArchiveVerifier.Verification verification = new ArchiveVerifier().verify(archive);
        assertThat(verification.batchCount()).isEqualTo(1);
        assertThatThrownBy(() -> new ArchiveStager().stage(settings(archive), verification))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_INCOMPLETE_DATES_OR_REJECTS");
    }

    private SourceArchiveExporter.BuildResult build(
        Path output,
        List<Sample> samples,
        SourceAcceptancePolicy policy
    ) {
        Path protocol = output.resolveSibling(output.getFileName() + "-protocol.json");
        CanonicalJson.writeCanonical(protocol, protocolDocument());
        InMemorySourceObjectStore store = new InMemorySourceObjectStore();
        List<SourceObjectStore.ObjectInfo> entries = new ArrayList<>();
        List<String> selected = new ArrayList<>();
        for (Sample sample : samples) {
            Objects objects = objectsFor(sample);
            entries.add(objects.record().info());
            entries.add(objects.raw().info());
            store.put(objects.record());
            store.put(objects.raw());
            selected.add(Sha256.of(objects.record().info().key()));
            if (sample.selectRaw()) {
                selected.add(Sha256.of(objects.raw().info().key()));
            }
        }
        SourceInventory inventory = new SourceInventory(
            ACCOUNT, BUCKET, "ap-northeast-2", CUTOFF, null, null, entries, selected);
        return new SourceArchiveExporter(store).build(
            inventory, protocol, output, 10_000, null, policy, null);
    }

    private Objects objectsFor(
        Sample sample
    ) {
        Instant requestedAt = Instant.parse("2026-08-14T08:00:00Z").plusSeconds(sample.offsetSeconds());
        Instant receivedAt = requestedAt.plusMillis(200);
        String stamp = STAMP.format(requestedAt);
        String hour = "%02d".formatted(requestedAt.atZone(ZoneId.of("Asia/Seoul")).getHour());
        String date = requestedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        String recordKey = "records/route=" + MODEL_ROUTE + "/dt=" + date + "/hh=" + hour
            + "/" + stamp + "_" + sample.attempt() + "_" + MODEL_ROUTE + ".record.json";
        String rawKey = "raw/route=" + MODEL_ROUTE + "/dt=" + date + "/hh=" + hour
            + "/" + stamp + "_" + sample.attempt() + "_" + MODEL_ROUTE + ".json";

        Map<String, Object> rawDocument = Map.of("response", Map.of(
            "msgHeader", Map.of("resultCode", 0, "resultMessage", "OK", "queryTime", ""),
            "msgBody", Map.of("busLocationList", List.of(busRow(sample, false)))));
        byte[] rawBody = CanonicalJson.bytesOf(rawDocument);
        String rawSha256 = Sha256.of(rawBody);

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schema_version", sample.schemaVersion());
        record.put("record_id", sample.recordId().toString());
        record.put("route", Map.of("name", MODEL_ROUTE, "route_id", ROUTE_ID));
        record.put("request", Map.of("parameters", Map.of("routeId", ROUTE_ID)));
        record.put("timing", Map.of(
            "request_started_at", timePair(requestedAt),
            "response_received_at", timePair(receivedAt)));
        record.put("buses", List.of(busRow(sample, true)));
        record.put("classification", Map.of("type", "SUCCESS_WITH_VEHICLES", "vehicle_count", 1));
        record.put("http", Map.of(
            "status", 200, "response_sha256", rawSha256, "response_bytes", rawBody.length));
        record.put("raw_response", Map.of(
            "s3_key", rawKey, "sha256", rawSha256, "exact_http_body_preserved", true));
        record.put("response_envelope", json.readTree(rawBody));
        record.put("collection", Map.of(
            "strategy_version", "adaptive-kst-v1.2.0",
            "period_minutes", 1,
            "rounds_in_invocation", 1,
            "round_index", 0,
            "scheduled_at", requestedAt.minusSeconds(1).toString()));
        byte[] recordBody = CanonicalJson.bytesOf(record);

        return new Objects(
            new SourceObjectStore.LoadedObject(
                new SourceObjectStore.ObjectInfo(
                    SourceObjectStore.Kind.RECORD, recordKey, "record-" + sample.attempt(),
                    recordBody.length, receivedAt.plusSeconds(1)),
                recordBody, Map.of(), false, null),
            new SourceObjectStore.LoadedObject(
                new SourceObjectStore.ObjectInfo(
                    SourceObjectStore.Kind.RAW, rawKey, "raw-" + sample.attempt(),
                    rawBody.length, receivedAt.plusSeconds(1)),
                rawBody, Map.of("sha256", rawSha256), false, null));
    }

    private static Map<String, Object> busRow(
        Sample sample,
        boolean pseudonyms
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("vehId", "fixture-vehicle-" + sample.vehicleTag());
        row.put("plateNo", "fixture-plate-" + sample.vehicleTag());
        row.put("routeId", ROUTE_ID);
        row.put("routeTypeCd", 1);
        row.put("stationId", sample.stopId());
        row.put("stationSeq", 1);
        row.put("remainSeatCnt", 20);
        row.put("crowded", 2);
        row.put("lowPlate", 1);
        row.put("taglessCd", null);
        row.put("stateCd", 1);
        if (pseudonyms) {
            row.put("pseudonyms", Map.of(
                "vehId_hmac", "hmac-sha256:" + Sha256.of("vehicle:" + sample.vehicleTag()),
                "plateNo_hmac", "hmac-sha256:" + Sha256.of("plate:" + sample.vehicleTag())));
        }
        return row;
    }

    private static Map<String, String> timePair(
        Instant instant
    ) {
        return Map.of(
            "utc", instant.toString(),
            "kst", instant.atOffset(ZoneOffset.ofHours(9)).toString());
    }

    private static Map<String, Object> protocolDocument() {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("route_id", ROUTE_ID);
        route.put("turn_station_seq", 2);
        route.put("stations", Map.of("1", KNOWN_STOP, "2", "205000220", "3", "277000001"));
        Map<String, Object> version = new LinkedHashMap<>();
        version.put("route_reference_version_id", REFERENCE_VERSION);
        version.put("effective_from", "2026-01-01");
        version.put("effective_through", null);
        version.put("routes", Map.of(MODEL_ROUTE, route));
        return Map.of("route_reference", Map.of("versions", List.of(version)));
    }

    private static SourceAcceptancePolicy policy(
        long acceptedBatches,
        long acceptedObservations,
        long quarantinedBatches,
        long quarantinedObservations
    ) {
        return new SourceAcceptancePolicy(
            Map.of("1650", SEAM_1650, "3330", SEAM_3330),
            acceptedBatches, acceptedObservations,
            quarantinedBatches, quarantinedObservations, 0, 0);
    }

    private static ImportSettings settings(
        Path archive
    ) {
        Map<String, Instant> authority = Map.of("3330", SEAM_3330, "1650", SEAM_1650);
        return new ImportSettings(
            new DatabaseEnvironment(
                DatabaseEnvironment.TargetKind.LOCAL,
                "jdbc:postgresql://127.0.0.1:5432/never-opened",
                "never-opened", "never-opened", Sha256.of("never-opened")),
            archive,
            authority.values().stream().min(Instant::compareTo).orElseThrow(),
            authority,
            ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION,
            1, 2, 10_000, 0, 2_000, 30, 0, 0, null, null);
    }

    private record Sample(
        String attempt,
        long offsetSeconds,
        String stopId,
        String schemaVersion,
        String vehicleTag,
        UUID recordId,
        boolean selectRaw
    ) {

        private static Sample accepted(String attempt, long offsetSeconds) {
            return new Sample(
                attempt, offsetSeconds, KNOWN_STOP, "1.0.0", attempt, recordIdOf(attempt), true);
        }

        private static Sample quarantined(String attempt, long offsetSeconds) {
            return new Sample(
                attempt, offsetSeconds, UNKNOWN_STOP, "1.0.0", attempt, recordIdOf(attempt), true);
        }

        private static Sample foreignReject(String attempt, long offsetSeconds) {
            return new Sample(
                attempt, offsetSeconds, KNOWN_STOP, "9.9.9", attempt, recordIdOf(attempt), false);
        }

        private static Sample semanticTwin(Sample original, String attempt) {
            return new Sample(
                attempt, original.offsetSeconds(), original.stopId(), original.schemaVersion(),
                original.vehicleTag(), recordIdOf(attempt), false);
        }

        private static UUID recordIdOf(String attempt) {
            return UUID.nameUUIDFromBytes(attempt.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Objects(
        SourceObjectStore.LoadedObject record,
        SourceObjectStore.LoadedObject raw
    ) {
    }

    private static final class InMemorySourceObjectStore implements SourceObjectStore {

        private final Map<String, LoadedObject> objects = new LinkedHashMap<>();

        private void put(
            LoadedObject object
        ) {
            objects.put(object.info().key(), object);
        }

        @Override
        public String callerAccountId() {
            return ACCOUNT;
        }

        @Override
        public List<ObjectInfo> list(
            String bucket,
            String prefix,
            Instant cutoffAt
        ) {
            return objects.values().stream()
                .map(LoadedObject::info)
                .filter(info -> info.key().startsWith(prefix))
                .toList();
        }

        @Override
        public LoadedObject get(
            String bucket,
            ObjectInfo expected
        ) {
            LoadedObject object = objects.get(expected.key());
            if (object == null) {
                throw new MigrationException("SOURCE_S3_GET_FAILED");
            }
            return object;
        }

        @Override
        public void close() {
        }
    }
}
