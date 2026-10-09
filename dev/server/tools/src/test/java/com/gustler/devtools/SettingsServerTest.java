package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

@Tag("linux-root")
class SettingsServerTest {
    @TempDir Path base;
    private Fixtures fixture;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root(), "Run server tests in a disposable Linux root container.");
        fixture = new Fixtures(base);
    }

    private Map<String, String> hashes() throws Exception {
        var result = new TreeMap<String, String>();
        try (var paths = Files.list(fixture.layout.config())) {
            for (var path : paths.toList()) {
                result.put(path.getFileName().toString(), FileOps.sha(path));
            }
        }
        return result;
    }

    @Test
    void preparesPrivateSettingsAndReadableYaml() throws Exception {
        assertEquals("prepared", fixture.settings());
        assertEquals(0750, FileOps.attribute(fixture.layout.config(), "mode") & 0777);
        assertEquals(9, hashes().size());
        try (var paths = Files.list(fixture.layout.config())) {
            for (var path : paths.toList()) {
                assertEquals(
                        path.toString().endsWith(".yml") ? 0644 : 0600,
                        FileOps.attribute(path, "mode") & 0777);
            }
        }
        Environment.load(fixture.layout.config());
    }

    @Test
    void repeatedPreparationPreservesCredentialsAndDatabaseFiles() throws Exception {
        fixture.settings();
        var before = hashes();
        var sentinel =
                Files.createDirectories(fixture.layout.data().resolve("postgres"))
                        .resolve("keep-data");
        Files.writeString(sentinel, "existing development data");
        assertEquals("preserved", fixture.settings());
        assertEquals(before, hashes());
        assertEquals("existing development data", Files.readString(sentinel));
    }

    @Test
    void differentOriginCommitVolumeAndInstanceCannotOverwriteSettings() throws Exception {
        fixture.settings();
        var before = hashes();
        var preparation = new SettingsPreparation(fixture.layout);
        for (var values :
                java.util.List.of(
                        java.util.List.of(
                                "https://other.example.test",
                                Fixtures.COMMIT,
                                "test-volume",
                                "test-dev-instance"),
                        java.util.List.of(
                                Fixtures.ORIGIN,
                                "b".repeat(40),
                                "test-volume",
                                "test-dev-instance"),
                        java.util.List.of(
                                Fixtures.ORIGIN,
                                Fixtures.COMMIT,
                                "other-volume",
                                "test-dev-instance"),
                        java.util.List.of(
                                Fixtures.ORIGIN,
                                Fixtures.COMMIT,
                                "test-volume",
                                "other-instance"))) {
            var identity =
                    Json.read(
                            Json.bytes(
                                    Map.of(
                                            "instanceId",
                                            values.get(3),
                                            "accountId",
                                            "test-account")));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            preparation.initialize(
                                    fixture.source,
                                    identity,
                                    values.get(2),
                                    values.get(0),
                                    values.get(1),
                                    65534));
            assertEquals(before, hashes());
        }
    }

    @Test
    void orphanedDatabaseAndPartialConfigurationAreNotReset() throws Exception {
        var sentinel =
                Files.createDirectories(fixture.layout.data().resolve("mongodb"))
                        .resolve("keep-data");
        Files.writeString(sentinel, "existing data");
        assertThrows(IllegalArgumentException.class, fixture::settings);
        assertFalse(Files.exists(fixture.layout.config()));
        assertEquals("existing data", Files.readString(sentinel));
        Files.delete(sentinel);
        Files.createDirectory(fixture.layout.config());
        FileOps.owner(fixture.layout.config(), 0, 65534);
        FileOps.mode(fixture.layout.config(), 0750);
        var partial = fixture.layout.config().resolve("postgres.env");
        FileOps.writeNew(partial, "existing-private-settings\n".getBytes(), 0600);
        assertThrows(java.nio.file.NoSuchFileException.class, fixture::settings);
        assertEquals("existing-private-settings\n", Files.readString(partial));
    }

    @Test
    void symlinkDestinationAndReadableSecretsAreRejected() throws Exception {
        var outside = Files.createDirectory(base.resolve("outside"));
        Files.createSymbolicLink(fixture.layout.config(), outside);
        assertThrows(IllegalArgumentException.class, fixture::settings);
        assertEquals(0, Files.list(outside).count());
        Files.delete(fixture.layout.config());
        fixture.settings();
        var before = hashes();
        FileOps.mode(fixture.layout.config().resolve("worker.env"), 0644);
        assertThrows(IllegalArgumentException.class, fixture::settings);
        assertEquals(before, hashes());
    }

    @Test
    void serviceUserCanReadYamlButCannotReadSecretFiles() throws Exception {
        fixture.settings();
        FileOps.mode(base, 0755);
        FileOps.mode(base.resolve("etc"), 0755);
        var config = fixture.layout.config().toString();
        var script = "test -r '" + config + "/api.yml' && test -r '" + config + "/worker.yml'";
        for (var name :
                java.util.List.of(
                        "api.env",
                        "worker.env",
                        "postgres.env",
                        "mongodb.env",
                        "environment.json")) {
            script += " && test ! -r '" + config + "/" + name + "'";
        }
        var result =
                new Host()
                        .run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(10),
                                java.util.List.of(
                                        "runuser", "-u", "nobody", "--", "/bin/sh", "-c", script));
        assertEquals(0, result.exit());
    }

    @Test
    void interruptedStagingDoesNotPublishPartialConfiguration() throws Exception {
        int[] writes = {0};
        var preparation =
                new SettingsPreparation(
                        fixture.layout,
                        (path, content, mode) -> {
                            FileOps.writeNew(path, content, mode);
                            if (++writes[0] == 2) {
                                throw new java.io.IOException("Simulated write interruption");
                            }
                        });
        assertThrows(
                java.io.IOException.class,
                () ->
                        preparation.initialize(
                                fixture.source,
                                fixture.host.identity(),
                                "test-volume",
                                Fixtures.ORIGIN,
                                Fixtures.COMMIT,
                                65534));
        assertFalse(Files.exists(fixture.layout.config()));
        assertEquals("prepared", fixture.settings());
    }

    @Test
    void sigkillReleasesLockForNextPreparation() throws Exception {
        var lock = fixture.layout.locks().resolve("killed.lock");
        var classpath =
                LockProbe.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                        + java.io.File.pathSeparator
                        + FileOps.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .getPath();
        var process =
                new ProcessBuilder(
                                "/usr/bin/java",
                                "-cp",
                                classpath,
                                LockProbe.class.getName(),
                                lock.toString())
                        .start();
        try {
            assertEquals("READY", process.inputReader().readLine());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        try (var ignored = FileOps.lock(lock)) {}
                    });
            process.destroyForcibly().waitFor();
            try (var lease = FileOps.lock(lock)) {
                assertTrue(lease.lock().isValid());
            }
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    void preflightRejectsWrongInstanceVolumeAndConfigurationHash() throws Exception {
        fixture.settings();
        new Preflight(fixture.layout, fixture.host).check("prepare", Map.of());
        var wrongIdentity =
                new Fixtures.FakeHost() {
                    @Override
                    tools.jackson.databind.JsonNode identity() {
                        return Json.read(
                                Json.bytes(
                                        Map.of(
                                                "instanceId",
                                                "other-instance",
                                                "accountId",
                                                "test-account")));
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () -> new Preflight(fixture.layout, wrongIdentity).check("prepare", Map.of()));
        var wrongVolume =
                new Fixtures.FakeHost() {
                    @Override
                    tools.jackson.databind.JsonNode mounted(Path path) {
                        return Json.read(
                                Json.bytes(Map.of("uuid", "other-volume", "fstype", "xfs")));
                    }
                };
        assertThrows(
                IllegalArgumentException.class,
                () -> new Preflight(fixture.layout, wrongVolume).check("prepare", Map.of()));
        Files.writeString(fixture.layout.config().resolve("api.yml"), "changed configuration\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> new Preflight(fixture.layout, fixture.host).check("prepare", Map.of()));
    }

    public static class LockProbe {
        public static void main(String[] args) throws Exception {
            try (var lease = FileOps.lock(Path.of(args[0]))) {
                System.out.println("READY");
                System.out.flush();
                Thread.sleep(30000);
            }
        }
    }
}
