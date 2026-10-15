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
        for (var name : List.of("tools/api.yml", "tools/worker.yml", "config/mongodb.yml",
                "systemd/salmonbus-dev-infra.service", "infra/compose.yaml", "infra/init-chat.js",
                "tools/data-contract.sha256", "wiremock-image.tar")) {
            var file = path.resolve(name);
            Files.createDirectories(file.getParent());
            var text = switch (name) {
                case "tools/api.yml" -> Files.readString(fixture.source.resolve("api.yml"));
                case "tools/worker.yml" -> Files.readString(fixture.source.resolve("worker.yml"));
                case "config/mongodb.yml" -> Files.readString(fixture.source.resolveSibling("config/mongodb.yml"));
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
        return new DeploymentPreparation(fixture.layout, host);
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
        for (var name : List.of("api", "worker", "postgres", "mongodb")) {
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
