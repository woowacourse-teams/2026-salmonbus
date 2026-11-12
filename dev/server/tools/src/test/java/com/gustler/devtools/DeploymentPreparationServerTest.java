package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag("linux-root")
class DeploymentPreparationServerTest {
    @TempDir Path base;
    private Fixtures fixture;
    private PreparationHost host;
    private Path original;
    private Path incoming;
    private boolean databaseApproved;
    private boolean failDatabaseApproval;
    private final Map<String, String> apiEnvironment = Map.of(
            "APPLICATION_NAME", "salmonbus-backend-dev",
            "DEPLOYMENT_GROUP_NAME", "salmonbus-backend-dev-api");

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root());
        fixture = new Fixtures(base);
        fixture.settings();
        var marker = Json.read(FileOps.privateText(fixture.layout.config().resolve("environment.json")).getBytes());
        ((tools.jackson.databind.node.ObjectNode) marker).put("accountId", "000000000000");
        FileOps.atomicFile(fixture.layout.config().resolve("environment.json"), Json.bytes(marker), 0600);
        host = new PreparationHost();
        host.data = fixture.layout.data();
        var releases = Files.createDirectories(fixture.layout.root().resolve("basis/releases"));
        var bundle = basis(base.resolve("old-basis"), "1".repeat(64), "a".repeat(40));
        var metadata = RevisionStore.verify(bundle, true);
        original = releases.resolve(metadata.releaseId());
        Files.move(bundle, original);
        Files.createSymbolicLink(fixture.layout.root().resolve("basis/current"), Path.of("releases/" + metadata.releaseId()));
        Files.createSymbolicLink(fixture.layout.tools(), Path.of("basis/current/tools"));
        RevisionStore.ready(fixture.layout, metadata);
        Files.copy(original.resolve("systemd/salmonbus-dev-infra.service"),
                fixture.layout.units().resolve("salmonbus-dev-infra.service"));
        for (var component : List.of("api", "worker")) {
            var app = application(base.resolve("old-" + component), component,
                    "1".repeat(64), "a".repeat(40), null);
            var revision = RevisionStore.verify(app, true);
            var appReleases = Files.createDirectories(fixture.layout.root().resolve(component + "/releases"));
            Files.move(app, appReleases.resolve(revision.releaseId()));
            Files.createSymbolicLink(fixture.layout.root().resolve(component + "/current"),
                    Path.of("releases/" + revision.releaseId()));
            Files.writeString(fixture.layout.units().resolve("salmonbus-" + component + ".service"),
                    "old " + component + " unit\n");
        }
        Files.writeString(fixture.layout.data().resolve("database-sentinel"), "existing database contents\n");
        incoming = application(base.resolve("new-api"), "api", "2".repeat(64), "b".repeat(40),
                basis(base.resolve("new-basis"), "2".repeat(64), "b".repeat(40)));
    }

    private Path basis(Path path, String digest, String commit) throws Exception {
        Files.createDirectory(path);
        for (var name : List.of("tools/api.yml", "tools/worker.yml",
                "systemd/salmonbus-dev-infra.service", "infra/compose.yaml",
                "tools/data-contract.sha256", "wiremock-image.tar")) {
            var file = path.resolve(name);
            Files.createDirectories(file.getParent());
            var text = switch (name) {
                case "tools/api.yml" -> Files.readString(fixture.source.resolve("api.yml"));
                case "tools/worker.yml" -> Files.readString(fixture.source.resolve("worker.yml"));
                default -> "stable " + name + "\n";
            };
            Files.writeString(file, text);
        }
        for (var name : DataPreparation.FILES) {
            var file = path.resolve("tools/data/" + name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "stable data " + name);
        }
        Files.writeString(path.resolve("wiremock-image.json"), Json.MAPPER.writeValueAsString(
                Map.of("tag", "salmonbus-gbis-replay:" + commit, "id", "sha256:fixture", "architecture", "arm64")));
        RevisionStore.seal(path, "basis", commit, digest, digest, false);
        return path;
    }

    private Path application(Path path, String component, String contract, String commit, Path embedded) throws Exception {
        Files.createDirectories(path.resolve("jars"));
        var jar = "jars/" + component + "-app.jar";
        Files.writeString(path.resolve(jar), "application fixture");
        var revision = new RevisionStore.Revision(1, "dev", component, commit,
                "3".repeat(64), contract, false, Map.of(jar, FileOps.sha(path.resolve(jar))), "");
        Files.writeString(path.resolve("release.env"), Environment.text(RevisionStore.releaseEnvironment(revision)));
        Files.writeString(path.resolve("release-manifest.txt"), Environment.text(RevisionStore.textManifest(revision)));
        if (embedded != null) {
            try (var files = Files.walk(embedded)) {
                for (var file : files.filter(Files::isRegularFile).toList()) {
                    Packager.copy(file, path.resolve("basis").resolve(embedded.relativize(file)));
                }
            }
        }
        RevisionStore.seal(path, component, commit, revision.sourceDigest(), contract, false);
        return path;
    }

    private DeploymentPreparation preparation() {
        return new DeploymentPreparation(fixture.layout, host, new DataTransition() {
            @Override
            void approveDatabase(Layout layout, Path basis, Approval approval) {
                assertFalse(host.apiActive);
                assertFalse(host.workerActive);
                if (failDatabaseApproval) throw new IllegalArgumentException("DB 승인 실패");
                databaseApproved = true;
            }
        });
    }

    private void changeDataContract() throws Exception {
        var next = incoming.resolve("basis");
        Files.delete(next.resolve("revision.json"));
        Files.writeString(next.resolve("tools/data-contract.sha256"), "changed data contract\n");
        RevisionStore.seal(next, "basis", "b".repeat(40), "2".repeat(64), "2".repeat(64), false);
        Files.delete(incoming.resolve("revision.json"));
        RevisionStore.seal(incoming, "api", "b".repeat(40), "3".repeat(64), "2".repeat(64), false);
    }

    private void approveDataContract() throws Exception {
        var directory = fixture.layout.data().resolve("deployment-preparation");
        FileOps.directory(directory, 0700);
        FileOps.atomicFile(directory.resolve("approval.json"), Json.bytes(Map.of(
                "transition", "SAL-175",
                "fromReleaseId", RevisionStore.verify(original, true).releaseId(),
                "toReleaseId", RevisionStore.verify(incoming.resolve("basis"), true).releaseId(),
                "backupReference", "dev-before-sal175",
                "expiresAt", java.time.Instant.now().plusSeconds(900).toString())), 0600);
    }

    private void changeFixtureCodeContract() throws Exception {
        changeCodeContract("116160aff97c67af1d1802ea7b985003889654365ee56b1a63783b713f30a5f1",
            CompatiblePreparation.NEXT, "a0169df87d0dbc8f488b562039beec76412275ecc077174d4f855dc5a1af2ee1",
            CompatiblePreparation.FIXTURE_DIGEST);
    }

    /** 증분 하차 정산을 도입하며 바뀐 초기화 코드 계약이다. */
    private void changeToIncrementalSettlementContract() throws Exception {
        changeCodeContract("b8f27458cfea4714baaadee80f8f3da737d325803d4820b8768ea0344c647e7a",
            CompatiblePreparation.ENV_MODEL_DATA, "adb3a9c2848d4e36da66dc27d1562bc44d6e5e9af014d53c92ca5762f0402de6",
            CompatiblePreparation.INCREMENTAL_SETTLEMENT_DATA);
    }

    private void changeCodeContract(String previousContract, String previousDigest,
            String nextContract, String nextDigest) throws Exception {
        Files.delete(original.resolve("revision.json"));
        Files.writeString(original.resolve("tools/data-contract.sha256"), previousContract + "\n");
        var previous = RevisionStore.seal(original, "basis", "a".repeat(40), "1".repeat(64), "1".repeat(64), false);
        assertEquals(previousDigest, previous.files().get("tools/data-contract.sha256"));
        original = Files.move(original, original.resolveSibling(previous.releaseId()));
        Deployment.setLink(fixture.layout.root().resolve("basis/current"), original);
        RevisionStore.ready(fixture.layout, previous);
        var next = incoming.resolve("basis");
        Files.delete(next.resolve("revision.json"));
        Files.writeString(next.resolve("tools/data-contract.sha256"), nextContract + "\n");
        var candidate = RevisionStore.seal(next, "basis", "b".repeat(40), "2".repeat(64), "2".repeat(64), false);
        assertEquals(nextDigest, candidate.files().get("tools/data-contract.sha256"));
        Files.delete(incoming.resolve("revision.json"));
        RevisionStore.seal(incoming, "api", "b".repeat(40), "3".repeat(64), "2".repeat(64), false);
    }

    private Path journal() {
        return fixture.layout.data().resolve("deployment-preparation/current.json");
    }

    @Test
    void 증분_정산_초기화_계약으로_바꾼_뒤_다음_hook이_실패하면_이전_basis와_앱으로_복구한다() throws Exception {
        // given
        changeToIncrementalSettlementContract();
        var api = fixture.layout.root().resolve("api/current").toRealPath();
        var worker = fixture.layout.root().resolve("worker/current").toRealPath();
        preparation().prepare(incoming, apiEnvironment);
        assertFalse(Json.read(journal()).path("dataTransition").asBoolean());

        // when
        var result = preparation().recover(incoming, apiEnvironment);

        // then
        assertEquals(true, result.get("recoveryRequired"));
        assertEquals("ROLLED_BACK", Json.read(journal()).path("phase").asText());
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertEquals(api, fixture.layout.root().resolve("api/current").toRealPath());
        assertEquals(worker, fixture.layout.root().resolve("worker/current").toRealPath());
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertEquals("existing database contents\n", Files.readString(fixture.layout.data().resolve("database-sentinel")));
        assertEquals(true, preparation().prepare(incoming, apiEnvironment).get("preparationRequired"));
    }

    @Test
    void 기반_교체가_옮기지_않는_설정_파일을_앱이_읽으면_앱을_중지하기_전에_거절한다() throws Exception {
        // given
        var settings = fixture.layout.config().resolve("route-geometry-api.env");
        FileOps.writeNew(settings, "ROUTE_GEOMETRY_PRESENTATION_ENABLED=true\n".getBytes(), 0600);
        var drop = fixture.layout.units().resolve("salmonbus-api.service.d/90-route-geometry.conf");
        Files.createDirectories(drop.getParent());
        Files.writeString(drop, "[Service]\nEnvironmentFile=" + settings + "\n");

        // when
        var failure = assertThrows(IllegalArgumentException.class,
                () -> preparation().prepare(incoming, apiEnvironment));

        // then
        assertEquals("DEV_UNMANAGED_ENVIRONMENT_FILE", failure.getMessage());
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(Files.exists(settings));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertTrue(host.calls.stream().noneMatch(c -> c.contains("stop")));
        assertFalse(Files.exists(journal()));
        var moved = Files.createDirectories(base.resolve("etc/salmonbus-dev-route-geometry")).resolve("api.env");
        Files.move(settings, moved);
        Files.writeString(drop, "[Service]\nEnvironmentFile=" + moved + "\n");
        assertEquals(true, preparation().prepare(incoming, apiEnvironment).get("preparationRequired"));
        assertEquals("ROUTE_GEOMETRY_PRESENTATION_ENABLED=true\n", Files.readString(moved));
    }

    @Test
    void 다른_커밋의_완료되지_않은_기반_교체_기록은_다음_기반_교체를_막지_않는다() throws Exception {
        // given
        preparation().prepare(incoming, apiEnvironment);
        var prepared = RevisionStore.selectedBasis(fixture.layout);
        var later = application(base.resolve("later-api"), "api", "4".repeat(64), "c".repeat(40),
                basis(base.resolve("later-basis"), "4".repeat(64), "c".repeat(40)));

        // when
        var result = preparation().prepare(later, apiEnvironment);

        // then
        assertEquals(true, result.get("preparationRequired"));
        assertEquals("b".repeat(40), result.get("supersededCommit"));
        assertEquals("PREPARED", result.get("supersededPhase"));
        assertNotEquals(prepared, RevisionStore.selectedBasis(fixture.layout));
        assertEquals("c".repeat(40), Json.read(journal()).path("commit").asText());
        assertEquals(RevisionStore.verify(prepared, true).releaseId(),
                Json.read(journal()).path("basisReleaseId").asText());
        assertEquals("PREPARED", Json.read(journal()).path("phase").asText());
    }

    @Test
    void DB_전환_기록과_준비_중_기록은_다른_커밋이어도_다음_기반_교체를_막는다() throws Exception {
        // given
        preparation().prepare(incoming, apiEnvironment);
        var later = application(base.resolve("later-api"), "api", "4".repeat(64), "c".repeat(40),
                basis(base.resolve("later-basis"), "4".repeat(64), "c".repeat(40)));
        var state = (tools.jackson.databind.node.ObjectNode) Json.read(journal());

        for (var change : List.<Runnable>of(() -> state.put("dataTransition", true),
                () -> state.put("dataTransition", false).put("phase", "PREPARING"))) {
            change.run();
            FileOps.atomicFile(journal(), Json.bytes(state), 0600);

            // when
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> preparation().prepare(later, apiEnvironment));

            // then
            assertEquals("DEV_PREPARATION_RECOVERY_REQUIRED", failure.getMessage());
            assertEquals("b".repeat(40), Json.read(journal()).path("commit").asText());
        }
    }

    @Test
    void 같은_커밋_기록과_basis가_되돌려진_기록과_DB_전환_여부가_없는_기록은_다음_기반_교체를_막는다() throws Exception {
        // given
        preparation().prepare(incoming, apiEnvironment);
        var prepared = RevisionStore.selectedBasis(fixture.layout);
        var preparedReleaseId = RevisionStore.verify(prepared, true).releaseId();
        var later = application(base.resolve("later-api"), "api", "4".repeat(64), "c".repeat(40),
                basis(base.resolve("later-basis"), "4".repeat(64), "c".repeat(40)));
        var recorded = (tools.jackson.databind.node.ObjectNode) Json.read(journal());
        assertEquals("PREPARED", recorded.path("phase").asText());

        for (var change : List.<java.util.function.Consumer<tools.jackson.databind.node.ObjectNode>>of(
                state -> state.put("commit", "c".repeat(40)),
                state -> state.put("basisReleaseId", preparedReleaseId),
                state -> state.remove("dataTransition"))) {
            var state = recorded.deepCopy();
            change.accept(state);
            FileOps.atomicFile(journal(), Json.bytes(state), 0600);

            // when
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> preparation().prepare(later, apiEnvironment));

            // then
            assertEquals("DEV_PREPARATION_RECOVERY_REQUIRED", failure.getMessage());
            assertEquals(state, Json.read(journal()));
            assertEquals(prepared, RevisionStore.selectedBasis(fixture.layout));
        }
    }

    @Test
    void API_검증까지_끝난_다른_커밋의_기록은_그_커밋의_worker_배포가_남아_있을_수_있어_다음_기반_교체를_막는다() throws Exception {
        // given
        preparation().prepare(incoming, apiEnvironment);
        preparation().complete(incoming, apiEnvironment);
        var prepared = RevisionStore.selectedBasis(fixture.layout);
        var recorded = Json.read(journal());
        assertEquals("API_READY", recorded.path("phase").asText());
        var later = application(base.resolve("later-api"), "api", "4".repeat(64), "c".repeat(40),
                basis(base.resolve("later-basis"), "4".repeat(64), "c".repeat(40)));

        // when
        var failure = assertThrows(IllegalArgumentException.class,
                () -> preparation().prepare(later, apiEnvironment));

        // then
        assertEquals("DEV_PREPARATION_RECOVERY_REQUIRED", failure.getMessage());
        assertEquals(recorded, Json.read(journal()));
        assertEquals(prepared, RevisionStore.selectedBasis(fixture.layout));
    }

    @Test
    void 파일_검증용_지문_분리는_DB_전환_없이_준비하고_다음_hook_실패에도_복구한다() throws Exception {
        // given
        changeFixtureCodeContract();
        var existingData = Files.createDirectories(fixture.layout.data().resolve("data")).resolve("routes.json");
        Files.writeString(existingData, "existing route fixture bytes");

        // when
        preparation().prepare(incoming, apiEnvironment);

        // then
        assertFalse(databaseApproved);
        assertFalse(Json.read(fixture.layout.data().resolve("deployment-preparation/current.json"))
            .path("dataTransition").asBoolean());
        assertFalse(Files.exists(fixture.layout.units().resolve("salmonbus-api.service.d/40-data-transition.conf")));
        assertEquals("existing route fixture bytes", Files.readString(existingData));
        assertEquals(true, preparation().recover(incoming, apiEnvironment).get("recoveryRequired"));
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertEquals("existing database contents\n", Files.readString(fixture.layout.data().resolve("database-sentinel")));
        assertEquals("existing route fixture bytes", Files.readString(existingData));
    }

    @Test
    void 파일_검증용_지문_분리_중_인프라_교체가_실패해도_기존_서비스를_복구한다() throws Exception {
        // given
        changeFixtureCodeContract();
        host.failLoad = true;

        // when
        var error = assertThrows(IllegalArgumentException.class,
            () -> preparation().prepare(incoming, apiEnvironment));

        // then
        assertEquals("DEV_PREPARATION_FAILED", error.getMessage());
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertFalse(databaseApproved);
        assertEquals("ROLLED_BACK", Json.read(fixture.layout.data()
            .resolve("deployment-preparation/current.json")).path("phase").asText());
    }

    @Test
    void 복구_완료_기록은_다음_실패에서_서비스를_다시_중지하지_않는다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();
        preparation().prepare(incoming, apiEnvironment);
        var journal = fixture.layout.data().resolve("deployment-preparation/current.json");
        var state = (tools.jackson.databind.node.ObjectNode) Json.read(journal);
        state.put("phase", "ROLLED_BACK");
        FileOps.atomicFile(journal, Json.bytes(state), 0600);
        host.apiActive = true;
        host.workerActive = true;
        host.calls.clear();

        // when
        var result = preparation().recover(incoming, apiEnvironment);
        var oldWorker = RevisionStore.verify(fixture.layout.root().resolve("worker/current").toRealPath(), true);
        DataTransition.requireApplication(fixture.layout, oldWorker);

        // then
        assertEquals(false, result.get("recoveryRequired"));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertTrue(host.calls.stream().noneMatch(c -> c.contains("stop")));
        assertEquals("ROLLED_BACK", Json.read(journal).path("phase").asText());
    }

    @Test
    void 만료된_승인은_앱을_중지하거나_복구_기록을_만들기_전에_거절한다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();
        var file = fixture.layout.data().resolve("deployment-preparation/approval.json");
        var approval = (tools.jackson.databind.node.ObjectNode) Json.read(file);
        approval.put("expiresAt", java.time.Instant.now().minusSeconds(1).toString());
        FileOps.atomicFile(file, Json.bytes(approval), 0600);

        // when
        var failure = assertThrows(IllegalArgumentException.class,
                () -> preparation().prepare(incoming, apiEnvironment));
        var recovery = preparation().recover(incoming, apiEnvironment);

        // then
        assertEquals("DEV_DATA_TRANSITION_APPROVAL", failure.getMessage());
        assertEquals(false, recovery.get("recoveryRequired"));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertFalse(Files.exists(fixture.layout.data().resolve("deployment-preparation/current.json")));
    }

    @Test
    void 승인_없이_데이터_계약을_바꾸면_앱을_중지하기_전에_거절한다() throws Exception {
        // given
        changeDataContract();

        // when
        var failure = assertThrows(IllegalArgumentException.class,
                () -> preparation().prepare(incoming, apiEnvironment));

        // then
        assertEquals("DEV_DATA_CHANGE_REQUIRES_PLAN", failure.getMessage());
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertFalse(databaseApproved);
    }

    @Test
    void 승인_명령은_정확한_두_배포판만_기록하고_앱이나_DB를_변경하지_않는다() throws Exception {
        // given
        changeDataContract();

        // when
        var result = DataTransition.authorize(fixture.layout, host, incoming, "dev-before-sal175");

        // then
        assertEquals(false, result.get("databaseChanged"));
        assertEquals(false, result.get("backupVerifiedByTool"));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertFalse(databaseApproved);
        var approval = DataTransition.approval(fixture.layout,
                RevisionStore.verify(original, true), RevisionStore.verify(incoming.resolve("basis"), true));
        assertEquals("dev-before-sal175", approval.backupReference());
        assertEquals(0600, FileOps.attribute(fixture.layout.data()
                .resolve("deployment-preparation/approval.json"), "mode") & 0777);
    }

    @Test
    void 승인된_계약_변경은_데이터를_보존하고_두_앱을_멈춘_뒤_DB_전환을_준비한다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();

        // when
        preparation().prepare(incoming, apiEnvironment);

        // then
        assertTrue(databaseApproved);
        assertFalse(host.apiActive);
        assertFalse(host.workerActive);
        assertEquals("existing database contents\n", Files.readString(fixture.layout.data().resolve("database-sentinel")));
        assertNotEquals(original, RevisionStore.selectedBasis(fixture.layout));
        DataTransition.requireApplication(fixture.layout, RevisionStore.verify(incoming, true));
        assertTrue(Files.readString(fixture.layout.units().resolve("salmonbus-worker.service.d/40-data-transition.conf"))
                .contains("basis/releases/" + RevisionStore.verify(incoming.resolve("basis"), true).releaseId()));
        var oldWorker = RevisionStore.verify(fixture.layout.root().resolve("worker/current").toRealPath(), true);
        assertThrows(IllegalArgumentException.class, () -> DataTransition.requireApplication(fixture.layout, oldWorker));
    }

    @Test
    void 전환_준비_뒤_배포가_실패하면_이전_앱을_자동으로_다시_켜지_않는다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();
        preparation().prepare(incoming, apiEnvironment);

        // when
        assertThrows(IllegalArgumentException.class, () -> preparation().recover(incoming, apiEnvironment));

        // then
        assertFalse(host.apiActive);
        assertFalse(host.workerActive);
        assertNotEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertEquals("RECOVERY_REQUIRED", Json.read(fixture.layout.data()
                .resolve("deployment-preparation/current.json")).path("phase").asText());
        assertThrows(IllegalArgumentException.class, () -> DataTransition.requireApplication(
                fixture.layout, RevisionStore.verify(incoming, true)));
        assertTrue(Files.exists(fixture.layout.units().resolve("salmonbus-worker.service.d/40-data-transition.conf")));
    }

    @Test
    void DB_승인에_실패해도_이전_앱을_자동으로_다시_켜지_않는다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();
        failDatabaseApproval = true;

        // when
        assertThrows(IllegalArgumentException.class, () -> preparation().prepare(incoming, apiEnvironment));

        // then
        assertFalse(databaseApproved);
        assertFalse(host.apiActive);
        assertFalse(host.workerActive);
    }

    @Test
    void 계약_변경을_승인해도_노선_테스트_데이터까지_바꾸지는_못한다() throws Exception {
        // given
        changeDataContract();
        var next = incoming.resolve("basis");
        Files.delete(next.resolve("revision.json"));
        Files.writeString(next.resolve("tools/data/routes.json"), "changed routes");
        RevisionStore.seal(next, "basis", "b".repeat(40), "2".repeat(64), "2".repeat(64), false);
        Files.delete(incoming.resolve("revision.json"));
        RevisionStore.seal(incoming, "api", "b".repeat(40), "3".repeat(64), "2".repeat(64), false);
        approveDataContract();

        // when
        assertThrows(IllegalArgumentException.class, () -> preparation().prepare(incoming, apiEnvironment));

        // then
        assertFalse(databaseApproved);
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertFalse(Files.exists(fixture.layout.data().resolve("deployment-preparation/current.json")));
        assertTrue(host.calls.stream().noneMatch(c -> c.contains("stop")));
    }

    @Test
    void API_검증이_끝나야_새_worker를_시작할_수_있다() throws Exception {
        // given
        changeDataContract();
        approveDataContract();
        preparation().prepare(incoming, apiEnvironment);
        var worker = application(base.resolve("transition-worker"), "worker", "2".repeat(64), "b".repeat(40), null);
        var metadata = RevisionStore.verify(worker, true);
        assertThrows(IllegalArgumentException.class, () -> DataTransition.requireApplication(fixture.layout, metadata));

        // when
        preparation().complete(incoming, apiEnvironment);

        // then
        DataTransition.requireApplication(fixture.layout, metadata);
        preparation().complete(worker, Map.of("APPLICATION_NAME", "salmonbus-backend-dev",
                "DEPLOYMENT_GROUP_NAME", "salmonbus-backend-dev-worker"));
        assertEquals("COMPLETE", Json.read(fixture.layout.data()
                .resolve("deployment-preparation/current.json")).path("phase").asText());
        assertFalse(Files.exists(fixture.layout.units().resolve("salmonbus-worker.service.d/40-data-transition.conf")));
        assertFalse(Files.exists(fixture.layout.units().resolve("salmonbus-api.service.d/40-data-transition.conf")));
    }

    @Test
    void apiHookPreparesChangedBasisAndLeavesApplicationsStoppedWithoutChangingData() throws Exception {
        var before = Files.readString(fixture.layout.config().resolve("api.env"));
        assertEquals(true, preparation().prepare(incoming, apiEnvironment).get("preparationRequired"));
        assertNotEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertFalse(host.apiActive);
        assertFalse(host.workerActive);
        assertEquals(before, Files.readString(fixture.layout.config().resolve("api.env")));
        assertEquals("existing database contents\n", Files.readString(fixture.layout.data().resolve("database-sentinel")));
        assertEquals("PREPARED", Json.read(fixture.layout.data().resolve("deployment-preparation/current.json")).path("phase").asText());
    }

    @Test
    void workerShutdownLongerThanThirtySecondsCanPrepareChangedBasis() throws Exception {
        host.workerStopCommand = List.of("/bin/sleep", "31");

        assertEquals(true, preparation().prepare(incoming, apiEnvironment).get("preparationRequired"));

        assertNotEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertFalse(host.apiActive);
        assertFalse(host.workerActive);
        assertEquals("PREPARED", Json.read(fixture.layout.data().resolve("deployment-preparation/current.json")).path("phase").asText());
    }

    @Test
    void failedWorkerShutdownRestoresPreviousServicesAndConfiguration() throws Exception {
        var before = Files.readString(fixture.layout.config().resolve("api.env"));
        host.workerStopCommand = List.of("/bin/sh", "-c", "exit 7");

        var error = assertThrows(IllegalArgumentException.class,
                () -> preparation().prepare(incoming, apiEnvironment));

        assertEquals("DEV_PREPARATION_FAILED", error.getMessage());
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertEquals(before, Files.readString(fixture.layout.config().resolve("api.env")));
        assertEquals("old worker unit\n", Files.readString(fixture.layout.units().resolve("salmonbus-worker.service")));
        assertEquals("existing database contents\n", Files.readString(fixture.layout.data().resolve("database-sentinel")));
        assertEquals("ROLLED_BACK", Json.read(fixture.layout.data().resolve("deployment-preparation/current.json")).path("phase").asText());
    }

    @Test
    void unchangedContractDoesNotStopApplicationsOrReloadInfrastructure() throws Exception {
        var same = application(base.resolve("same-api"), "api", "1".repeat(64), "c".repeat(40),
                basis(base.resolve("same-basis"), "1".repeat(64), "c".repeat(40)));
        assertEquals(false, preparation().prepare(same, apiEnvironment).get("preparationRequired"));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertTrue(host.calls.stream().noneMatch(c -> c.contains("stop") || c.contains("load")));
    }

    @Test
    void productionGroupsAndCorruptedEmbeddedBasisAreRejectedBeforeStoppingApps() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> preparation().prepare(incoming,
                Map.of("APPLICATION_NAME", "salmonbus-backend", "DEPLOYMENT_GROUP_NAME", "salmonbus-backend-api")));
        Files.writeString(incoming.resolve("basis/infra/compose.yaml"), "modified artifact");
        assertThrows(IllegalArgumentException.class, () -> preparation().prepare(incoming, apiEnvironment));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
    }

    @Test
    void failedBasisLoadRestoresPreviousServicesAndConfiguration() throws Exception {
        host.failLoad = true;
        assertThrows(IllegalArgumentException.class, () -> preparation().prepare(incoming, apiEnvironment));
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
        assertEquals("old api unit\n", Files.readString(fixture.layout.units().resolve("salmonbus-api.service")));
        assertEquals("ROLLED_BACK", Json.read(fixture.layout.data().resolve("deployment-preparation/current.json")).path("phase").asText());
    }

    @Test
    void laterHookFailureRestoresUnitsLinksAndManualOverrides() throws Exception {
        var drop = fixture.layout.units().resolve("salmonbus-api.service.d/30-dev-rds.conf");
        Files.createDirectories(drop.getParent());
        Files.writeString(drop, "# existing manual override\n");
        var before = fixture.layout.root().resolve("api/current").toRealPath();
        preparation().prepare(incoming, apiEnvironment);
        Files.delete(drop);
        Files.writeString(fixture.layout.units().resolve("salmonbus-api.service"), "new unit\n");
        host.workerStopCommand = List.of("/bin/sleep", "31");
        assertEquals(true, preparation().recover(incoming, apiEnvironment).get("recoveryRequired"));
        assertEquals(original, RevisionStore.selectedBasis(fixture.layout));
        assertEquals(before, fixture.layout.root().resolve("api/current").toRealPath());
        assertEquals("# existing manual override\n", Files.readString(drop));
        assertTrue(host.apiActive);
        assertTrue(host.workerActive);
    }

    @Test
    void rdsConfigurationSurvivesAutomaticBasisUpdateAndManualOverridesAreRetired() throws Exception {
        var config = fixture.layout.config();
        var certificate = config.resolve(DatabaseTarget.CERTIFICATE);
        FileOps.writeNew(certificate, "test CA bytes\n".getBytes(), 0644);
        FileOps.writeNew(config.resolve(DatabaseTarget.FILE), Json.bytes(Map.of(
                "environment", "dev", "dbInstanceIdentifier", "salmonbus-dev-db",
                "dbInstanceArn", "arn:aws:rds:ap-northeast-2:000000000000:db:salmonbus-dev-db",
                "endpoint", "salmonbus-dev-db.example.ap-northeast-2.rds.amazonaws.com",
                "port", 5432, "certificateSha256", FileOps.sha(certificate))), 0600);
        var url = DatabaseTarget.url(config);
        for (var name : List.of("api", "worker")) {
            var file = config.resolve(name + ".env");
            var values = new java.util.TreeMap<>(Environment.parse(FileOps.privateText(file), Environment.KEYS.get(name)));
            values.put("DB_URL", url);
            FileOps.atomicFile(file, Environment.text(values).getBytes(), 0600);
            var drop = fixture.layout.units().resolve("salmonbus-" + name + ".service.d/30-dev-rds.conf");
            Files.createDirectories(drop.getParent());
            Files.writeString(drop, "# salmonbus-dev-rds-manual dc47e6e7\n[Service]\n");
            FileOps.mode(drop, 0644);
        }
        var before = Environment.load(config);
        preparation().prepare(incoming, apiEnvironment);
        var after = Environment.load(config);
        for (var name : List.of("api", "worker", "postgres")) {
            assertTrue(before.get(name).equals(after.get(name)), name + " settings must be preserved");
        }
        assertEquals(RevisionStore.verify(incoming.resolve("basis"), true).commit(),
                after.get("infra").get("DEV_REVISION"));
        preparation().complete(incoming, apiEnvironment);
        assertFalse(Files.exists(fixture.layout.units().resolve("salmonbus-api.service.d/30-dev-rds.conf")));
        assertTrue(Files.exists(fixture.layout.units().resolve("salmonbus-worker.service.d/30-dev-rds.conf")));
        var worker = application(base.resolve("rds-worker"), "worker", "2".repeat(64), "b".repeat(40), null);
        preparation().complete(worker, Map.of("APPLICATION_NAME", "salmonbus-backend-dev",
                "DEPLOYMENT_GROUP_NAME", "salmonbus-backend-dev-worker"));
        assertFalse(Files.exists(fixture.layout.units().resolve("salmonbus-worker.service.d/30-dev-rds.conf")));
        assertEquals(url, Environment.load(config).get("worker").get("DB_URL"));
    }

    @Test
    void completedReleaseCannotBeRolledBackByALaterFailedHook() throws Exception {
        preparation().prepare(incoming, apiEnvironment);
        preparation().complete(incoming, apiEnvironment);
        var worker = application(base.resolve("new-worker"), "worker", "2".repeat(64), "b".repeat(40), null);
        var workerEnvironment = Map.of("APPLICATION_NAME", "salmonbus-backend-dev",
                "DEPLOYMENT_GROUP_NAME", "salmonbus-backend-dev-worker");
        preparation().complete(worker, workerEnvironment);
        assertEquals(false, preparation().recover(incoming, apiEnvironment).get("recoveryRequired"));
        assertNotEquals(original, RevisionStore.selectedBasis(fixture.layout));
    }

    private static class PreparationHost extends Fixtures.FakeHost {
        boolean apiActive = true;
        boolean workerActive = true;
        boolean infraActive = true;
        boolean failLoad;
        List<String> workerStopCommand;

        @Override
        tools.jackson.databind.JsonNode identity() {
            var value = (tools.jackson.databind.node.ObjectNode) super.identity();
            value.put("accountId", "000000000000");
            return value;
        }

        @Override
        Result run(Path cwd, Map<String, String> environment, Duration timeout, List<String> command) {
            if (command.getFirst().equals("docker")) {
                calls.add(command);
                if (command.contains("load")) {
                    if (failLoad) { failLoad = false; return new Result(1, "", ""); }
                    return new Result(0, "", "");
                }
                if (command.contains("inspect")) {
                    return new Result(0, "[{\"Id\":\"sha256:fixture\",\"Architecture\":\"arm64\"}]", "");
                }
            }
            if (command.getFirst().equals("systemctl")) {
                calls.add(command);
                var unit = command.stream().filter(value -> value.startsWith("salmonbus-"))
                        .findFirst().orElse("").replace(".service", "");
                final boolean active = unit.equals("salmonbus-api") ? apiActive
                        : unit.equals("salmonbus-worker") ? workerActive : infraActive;
                if (command.contains("show")) return new Result(0, active ? "active\n" : "inactive\n", "");
                if (command.contains("is-active")) return new Result(active ? 0 : 3, active ? "active\n" : "inactive\n", "");
                if (command.contains("stop") && unit.equals("salmonbus-worker") && workerStopCommand != null) {
                    var stopCommand = workerStopCommand;
                    workerStopCommand = null;
                    try {
                        var result = new CommandRunner().run(cwd, environment, timeout, stopCommand);
                        if (result.exit() != 0) return result;
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                }
                if (command.contains("stop") || command.contains("start")) {
                    final boolean next = command.contains("start");
                    switch (unit) {
                        case "salmonbus-api" -> apiActive = next;
                        case "salmonbus-worker" -> workerActive = next;
                        default -> infraActive = next;
                    }
                }
                return new Result(0, "", "");
            }
            return super.run(cwd, environment, timeout, command);
        }
    }
}
