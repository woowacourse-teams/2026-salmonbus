package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.archive.ArchiveManifest;
import com.gustler.backend.migration.archive.RouteRoster;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteBinderTest extends PostgresMigrationTestSupport {

    private static final Instant FIRST_3330 = Instant.parse("2026-08-14T08:00:00Z");
    private static final Instant FIRST_1650 = Instant.parse("2026-08-14T08:01:00Z");
    private static final Instant LATE_3330 = Instant.parse("2026-09-02T10:27:45.315Z");
    private static final Instant LATE_1650 = Instant.parse("2026-09-02T12:49:31.467Z");
    private static final String REFERENCE_VERSION = "gbis-2026-08-19";

    @TempDir
    static Path archiveDirectory;

    private static ArchiveTestFixture.Fixture fixture;
    private static int manifestCounter;

    @BeforeAll
    static void prepareSchemaAndRoutes() throws Exception {
        new HistoricalSchema().migrate(database);
        try (Connection connection = connection()) {
            fixture = ArchiveTestFixture.create(connection, archiveDirectory.resolve("archive"));
        }
    }

    @Test
    void preflightPinsBothRoutesToTheApprovedCurrentVersionIdentities() throws Exception {
        assertThat(fixture.version3330()).isEqualTo(1);
        assertThat(fixture.version1650()).isEqualTo(2);

        try (Connection connection = connection()) {
            Map<String, Long> versions =
                new RouteBinder().preflight(connection, settings(), fixture.manifest());

            assertThat(versions).containsOnly(
                Map.entry("3330", fixture.version3330()),
                Map.entry("1650", fixture.version1650()));
        }
    }

    @Test
    void refusesRoutesWhoseIdentityColumnsDoNotMatchTheApprovedTargetExactly() throws Exception {
        assertThat(preflightFailureAfter(
            "UPDATE route SET public_route_id = '204000058' WHERE display_name = '3330'"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
        assertThat(preflightFailureAfter(
            "UPDATE route SET source_id = 'GGDREAM' WHERE display_name = '3330'"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
        assertThat(preflightFailureAfter(
            "UPDATE route SET source_route_id = '204000058' WHERE display_name = '3330'"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
        assertThat(preflightFailureAfter(
            "UPDATE route SET display_name = '3330-B' WHERE display_name = '3330'"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
    }

    @Test
    void refusesTheRouteWhenTheCurrentVersionRowNoLongerCarriesThePinnedIdentity() throws Exception {
        assertThat(preflightFailureAfter(
            "CREATE TEMP TABLE saved_version AS SELECT * FROM route_version WHERE id = 1",
            "CREATE TEMP TABLE saved_stops AS SELECT * FROM route_stop WHERE route_version_id = 1",
            "DELETE FROM vehicle_observation WHERE route_version_id = 1",
            "DELETE FROM observation_batch WHERE route_version_id = 1",
            "DELETE FROM route_stop WHERE route_version_id = 1",
            "DELETE FROM route_version WHERE id = 1",
            """
            INSERT INTO route_version (
                id, route_id, turn_sequence, up_first_departure_time, up_last_departure_time,
                down_first_departure_time, down_last_departure_time, content_digest, valid_from, valid_to)
            OVERRIDING SYSTEM VALUE
            SELECT 99, route_id, turn_sequence, up_first_departure_time, up_last_departure_time,
                   down_first_departure_time, down_last_departure_time, content_digest, valid_from, valid_to
            FROM saved_version
            """,
            """
            INSERT INTO route_stop (route_version_id, stop_order, stop_id, name, direction, boarding_allowed)
            SELECT 99, stop_order, stop_id, name, direction, boarding_allowed FROM saved_stops
            """))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_MISSING");
    }

    @Test
    void refusesTargetsWithASecondVersionAClosedValidityOrAChangedPinnedContent() throws Exception {
        assertThat(preflightFailureAfter("""
            INSERT INTO route_version (
                route_id, turn_sequence, content_digest, valid_from, valid_to)
            SELECT route_id, turn_sequence, content_digest,
                   TIMESTAMPTZ '2026-01-01T00:00:00Z', TIMESTAMPTZ '2026-01-02T00:00:00Z'
            FROM route_version WHERE id = 1
            """))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_PRECONDITION_FAILED");
        assertThat(preflightFailureAfter(
            "UPDATE route_version SET valid_to = TIMESTAMPTZ '2026-09-03T00:00:00Z' WHERE id = 1"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_PRECONDITION_FAILED");
        assertThat(preflightFailureAfter(
            "UPDATE route_version SET turn_sequence = 42 WHERE id = 1"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_PRECONDITION_FAILED");
        assertThat(preflightFailureAfter(
            "UPDATE route_version SET content_digest = repeat('0', 64) WHERE id = 1"))
            .isEqualTo("DATABASE_EXACT_CURRENT_ROUTE_PRECONDITION_FAILED");
    }

    @Test
    void recalculatesTheContentDigestFromRouteStopRowsInsteadOfTrustingTheStoredValue() throws Exception {
        assertThat(preflightFailureAfter(
            "UPDATE route_stop SET name = name || '역' WHERE route_version_id = 1 AND stop_order = 1"))
            .isEqualTo("DATABASE_ROUTE_CONTENT_DIGEST_MISMATCH");
        assertThat(preflightFailureAfter(
            "UPDATE route_stop SET stop_id = '205000999' WHERE route_version_id = 1 AND stop_order = 1"))
            .isEqualTo("DATABASE_ROUTE_CONTENT_DIGEST_MISMATCH");
        assertThat(preflightFailureAfter(
            "DELETE FROM route_stop WHERE route_version_id = 1 AND stop_order = 85"))
            .isEqualTo("DATABASE_ROUTE_CONTENT_DIGEST_MISMATCH");
    }

    @Test
    void refusesAnArchiveRosterThatDoesNotMatchTheStoredRouteStopRowsStopByStop() throws Exception {
        RouteRoster reference = rosterOf("3330");
        Map<Integer, RouteRoster.Station> renamed = new TreeMap<>(reference.stations());
        renamed.put(1, new RouteRoster.Station(1, "205000999", true));

        assertThatThrownBy(() -> preflight(manifestWith(replaceRoster(reference, renamed))))
            .isInstanceOf(MigrationException.class)
            .hasMessage("DATABASE_ROUTE_ROSTER_MISMATCH");

        Map<Integer, RouteRoster.Station> shortened = new TreeMap<>(reference.stations());
        shortened.remove(85);
        assertThatThrownBy(() -> preflight(manifestWith(replaceRoster(reference, shortened))))
            .isInstanceOf(MigrationException.class)
            .hasMessage("DATABASE_ROUTE_ROSTER_MISMATCH");
    }

    @Test
    void refusesWhenTheManifestDoesNotHoldExactlyOneRosterForTheRoute() throws Exception {
        RouteRoster reference = rosterOf("3330");
        List<RouteRoster> duplicated = new ArrayList<>(fixture.manifest().routeReferences());
        duplicated.add(new RouteRoster(
            "gbis-2026-09-01", reference.effectiveFrom(), reference.effectiveThrough(),
            reference.modelRoute(), reference.sourceRouteId(), reference.turnSequence(),
            reference.stations(), null));

        assertThatThrownBy(() -> preflight(manifestWith(duplicated)))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_ROUTE_ROSTER_PREFLIGHT_NOT_UNIQUE");

        List<RouteRoster> without3330 = fixture.manifest().routeReferences().stream()
            .filter(roster -> !"3330".equals(roster.modelRoute()))
            .toList();
        assertThatThrownBy(() -> preflight(manifestWith(without3330)))
            .isInstanceOf(MigrationException.class)
            .hasMessage("ARCHIVE_ROUTE_ROSTER_PREFLIGHT_NOT_UNIQUE");
    }

    @Test
    void refuses1650WhenTheStoredVersionDivergesFromTheApprovedSeedFile() throws Exception {
        assertThat(preflightFailureAfter(
            "UPDATE route_version SET up_first_departure_time = '00:05' WHERE id = 2"))
            .isEqualTo("DATABASE_ROUTE_1650_SEED_MISMATCH");
    }

    @Test
    void refusesAValidityThatNoRecordedImportCanExplain() throws Exception {
        assertThat(preflightFailureAfter(
            "UPDATE route_version SET valid_from = valid_from - INTERVAL '1 hour' WHERE id = 1"))
            .isEqualTo("ROUTE_CURRENT_VALID_FROM_UNEXPLAINED");
    }

    @Test
    void extendsBothCurrentVersionsBackToTheFirstAcceptedResponseWithoutCreatingANewVersion()
        throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                UUID importId = stageImport(connection, authority(), List.of(
                    new Staged("3330", "204000057", REFERENCE_VERSION, LATE_3330),
                    new Staged("3330", "204000057", REFERENCE_VERSION, FIRST_3330),
                    new Staged("1650", "234000050", REFERENCE_VERSION, LATE_1650),
                    new Staged("1650", "234000050", REFERENCE_VERSION, FIRST_1650)));

                List<RouteBinder.Binding> bindings =
                    new RouteBinder().bind(connection, settings(), fixture.manifest(), importId);

                assertThat(bindings).hasSize(2);
                RouteBinder.Binding binding1650 = bindings.getFirst();
                RouteBinder.Binding binding3330 = bindings.getLast();
                assertThat(binding1650.modelRoute()).isEqualTo("1650");
                assertThat(binding1650.routeVersionId()).isEqualTo(fixture.version1650());
                assertThat(binding1650.mappingKind()).isEqualTo("EXTENDED_CURRENT_ROUTE");
                assertThat(binding1650.originalValidFrom()).isEqualTo(ArchiveTestFixture.ORIGINAL_1650);
                assertThat(binding1650.validFrom()).isEqualTo(FIRST_1650);
                assertThat(binding1650.validTo()).isNull();
                assertThat(binding3330.modelRoute()).isEqualTo("3330");
                assertThat(binding3330.routeVersionId()).isEqualTo(fixture.version3330());
                assertThat(binding3330.mappingKind()).isEqualTo("EXTENDED_CURRENT_ROUTE");
                assertThat(binding3330.originalValidFrom()).isEqualTo(ArchiveTestFixture.ORIGINAL_3330);
                assertThat(binding3330.validFrom()).isEqualTo(FIRST_3330);
                assertThat(binding3330.archiveRosterSha256())
                    .isEqualTo(binding3330.databaseRosterSha256());
                assertThat(binding1650.archiveRosterSha256())
                    .isEqualTo(binding1650.databaseRosterSha256());

                assertThat(validityOf(connection, fixture.version3330())).isEqualTo(FIRST_3330);
                assertThat(validityOf(connection, fixture.version1650())).isEqualTo(FIRST_1650);
                assertThat(countOf(connection, "SELECT count(*) FROM route_version")).isEqualTo(2);
                assertThat(countOf(connection,
                    "SELECT count(*) FROM historical_import_route_binding WHERE mapping_kind"
                        + " = 'EXTENDED_CURRENT_ROUTE'"))
                    .isEqualTo(2);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void refusesOneImportThatCarriesTwoRosterVersionsForTheSameRoute() throws Exception {
        assertThat(bindFailureWith(authority(), List.of(
            new Staged("3330", "204000057", REFERENCE_VERSION, FIRST_3330),
            new Staged("3330", "204000057", "gbis-2026-09-01", LATE_3330))))
            .isEqualTo("MULTIPLE_ROUTE_ROSTERS_REQUIRE_SEPARATE_IMPORTS");
    }

    @Test
    void refusesStagedRowsWhoseRosterVersionOrRouteIsNotInTheApprovedSet() throws Exception {
        assertThat(bindFailureWith(authority(), List.of(
            new Staged("3330", "204000057", "gbis-2026-09-01", FIRST_3330))))
            .isEqualTo("ARCHIVE_ROUTE_ROSTER_BINDING_NOT_UNIQUE");
        assertThat(bindFailureWith(authority(), List.of(
            new Staged("1650", "204000057", REFERENCE_VERSION, FIRST_1650))))
            .isEqualTo("ROUTE_MAPPING_UNSUPPORTED_ROUTE");
    }

    @Test
    void refusesToReuseAnEarlierValidityThatNoRecordedImportProduced() throws Exception {
        Map<String, Instant> authority = Map.of(
            "3330", ArchiveTestFixture.FIRST_LIVE_3330,
            "1650", ArchiveTestFixture.ORIGINAL_1650);

        assertThat(bindFailureWith(authority, List.of(
            new Staged("3330", "204000057", REFERENCE_VERSION, ArchiveTestFixture.ORIGINAL_3330))))
            .isEqualTo("ROUTE_EARLIER_VALID_FROM_HAS_NO_IMPORT_PROVENANCE");
    }

    @Test
    void refusesToExtendAValidityThatAnotherSessionMovedFirst() throws Exception {
        try (Connection blocking = connection()) {
            blocking.setAutoCommit(false);
            CompletableFuture<String> binding;
            try (Statement lock = blocking.createStatement()) {
                lock.executeQuery("SELECT id FROM route_version WHERE id = 1 FOR UPDATE").close();
                binding = CompletableFuture.supplyAsync(() -> bindFailureQuietly(authority(), List.of(
                    new Staged("3330", "204000057", REFERENCE_VERSION, FIRST_3330))));
                awaitBlockedStatement();
                lock.executeUpdate(
                    "UPDATE route_version SET valid_from = valid_from - INTERVAL '2 hours' WHERE id = 1");
                blocking.commit();
            }

            try {
                assertThat(binding.get(30, TimeUnit.SECONDS))
                    .isEqualTo("ROUTE_VALID_FROM_EXTENSION_CONCURRENT_CONFLICT");
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            } finally {
                restoreValidity();
            }
        }
    }

    private static void awaitBlockedStatement() throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            try (Connection observer = connection();
                Statement statement = observer.createStatement();
                ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted")) {
                rows.next();
                if (rows.getLong(1) > 0) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("route_version update never blocked");
    }

    private static void restoreValidity() throws Exception {
        try (Connection connection = connection();
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE route_version SET valid_from = ? WHERE id = 1")) {
            statement.setObject(1, ArchiveTestFixture.ORIGINAL_3330.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private static String bindFailureQuietly(
        Map<String, Instant> authority,
        List<Staged> staged
    ) {
        try {
            return bindFailureWith(authority, staged);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String bindFailureWith(
        Map<String, Instant> authority,
        List<Staged> staged
    ) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                UUID importId = stageImport(connection, authority, staged);
                new RouteBinder().bind(connection, settings(), fixture.manifest(), importId);
                return null;
            } catch (MigrationException e) {
                return e.code();
            } finally {
                connection.rollback();
            }
        }
    }

    private static String preflightFailureAfter(
        String... statements
    ) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (String sql : statements) {
                    try (Statement statement = connection.createStatement()) {
                        statement.executeUpdate(sql);
                    }
                }
                new RouteBinder().preflight(connection, settings(), fixture.manifest());
                return null;
            } catch (MigrationException e) {
                return e.code();
            } finally {
                connection.rollback();
            }
        }
    }

    private static void preflight(
        ArchiveManifest manifest
    ) throws SQLException {
        try (Connection connection = connection()) {
            new RouteBinder().preflight(connection, settings(), manifest);
        }
    }

    private static RouteRoster rosterOf(
        String modelRoute
    ) {
        return fixture.manifest().routeReferences().stream()
            .filter(roster -> modelRoute.equals(roster.modelRoute()))
            .findFirst()
            .orElseThrow();
    }

    private static List<RouteRoster> replaceRoster(
        RouteRoster reference,
        Map<Integer, RouteRoster.Station> stations
    ) {
        List<RouteRoster> rosters = new ArrayList<>();
        for (RouteRoster roster : fixture.manifest().routeReferences()) {
            rosters.add(roster == reference
                ? new RouteRoster(
                    roster.version(), roster.effectiveFrom(), roster.effectiveThrough(),
                    roster.modelRoute(), roster.sourceRouteId(), roster.turnSequence(), stations, null)
                : roster);
        }
        return rosters;
    }

    private static ArchiveManifest manifestWith(
        List<RouteRoster> rosters
    ) {
        ArchiveManifest manifest = fixture.manifest();
        return new ArchiveManifest(
            manifest.archiveKind(), manifest.previousManifestSha256(), manifest.exporterVersion(),
            manifest.cutoffAt(), manifest.inventory(), manifest.normalizationVersion(),
            manifest.complete(), manifest.terminalFreeze(), rosters, manifest.shards(),
            manifest.summary());
    }

    private static Map<String, Instant> authority() {
        return Map.of(
            "3330", ArchiveTestFixture.ORIGINAL_3330,
            "1650", ArchiveTestFixture.ORIGINAL_1650);
    }

    private static ImportSettings settings() {
        Map<String, Instant> authority = authority();
        return new ImportSettings(
            database,
            archiveDirectory.resolve("archive"),
            authority.values().stream().min(Instant::compareTo).orElseThrow(),
            authority,
            ImportSettings.RouteValidityPolicy.EXTEND_EXACT_CURRENT_VERSION,
            1,
            2,
            10_000,
            0,
            2_000,
            30,
            0,
            0,
            fixture.routeSeed1650(),
            null);
    }

    private static UUID stageImport(
        Connection connection,
        Map<String, Instant> authority,
        List<Staged> rows
    ) throws SQLException {
        String manifestSha256 = "%064x".formatted(++manifestCounter);
        UUID importId = ImportIds.fromManifest(manifestSha256);
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_batch (
                id, manifest_sha256, archive_schema_version, archive_kind, inventory_sha256,
                source_cutoff_at, importer_version, target_kind, target_authority_from_min,
                route_validity_policy, status, expected_batch_count, expected_observation_count)
            VALUES (?, ?, ?, 'BASE', ?, ?, 's3-rds-migration-v1', 'LOCAL', ?,
                    'EXTEND_EXACT_CURRENT_VERSION', 'STAGED', ?, ?)
            """)) {
            statement.setObject(1, importId);
            statement.setString(2, manifestSha256);
            statement.setString(3, ArchiveManifest.SCHEMA_VERSION);
            statement.setString(4, "%064x".formatted(manifestCounter));
            statement.setObject(5, offset(Instant.parse("2026-09-02T13:00:00Z")));
            statement.setObject(6, offset(
                authority.values().stream().min(Instant::compareTo).orElseThrow()));
            statement.setLong(7, rows.size());
            statement.setLong(8, rows.size());
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_route_boundary (
                import_batch_id, model_route, target_authority_from)
            VALUES (?, ?, ?)
            """)) {
            for (Map.Entry<String, Instant> entry : authority.entrySet()) {
                statement.setObject(1, importId);
                statement.setString(2, entry.getKey());
                statement.setObject(3, offset(entry.getValue()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
        int line = 0;
        for (Staged row : rows) {
            line++;
            String digest = "%064x".formatted(manifestCounter * 1_000L + line);
            insertStagedRecord(connection, importId, digest, line, row);
            insertStagedBatch(connection, importId, digest, row);
        }
        return importId;
    }

    private static void insertStagedRecord(
        Connection connection,
        UUID importId,
        String digest,
        int line,
        Staged row
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_record (
                import_batch_id, source_account, source_record_id, source_schema_version,
                semantic_batch_digest, normalized_record_sha256, shard_sha256, shard_line_number,
                model_route, source_route_id, source_collected_at, kst_date,
                provider_rows, stored_rows, excluded_rows, status)
            VALUES (?, '827325854159', ?, '1.0.0', ?, ?, ?, ?, ?, ?, ?,
                    (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Seoul')::date, 1, 1, 0, 'STAGED')
            """)) {
            statement.setObject(1, importId);
            statement.setObject(2, UUID.nameUUIDFromBytes(digest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            statement.setString(3, digest);
            statement.setString(4, digest);
            statement.setString(5, "%064x".formatted(0));
            statement.setInt(6, line);
            statement.setString(7, row.modelRoute());
            statement.setString(8, row.sourceRouteId());
            statement.setObject(9, offset(row.receivedAt()));
            statement.setObject(10, offset(row.receivedAt()));
            statement.executeUpdate();
        }
    }

    private static void insertStagedBatch(
        Connection connection,
        UUID importId,
        String digest,
        Staged row
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO historical_import_stage_batch (
                import_batch_id, source_account, source_record_id, source_schema_version,
                semantic_batch_digest, route_reference_version, model_route, source_route_id,
                scheduled_at, requested_at, response_received_at, attempt_key, http_status,
                result_code, outcome, failure_code, provider_rows, stored_rows, excluded_rows,
                normalization_version, collection_strategy_version)
            VALUES (?, '827325854159', ?, '1.0.0', ?, ?, ?, ?, ?, ?, ?, ?, 200, 0,
                    'SUCCESS_ROWS', NULL, 1, 1, 0,
                    'normalization-v1.0.0-s3-backfill', 'adaptive-kst-v1.2.0')
            """)) {
            statement.setObject(1, importId);
            statement.setObject(2, UUID.nameUUIDFromBytes(digest.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            statement.setString(3, digest);
            statement.setString(4, row.referenceVersion());
            statement.setString(5, row.modelRoute());
            statement.setString(6, row.sourceRouteId());
            statement.setObject(7, offset(row.receivedAt().minusMillis(300)));
            statement.setObject(8, offset(row.receivedAt().minusMillis(200)));
            statement.setObject(9, offset(row.receivedAt()));
            statement.setString(10, "s3v1:" + digest);
            statement.executeUpdate();
        }
    }

    private static Instant validityOf(
        Connection connection,
        long routeVersionId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT valid_from FROM route_version WHERE id = ?")) {
            statement.setLong(1, routeVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getObject(1, OffsetDateTime.class).toInstant();
            }
        }
    }

    private static long countOf(
        Connection connection,
        String sql
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static OffsetDateTime offset(
        Instant instant
    ) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record Staged(
        String modelRoute,
        String sourceRouteId,
        String referenceVersion,
        Instant receivedAt
    ) {
    }
}
