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
class DataServerTest {
    @TempDir Path base;
    private Path source;
    private Path destination;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root());
        source = Files.createDirectory(base.resolve("source"));
        destination = base.resolve("published");
        for (var name : DataPreparation.FILES) {
            var file = source.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fixture:" + name);
        }
    }

    private Map<String, String> hashes() throws Exception {
        var result = new TreeMap<String, String>();
        for (var name : DataPreparation.FILES) {
            result.put(name, FileOps.sha(destination.resolve(name)));
        }
        return result;
    }

    @Test
    void publishesReadOnlyFixturesAndPreservesRepeatedPublication() throws Exception {
        DataPreparation.publish(source, destination, 65534);
        assertEquals(0750, FileOps.attribute(destination, "mode") & 0777);
        for (var name : DataPreparation.FILES) {
            assertEquals(0640, FileOps.attribute(destination.resolve(name), "mode") & 0777);
        }
        var before = hashes();
        DataPreparation.publish(source, destination, 65534);
        assertEquals(before, hashes());
    }

    @Test
    void changedFixtureDoesNotOverwritePublishedData() throws Exception {
        DataPreparation.publish(source, destination, 65534);
        var before = hashes();
        Files.writeString(source.resolve("routes.json"), "another fixture");
        assertThrows(
                IllegalArgumentException.class,
                () -> DataPreparation.publish(source, destination, 65534));
        assertEquals(before, hashes());
    }

    @Test
    void sourceSymlinkIsRejectedBeforePublication() throws Exception {
        Files.delete(source.resolve("routes.json"));
        Files.createSymbolicLink(source.resolve("routes.json"), Path.of("catalog-routes.json"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DataPreparation.publish(source, destination, 65534));
        assertFalse(Files.exists(destination));
    }

    @Test
    void destinationAndNestedSymlinksAreRejected() throws Exception {
        var outside = Files.createDirectory(base.resolve("outside"));
        Files.createSymbolicLink(destination, outside);
        assertThrows(
                IllegalArgumentException.class,
                () -> DataPreparation.publish(source, destination, 65534));
        assertEquals(0, Files.list(outside).count());
        Files.delete(destination);
        Files.createDirectory(destination);
        Files.createSymbolicLink(
                destination.resolve("model-reference"), source.resolve("model-reference"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DataPreparation.publish(source, destination, 65534));
    }

    @Test
    void serviceAccountCanReadButCannotChangeData() throws Exception {
        DataPreparation.publish(source, destination, 65534);
        FileOps.mode(base, 0755);
        var path = destination.resolve("routes.json").toString();
        var result =
                new Host()
                        .run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(10),
                                java.util.List.of(
                                        "runuser",
                                        "-u",
                                        "nobody",
                                        "--",
                                        "/bin/sh",
                                        "-c",
                                        "test -r '" + path + "' && test ! -w '" + path + "'"));
        assertEquals(0, result.exit());
    }

    @Test
    void activeAppsBlockPreparationBeforeLaunchingJava() throws Exception {
        var fixture = new Fixtures(Files.createDirectory(base.resolve("server")));
        fixture.settings();
        fixture.host.state = "active";
        assertEquals(
                "STOP_DEV_APPS_BEFORE_PREPARE",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> new DataPreparation(fixture.layout, fixture.host).prepare())
                        .getMessage());
        assertTrue(
                fixture.host.calls.stream().allMatch(call -> call.getFirst().equals("systemctl")));
    }
}
