package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("linux-root")
class ChatRetirementServerTest {
    @TempDir Path base;

    @Test
    void migratesPrivateSettingsAndRestoresThemWithoutChangingDatabaseFiles() throws Exception {
        var fixture = new Fixtures(base);
        fixture.settings();
        var config = fixture.layout.config();
        var api = new TreeMap<>(Environment.load(config).get("api"));
        var database = Map.copyOf(api);
        var password = "b".repeat(48);
        api.put("CHAT_ENABLED", "true");
        api.put("CHAT_ALLOWED_ORIGINS", Fixtures.ORIGIN);
        api.put("CHAT_MONGODB_URI", "mongodb://salmonbus_dev_chat:" + password
                + "@127.0.0.1:27017/salmonbus_chat_dev?authSource=salmonbus_chat_dev");
        FileOps.atomicFile(config.resolve("api.env"), Environment.text(api).getBytes(), 0600);
        FileOps.writeNew(config.resolve("mongodb.env"), Environment.text(Map.of(
                "MONGO_INITDB_ROOT_USERNAME", "salmonbus_dev_admin",
                "MONGO_INITDB_ROOT_PASSWORD", "c".repeat(48), "MONGO_CHAT_PASSWORD", password)).getBytes(), 0600);
        var sentinel = Files.createDirectories(fixture.layout.data().resolve("mongodb")).resolve("keep-data");
        Files.writeString(sentinel, "existing messages");
        var originalApi = FileOps.sha(config.resolve("api.env"));
        var preparation = new SettingsPreparation(fixture.layout);
        var destination = base.resolve("new-settings");
        preparation.replacement(fixture.source, destination, fixture.host.identity(), "test-volume",
                "b".repeat(40), fixture.host.account().gid());
        assertEquals(database, Environment.load(destination).get("api"));
        assertFalse(Files.exists(destination.resolve("mongodb.env")));
        assertFalse(Files.exists(destination.resolve("mongodb.yml")));
        assertEquals(originalApi, FileOps.sha(destination.resolve("retired-chat/api.env")));
        assertEquals(0600, FileOps.attribute(destination.resolve("retired-chat/mongodb.env"), "mode") & 0777);
        FileOps.moveNew(config, base.resolve("original-settings"));
        FileOps.moveNew(destination, config);
        Files.writeString(fixture.source.resolveSibling("config/mongodb.yml"), "storage:\n  dbPath: /data/db\n");
        var restored = base.resolve("restored-settings");
        preparation.replacement(fixture.source, restored, fixture.host.identity(), "test-volume",
                Fixtures.COMMIT, fixture.host.account().gid());
        assertEquals(originalApi, FileOps.sha(restored.resolve("api.env")));
        assertEquals(api, Environment.load(restored).get("api"));
        assertEquals("existing messages", Files.readString(sentinel));
    }
}
