package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux-root")
class ArrivalSettingsServerTest {
    @TempDir Path base;
    private Fixtures fixture;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root(), "Use a disposable Linux root container.");
        fixture = new Fixtures(base);
        fixture.settings();
    }

    private Map<String, String> secrets() throws Exception {
        var values = new TreeMap<String, String>();
        for (var name : Environment.KEYS.keySet()) {
            values.put(name, FileOps.sha(fixture.layout.config().resolve(name + ".env")));
        }
        return values;
    }

    @Test
    void legacySettingsGainOnePrivateSharedTokenWithoutChangingExistingSecrets() throws Exception {
        var before = secrets();
        assertFalse(Files.exists(fixture.layout.config().resolve(ArrivalSettings.FILE)));
        new Preflight(fixture.layout, fixture.host).check("prepare", Map.of());
        assertTrue(ArrivalSettings.ensure(fixture.layout.config()));
        var file = fixture.layout.config().resolve(ArrivalSettings.FILE);
        assertEquals(0600, FileOps.attribute(file, "mode") & 0777);
        assertTrue(ArrivalSettings.load(fixture.layout.config()).get("ARRIVAL_INTERNAL_TOKEN").length() == 64);
        var hash = FileOps.sha(file);
        assertFalse(ArrivalSettings.ensure(fixture.layout.config()));
        assertTrue(hash.equals(FileOps.sha(file)), "A deployment must not rotate the shared token.");
        assertEquals(before, secrets());
    }

    @Test
    void basisReplacementPreservesHotfixTokenAndExistingDatabaseCredentials() throws Exception {
        ArrivalSettings.ensure(fixture.layout.config());
        var file = fixture.layout.config().resolve(ArrivalSettings.FILE);
        var before = FileOps.sha(file);
        var destination = base.resolve("replacement");
        new SettingsPreparation(fixture.layout).replacement(fixture.source, destination,
                fixture.host.identity(), "test-volume", "b".repeat(40), fixture.host.account().gid());
        assertTrue(before.equals(FileOps.sha(destination.resolve(ArrivalSettings.FILE))));
        assertEquals(0600, FileOps.attribute(destination.resolve(ArrivalSettings.FILE), "mode") & 0777);
        for (var name : java.util.List.of("api", "worker", "postgres", "mongodb")) {
            assertTrue(FileOps.sha(fixture.layout.config().resolve(name + ".env"))
                    .equals(FileOps.sha(destination.resolve(name + ".env"))));
        }
    }

    @Test
    void insecureOrInvalidExistingSidecarsCannotBeOverwrittenOrActivated() throws Exception {
        ArrivalSettings.ensure(fixture.layout.config());
        var file = fixture.layout.config().resolve(ArrivalSettings.FILE);
        var original = Files.readAllBytes(file);
        FileOps.mode(file, 0644);
        assertThrows(IllegalArgumentException.class, () -> ArrivalSettings.ensure(fixture.layout.config()));
        FileOps.mode(file, 0600);
        Files.writeString(file, Environment.text(Map.of("ARRIVALS_ENABLED", "true",
                "ARRIVAL_INTERNAL_TOKEN", "a".repeat(64),
                "ARRIVAL_WORKER_BASE_URL", "http://external.invalid:8081")));
        var invalidHash = FileOps.sha(file);
        assertThrows(IllegalArgumentException.class, () -> Environment.load(fixture.layout.config()));
        assertTrue(invalidHash.equals(FileOps.sha(file)));
        Files.delete(file);
        var target = base.resolve("private-target");
        FileOps.writeNew(target, original, 0600);
        Files.createSymbolicLink(file, target);
        assertThrows(IllegalArgumentException.class, () -> ArrivalSettings.ensure(fixture.layout.config()));
    }
}
