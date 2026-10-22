package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Tag("linux-root")
class LoginSettingsServerTest {
    @TempDir Path base;
    private Fixtures fixture;
    private Path keys;
    private Path key;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root(), "Use a disposable Linux root container.");
        fixture = new Fixtures(base);
        fixture.settings();
        keys = fixture.layout.config().resolve(LoginSettings.APPLE_DIRECTORY);
        key = keys.resolve("AuthKey_KEY0000000.p8");
    }

    private void login(Map<String, String> values) throws Exception {
        var file = fixture.layout.config().resolve("api.env");
        var api =
                new HashMap<>(
                        Environment.parse(
                                Files.readString(file),
                                Environment.KEYS.get("api"),
                                Environment.OPTIONAL_KEYS.get("api")));
        api.putAll(values);
        Files.writeString(file, Environment.text(api));
        assertEquals(0600, FileOps.attribute(file, "mode") & 0777);
    }

    private void appleKey() throws Exception {
        Files.createDirectory(keys);
        FileOps.owner(keys, 0, fixture.host.account().gid());
        FileOps.mode(keys, 0750);
        FileOps.writeNew(key, "test apple key\n".getBytes(), 0640);
        FileOps.owner(key, 0, fixture.host.account().gid());
    }

    private void preflight() throws Exception {
        new Preflight(fixture.layout, fixture.host).check("prepare", Map.of());
    }

    private void rejected() {
        assertEquals(
                "DEV_AUTH_APPLE_KEY",
                assertThrows(IllegalArgumentException.class, this::preflight).getMessage());
    }

    @Test
    void existingSettingsWithoutLoginPassPreflight() throws Exception {
        preflight();
        assertFalse(Files.exists(keys));
    }

    @Test
    void stagedLoginValuesWhileOffDoNotNeedTheAppleKey() throws Exception {
        login(
                Map.of(
                        "AUTH_ENABLED",
                        "false",
                        "AUTH_APPLE_ENABLED",
                        "true",
                        "AUTH_APPLE_PRIVATE_KEY_PATH",
                        Fixtures.LOGIN.get("AUTH_APPLE_PRIVATE_KEY_PATH")));
        preflight();
    }

    @Test
    void completeLoginWithProtectedAppleKeyPassesPreflight() throws Exception {
        login(Fixtures.LOGIN);
        appleKey();
        preflight();
        FileOps.mode(key, 0440);
        preflight();
    }

    @Test
    void enabledAppleLoginRequiresGroupReadableRootKey() throws Exception {
        login(Fixtures.LOGIN);
        rejected();
        appleKey();
        for (var mode : java.util.List.of(0644, 0660, 0600, 0750, 0641)) {
            FileOps.mode(key, mode);
            rejected();
        }
        FileOps.mode(key, 0640);
        FileOps.owner(key, 0, 0);
        rejected();
        FileOps.owner(key, fixture.host.account().uid(), fixture.host.account().gid());
        rejected();
        Files.delete(key);
        var target = base.resolve("outside.p8");
        FileOps.writeNew(target, "test apple key\n".getBytes(), 0640);
        FileOps.owner(target, 0, fixture.host.account().gid());
        Files.createSymbolicLink(key, target);
        rejected();
        Files.delete(key);
        Files.createDirectory(key);
        rejected();
    }

    @Test
    void enabledAppleLoginRequiresServiceGroupKeyDirectory() throws Exception {
        login(Fixtures.LOGIN);
        appleKey();
        for (var mode : java.util.List.of(0755, 0770, 0700)) {
            FileOps.mode(keys, mode);
            rejected();
        }
        FileOps.mode(keys, 0750);
        FileOps.owner(keys, 0, 0);
        rejected();
    }

    @Test
    void basisReplacementPreservesAppleKeysAndLoginSettings() throws Exception {
        login(Fixtures.LOGIN);
        appleKey();
        var destination = base.resolve("replacement");
        new SettingsPreparation(fixture.layout)
                .replacement(
                        fixture.source,
                        destination,
                        fixture.host.identity(),
                        "test-volume",
                        "b".repeat(40),
                        fixture.host.account().gid());
        assertEquals(
                FileOps.sha(fixture.layout.config().resolve("api.env")),
                FileOps.sha(destination.resolve("api.env")));
        var copy = destination.resolve(LoginSettings.APPLE_DIRECTORY).resolve(key.getFileName());
        assertEquals(FileOps.sha(key), FileOps.sha(copy));
        for (var path : java.util.List.of(copy, copy.getParent())) {
            assertEquals(0, FileOps.attribute(path, "uid"));
            assertEquals(fixture.host.account().gid(), FileOps.attribute(path, "gid"));
        }
        assertEquals(0640, FileOps.attribute(copy, "mode") & 07777);
        assertEquals(0750, FileOps.attribute(copy.getParent(), "mode") & 07777);
    }

    @Test
    void basisReplacementCopiesAStagedAppleDirectoryAsItIs() throws Exception {
        appleKey();
        FileOps.mode(keys, 0755);
        FileOps.mode(key, 0600);
        var notes = keys.resolve("notes.txt");
        FileOps.writeNew(notes, "not a key\n".getBytes(), 0644);
        var backup = keys.resolve("AuthKey_KEY0000000.p8.bak");
        FileOps.writeNew(backup, "old apple key\n".getBytes(), 0600);
        FileOps.owner(backup, fixture.host.account().uid(), fixture.host.account().gid());
        preflight();
        var destination = base.resolve("replacement");
        replace(destination);
        var copies = destination.resolve(LoginSettings.APPLE_DIRECTORY);
        for (var path : java.util.List.of(keys, key, notes, backup)) {
            var copy = copies.resolve(keys.relativize(path));
            for (var name : java.util.List.of("uid", "gid", "mode")) {
                assertEquals(FileOps.attribute(path, name), FileOps.attribute(copy, name));
            }
            if (Files.isRegularFile(path)) {
                assertEquals(FileOps.sha(path), FileOps.sha(copy));
            }
        }
        try (var files = Files.list(copies)) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void appleDirectoryWithLinksOrSubdirectoriesStopsPreparationAndReplacement() throws Exception {
        appleKey();
        var link = keys.resolve("AuthKey_LINK000000.p8");
        Files.createSymbolicLink(link, key);
        refused("first");
        Files.delete(link);
        Files.createDirectory(keys.resolve("old"));
        refused("second");
        Files.delete(keys.resolve("old"));
        var moved = base.resolve("apple-elsewhere");
        Files.move(keys, moved);
        Files.createSymbolicLink(keys, moved);
        refused("third");
    }

    private void replace(Path destination) throws Exception {
        new SettingsPreparation(fixture.layout)
                .replacement(
                        fixture.source,
                        destination,
                        fixture.host.identity(),
                        "test-volume",
                        "b".repeat(40),
                        fixture.host.account().gid());
    }

    private void refused(String name) {
        assertEquals(
                "DEV_AUTH_APPLE_DIRECTORY",
                assertThrows(IllegalArgumentException.class, this::preflight).getMessage());
        assertEquals(
                "DEV_AUTH_APPLE_DIRECTORY",
                assertThrows(IllegalArgumentException.class, () -> replace(base.resolve(name)))
                        .getMessage());
    }
}
