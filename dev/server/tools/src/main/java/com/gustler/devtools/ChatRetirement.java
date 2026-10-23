package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** SAL-256: exact infrastructure-only transition; retained private settings support rollback. */
final class ChatRetirement {
    static final Set<String> INFRA_FILES = Set.of(
            "infra/compose.yaml", "config/mongodb.yml", "infra/init-chat.js");
    static final String OLD_COMPOSE = "d7611d246c718a706d1e32ba6ef6261906a41e15b843fa3dd10445df88aa9718";
    static final String NEW_COMPOSE = "4813322215845fe67fd526243d61cefc3da02695dc0aa1c854596234e3a67fa2";
    static final String OLD_CONFIG = "f793c67807d089c757ee1b2a37671995d81db2137c8e5db4afe9414fe247d5e8";
    static final String OLD_INIT = "beef8bd0a0a44543ee54f74d2139f1c7db9ee1efa4adb80f9be45418b0332f84";
    static final Set<String> LEGACY_API_KEYS = Set.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "CHAT_ENABLED", "CHAT_MONGODB_URI", "CHAT_ALLOWED_ORIGINS");
    static final Set<String> LEGACY_MONGO_KEYS = Set.of(
            "MONGO_INITDB_ROOT_USERNAME", "MONGO_INITDB_ROOT_PASSWORD", "MONGO_CHAT_PASSWORD");

    private ChatRetirement() {}

    static boolean permits(RevisionStore.Revision previous, RevisionStore.Revision next) {
        return forward(previous.files(), next.files()) || forward(next.files(), previous.files());
    }

    private static boolean forward(Map<String, String> from, Map<String, String> to) {
        return OLD_COMPOSE.equals(from.get("infra/compose.yaml"))
                && OLD_CONFIG.equals(from.get("config/mongodb.yml"))
                && OLD_INIT.equals(from.get("infra/init-chat.js"))
                && NEW_COMPOSE.equals(to.get("infra/compose.yaml"))
                && !to.containsKey("config/mongodb.yml") && !to.containsKey("infra/init-chat.js")
                && java.util.Objects.equals(from.get("tools/data-contract.sha256"),
                        to.get("tools/data-contract.sha256"));
    }

    static Map<String, String> databaseOnly(Map<String, String> api) {
        var result = new TreeMap<String, String>();
        for (var key : Environment.KEYS.get("api")) result.put(key, api.get(key));
        return result;
    }

    static Path saved(Path directory, String name) {
        var current = directory.resolve(name);
        return FileOps.exists(current) ? current : directory.resolve("retired-chat/" + name);
    }

    static Map<String, String> api(Path directory) throws Exception {
        var text = FileOps.privateText(directory.resolve("api.env"));
        if (!text.lines().anyMatch(line -> line.startsWith("CHAT_"))) {
            return Environment.parse(text, Environment.KEYS.get("api"));
        }
        var api = Environment.parse(text, LEGACY_API_KEYS);
        var mongo = Environment.parse(FileOps.privateText(saved(directory, "mongodb.env")), LEGACY_MONGO_KEYS);
        Checks.require("true".equals(api.get("CHAT_ENABLED"))
                        && "salmonbus_dev_admin".equals(mongo.get("MONGO_INITDB_ROOT_USERNAME"))
                        && mongo.get("MONGO_CHAT_PASSWORD").matches("[0-9a-f]{48}")
                        && mongo.get("MONGO_INITDB_ROOT_PASSWORD").matches("[0-9a-f]{48}")
                        && !mongo.get("MONGO_CHAT_PASSWORD").equals(mongo.get("MONGO_INITDB_ROOT_PASSWORD"))
                        && ("mongodb://salmonbus_dev_chat:" + mongo.get("MONGO_CHAT_PASSWORD")
                            + "@127.0.0.1:27017/salmonbus_chat_dev?authSource=salmonbus_chat_dev")
                            .equals(api.get("CHAT_MONGODB_URI")), "DEV_RETIRED_CHAT_SETTINGS");
        Environment.origin(api.get("CHAT_ALLOWED_ORIGINS"));
        return api;
    }

    static void retain(Path directory, Path destination, SettingsPreparation.Writer writer) throws Exception {
        if (!Files.exists(saved(directory, "mongodb.env"))) return;
        var archive = destination.resolve("retired-chat");
        Files.createDirectory(archive);
        FileOps.mode(archive, 0700);
        for (var name : Set.of("api.env", "mongodb.env")) {
            var original = directory.resolve("retired-chat/" + name);
            if (!FileOps.exists(original)) original = directory.resolve(name);
            writer.write(archive.resolve(name), FileOps.privateText(original).getBytes(), 0600);
        }
    }
}
