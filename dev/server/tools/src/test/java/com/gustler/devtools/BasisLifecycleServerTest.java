package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Tag("linux-root")
class BasisLifecycleServerTest {
    @TempDir Path base;
    private Fixtures fixture;
    private UpdateHost host;
    private Path original;
    private Path incoming;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root());
        fixture = new Fixtures(base);
        fixture.settings();
        host = new UpdateHost();
        host.data = fixture.layout.data();
        var releases = Files.createDirectories(fixture.layout.root().resolve("basis/releases"));
        FileOps.mode(releases.getParent(), 0755);
        FileOps.mode(releases, 0755);
        original =
                bundle(
                        "old",
                        "1".repeat(64),
                        "a".repeat(40),
                        Files.readString(fixture.source.resolve("api.yml")));
        var metadata = RevisionStore.verify(original, true);
        var installed = releases.resolve(metadata.releaseId());
        Files.move(original, installed);
        original = installed;
        Files.createSymbolicLink(
                fixture.layout.root().resolve("basis/current"),
                Path.of("releases/" + metadata.releaseId()));
        Files.createSymbolicLink(fixture.layout.tools(), Path.of("basis/current/tools"));
        RevisionStore.ready(fixture.layout, metadata);
        var unit = fixture.layout.units().resolve("salmonbus-dev-infra.service");
        Files.copy(original.resolve("systemd/salmonbus-dev-infra.service"), unit);
        FileOps.mode(unit, 0644);
        Files.writeString(fixture.layout.data().resolve("keep-database"), "database history\n");
        Files.createDirectories(fixture.layout.data().resolve("models"));
        Files.writeString(
                fixture.layout.data().resolve("models/keep-active-model"),
                "active model sentinel\n");
        incoming = bundle("next", "2".repeat(64), "b".repeat(40), "new api config\n");
    }

    private Path bundle(String name, String digest, String commit, String api) throws Exception {
        var path = Files.createDirectory(base.resolve(name));
        for (var file :
                List.of(
                        "tools/api.yml",
                        "tools/worker.yml",
                        "config/mongodb.yml",
                        "systemd/salmonbus-dev-infra.service",
                        "infra/compose.yaml",
                        "infra/init-chat.js",
                        "tools/data-contract.sha256",
                        "wiremock-image.tar")) {
            var target = path.resolve(file);
            Files.createDirectories(target.getParent());
            var content = file.equals("tools/api.yml") ? api : "stable:" + file + "\n";
            if (file.equals("tools/worker.yml")) {
                content = Files.readString(fixture.source.resolve("worker.yml"));
            }
            if (file.equals("config/mongodb.yml")) {
                content =
                        Files.readString(
                                fixture.source.resolveSibling("config").resolve("mongodb.yml"));
            }
            if (file.equals("systemd/salmonbus-dev-infra.service")) {
                content = "unit:" + name + "\n";
            }
            Files.writeString(target, content);
        }
        for (var file : DataPreparation.FILES) {
            var target = path.resolve("tools/data/" + file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "stable:" + file);
        }
        Files.writeString(
                path.resolve("wiremock-image.json"),
                Json.MAPPER.writeValueAsString(
                        Map.of(
                                "tag",
                                "salmonbus-gbis-replay:" + commit,
                                "id",
                                "sha256:fixture",
                                "architecture",
                                "arm64")));
        RevisionStore.seal(path, "basis", commit, digest, digest, false);
        return path;
    }

    private Map<String, String> secrets() throws Exception {
        var result = new TreeMap<String, String>();
        for (var name : List.of("api", "worker", "postgres", "mongodb")) {
            result.put(name, FileOps.sha(fixture.layout.config().resolve(name + ".env")));
        }
        return result;
    }

    private void preserved(Map<String, String> before) throws Exception {
        assertEquals(before, secrets());
        assertEquals(
                "database history\n",
                Files.readString(fixture.layout.data().resolve("keep-database")));
        assertEquals(
                "active model sentinel\n",
                Files.readString(fixture.layout.data().resolve("models/keep-active-model")));
        assertTrue(
                host.calls.stream()
                        .noneMatch(
                                command ->
                                        command.contains("runuser")
                                                || command.contains("psql")
                                                || command.contains("--shared-dev")
                                                || command.contains("down")));
    }

    @Test
    void checkModeDoesNotPublishConfigurationOrRestartServices() throws Exception {
        var before = secrets();
        var current = fixture.layout.root().resolve("basis/current").toRealPath();
        var result = new BasisLifecycle(fixture.layout, host).change(incoming, false);
        assertEquals("check", result.get("mode"));
        assertEquals(current, fixture.layout.root().resolve("basis/current").toRealPath());
        assertFalse(Files.exists(fixture.layout.root().resolve("basis/operation.json")));
        assertTrue(
                host.calls.stream()
                        .noneMatch(
                                command ->
                                        command.contains("stop")
                                                || command.contains("start")
                                                || command.contains("load")));
        preserved(before);
    }

    @Test
    void updateAndExplicitRestorePreserveCredentialsHistoryAndModel() throws Exception {
        var before = secrets();
        var lifecycle = new BasisLifecycle(fixture.layout, host);
        lifecycle.change(incoming, true);
        var selected = fixture.layout.root().resolve("basis/current").toRealPath();
        assertEquals(
                RevisionStore.verify(incoming, true).releaseId(),
                selected.getFileName().toString());
        assertEquals(
                "new api config\n", Files.readString(fixture.layout.config().resolve("api.yml")));
        RevisionStore.requireReady(fixture.layout, RevisionStore.verify(selected, true));
        assertEquals(original, fixture.layout.root().resolve("basis/previous").toRealPath());
        preserved(before);
        lifecycle.change(original, true);
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        assertEquals(
                Files.readString(fixture.source.resolve("api.yml")),
                Files.readString(fixture.layout.config().resolve("api.yml")));
        preserved(before);
    }

    @Test
    void restartFailureAutomaticallyRestoresPreviousConfiguration() throws Exception {
        var before = secrets();
        host.startFailures = 1;
        assertEquals(
                "DEV_BASIS_CHANGE_FAILED",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        new BasisLifecycle(fixture.layout, host)
                                                .change(incoming, true))
                        .getMessage());
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        assertFalse(Files.exists(fixture.layout.root().resolve("basis/operation.json")));
        assertFalse(Files.exists(fixture.layout.root().resolve(".deploying")));
        preserved(before);
    }

    @Test
    void failedRecoveryBlocksDeploymentUntilExplicitRecovery() throws Exception {
        var before = secrets();
        host.startFailures = 2;
        var lifecycle = new BasisLifecycle(fixture.layout, host);
        assertEquals(
                "DEV_BASIS_RECOVERY_REQUIRED",
                assertThrows(IllegalArgumentException.class, () -> lifecycle.change(incoming, true))
                        .getMessage());
        assertTrue(Files.exists(fixture.layout.root().resolve("basis/operation.json")));
        assertThrows(
                IllegalArgumentException.class,
                () -> RevisionStore.component(incoming, fixture.layout, host, Map.of()));
        assertEquals("check", lifecycle.recover(false).get("mode"));
        lifecycle.recover(true);
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        assertFalse(Files.exists(fixture.layout.root().resolve("basis/operation.json")));
        preserved(before);
    }

    @Test
    void failureBeforeConfigurationSwapPreservesTheSelectedBasis() throws Exception {
        var before = secrets();
        host.loadFailures = 1;
        assertThrows(
                IllegalArgumentException.class,
                () -> new BasisLifecycle(fixture.layout, host).change(incoming, true));
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        preserved(before);
    }

    @Test
    void recoveryRestoresConfigurationMissingBetweenDirectoryRenames() throws Exception {
        var before = secrets();
        var next = RevisionStore.verify(incoming, true);
        var installed = fixture.layout.root().resolve("basis/releases/" + next.releaseId());
        Files.move(incoming, installed);
        var operation =
                new BasisLifecycle.Operation(
                        "a".repeat(32),
                        RevisionStore.verify(original, true).releaseId(),
                        next.releaseId(),
                        true);
        Files.createDirectories(operation.unitBackup(fixture.layout).getParent());
        FileOps.writeNew(
                operation.unitBackup(fixture.layout),
                Files.readAllBytes(fixture.layout.units().resolve("salmonbus-dev-infra.service")),
                0600);
        new SettingsPreparation(fixture.layout)
                .replacement(
                        installed.resolve("tools"),
                        operation.stage(fixture.layout),
                        host.identity(),
                        "test-volume",
                        next.commit(),
                        host.account().gid());
        BasisLifecycle.claim(fixture.layout, operation.id());
        FileOps.atomicFile(
                fixture.layout.root().resolve("basis/operation.json"), Json.bytes(operation), 0600);
        FileOps.moveNew(fixture.layout.config(), operation.backup(fixture.layout));
        assertFalse(Files.exists(fixture.layout.config()));
        new BasisLifecycle(fixture.layout, host).recover(true);
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        preserved(before);
    }

    @Test
    void repeatUpdateDoesNotRestartTheInfrastructure() throws Exception {
        var before = secrets();
        var result = new BasisLifecycle(fixture.layout, host).change(original, true);
        assertEquals(true, result.get("alreadySelected"));
        assertTrue(
                host.calls.stream()
                        .noneMatch(
                                command ->
                                        command.contains("stop")
                                                || command.contains("start")
                                                || command.contains("load")));
        preserved(before);
    }

    @Test
    void orphanedBasisMarkerNeedsExplicitRecoveryAndDoesNotTouchData() throws Exception {
        var before = secrets();
        BasisLifecycle.claim(fixture.layout, "a".repeat(32));
        var lifecycle = new BasisLifecycle(fixture.layout, host);
        assertEquals(true, lifecycle.recover(false).get("recoveryRequired"));
        assertTrue(Files.exists(fixture.layout.root().resolve(".deploying")));
        lifecycle.recover(true);
        assertFalse(Files.exists(fixture.layout.root().resolve(".deploying")));
        assertEquals(original, fixture.layout.root().resolve("basis/current").toRealPath());
        preserved(before);
    }

    @Test
    void recoveryCannotRemoveAnApplicationDeploymentMarker() throws Exception {
        var marker = Files.createDirectory(fixture.layout.root().resolve(".deploying"));
        FileOps.writeNew(marker.resolve("owner"), "api other-deployment 1\n".getBytes(), 0600);
        assertThrows(
                IllegalArgumentException.class,
                () -> new BasisLifecycle(fixture.layout, host).recover(true));
        assertEquals("api other-deployment 1\n", Files.readString(marker.resolve("owner")));
    }

    @Test
    void changedFixtureAndDatabaseConfigurationRequireASeparatePlan() throws Exception {
        for (var file :
                List.of(
                        "tools/data/routes.json",
                        "tools/data-contract.sha256",
                        "infra/compose.yaml")) {
            var candidate =
                    bundle(
                            "changed-" + java.util.UUID.randomUUID(),
                            "3".repeat(64),
                            "c".repeat(40),
                            "changed config\n");
            Files.delete(candidate.resolve("revision.json"));
            Files.writeString(candidate.resolve(file), "changed data contract\n");
            RevisionStore.seal(
                    candidate, "basis", "c".repeat(40), "3".repeat(64), "3".repeat(64), false);
            assertEquals(
                    "DEV_DATA_CHANGE_REQUIRES_PLAN",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            new BasisLifecycle(fixture.layout, host)
                                                    .change(candidate, false))
                            .getMessage());
        }
        assertTrue(host.calls.isEmpty());
    }

    @Test
    void unreadyBasisAndConcurrentApplicationDeploymentAreRejected() throws Exception {
        Files.delete(fixture.layout.root().resolve("basis/ready.json"));
        assertThrows(
                Exception.class,
                () -> new BasisLifecycle(fixture.layout, host).change(incoming, true));
        RevisionStore.ready(fixture.layout, RevisionStore.verify(original, true));
        var marker = Files.createDirectory(fixture.layout.root().resolve(".deploying"));
        Files.writeString(marker.resolve("owner"), "api other-deployment 1\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> new BasisLifecycle(fixture.layout, host).change(incoming, true));
        assertEquals("api other-deployment 1\n", Files.readString(marker.resolve("owner")));
        assertTrue(host.calls.isEmpty());
    }

    private static class UpdateHost extends Fixtures.FakeHost {
        int startFailures;
        int loadFailures;

        @Override
        Result run(
                Path cwd, Map<String, String> environment, Duration timeout, List<String> command) {
            if (command.getFirst().equals("docker")) {
                calls.add(command);
                if (command.contains("load")) {
                    if (loadFailures-- > 0) {
                        return new Result(1, "", "");
                    }
                    return new Result(0, "", "");
                }
                if (command.contains("inspect")) {
                    return new Result(
                            0, "[{\"Id\":\"sha256:fixture\",\"Architecture\":\"arm64\"}]", "");
                }
            }
            if (command.getFirst().equals("systemctl")
                    && command.contains("salmonbus-dev-infra.service")) {
                calls.add(command);
                if (command.contains("show")) {
                    return new Result(0, "active\n", "");
                }
                if (command.contains("start") && startFailures-- > 0) {
                    return new Result(1, "", "");
                }
                return new Result(0, "", "");
            }
            return super.run(cwd, environment, timeout, command);
        }
    }
}
