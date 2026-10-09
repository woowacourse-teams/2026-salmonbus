package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

final class SettingsPreparation {
    interface Writer {
        void write(Path path, byte[] content, int mode) throws Exception;
    }

    private final Layout layout;
    private final Writer writer;

    SettingsPreparation(Layout layout) {
        this(layout, FileOps::writeNew);
    }

    SettingsPreparation(Layout layout, Writer writer) {
        this.layout = layout;
        this.writer = writer;
    }

    static Map<String, Map<String, String>> create(String origin, String revision) {
        Environment.origin(origin);
        Checks.require(revision.matches("[0-9a-f]{40}"), "DEV_REVISION");
        var password = password();
        var admin = password();
        var chat = password();
        var common =
                Map.of(
                        "DB_URL",
                        Environment.DATABASE,
                        "DB_USERNAME",
                        "salmonbus_dev",
                        "DB_PASSWORD",
                        password);
        var values =
                Map.of(
                        "api",
                                Environment.combined(
                                        common,
                                        Map.of(
                                                "CHAT_ENABLED",
                                                "true",
                                                "CHAT_ALLOWED_ORIGINS",
                                                origin,
                                                "CHAT_MONGODB_URI",
                                                "mongodb://salmonbus_dev_chat:"
                                                        + chat
                                                        + "@127.0.0.1:27017/salmonbus_chat_dev?authSource=salmonbus_chat_dev")),
                        "worker",
                                Environment.combined(
                                        common,
                                        Map.of(
                                                "GBIS_SERVICE_KEY",
                                                Environment.KEY,
                                                "GBIS_SERVICE_KEY_B",
                                                "",
                                                "GBIS_SERVICE_KEY_C",
                                                "",
                                                "GBIS_SERVICE_KEY_D",
                                                "")),
                        "postgres",
                                Map.of(
                                        "POSTGRES_DB",
                                        "salmonbus_dev",
                                        "POSTGRES_USER",
                                        "salmonbus_dev",
                                        "POSTGRES_PASSWORD",
                                        password),
                        "mongodb",
                                Map.of(
                                        "MONGO_INITDB_ROOT_USERNAME",
                                        "salmonbus_dev_admin",
                                        "MONGO_INITDB_ROOT_PASSWORD",
                                        admin,
                                        "MONGO_CHAT_PASSWORD",
                                        chat),
                        "infra", Map.of("DEV_REVISION", revision));
        Environment.validate(values);
        return values;
    }

    private static String password() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    void replacement(
            Path source,
            Path destination,
            JsonNode identity,
            String uuid,
            String revision,
            final int group)
            throws Exception {
        var settings = Environment.load(layout.config());
        Checks.require(!FileOps.exists(destination), "DESTINATION_EXISTS");
        Files.createDirectory(
                destination,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        for (var name : Environment.KEYS.keySet()) {
            byte[] content =
                    name.equals("infra")
                            ? Environment.text(Map.of("DEV_REVISION", revision)).getBytes()
                            : FileOps.privateText(layout.config().resolve(name + ".env"))
                                    .getBytes();
            writer.write(destination.resolve(name + ".env"), content, 0600);
        }
        var hashes = new TreeMap<String, String>();
        for (var name : java.util.List.of("api.yml", "worker.yml", "mongodb.yml")) {
            var file =
                    name.equals("mongodb.yml")
                            ? source.resolve("../config/" + name).normalize()
                            : source.resolve(name);
            byte[] content = Files.readAllBytes(file);
            writer.write(destination.resolve(name), content, 0644);
            hashes.put(name, FileOps.sha(content));
        }
        writer.write(
                destination.resolve("environment.json"),
                Json.bytes(
                        Map.of(
                                "environment",
                                "dev",
                                "instanceId",
                                identity.path("instanceId").asText(),
                                "accountId",
                                identity.path("accountId").asText(),
                                "dataVolumeUuid",
                                uuid,
                                "configSha256",
                                hashes)),
                0600);
        var updated = Environment.load(destination);
        for (var name : java.util.List.of("api", "worker", "postgres", "mongodb")) {
            Checks.require(updated.get(name).equals(settings.get(name)), "DEV_SECRETS_CHANGED");
        }
        FileOps.owner(destination, 0, group);
        FileOps.mode(destination, 0750);
        FileOps.sync(destination);
    }

    String initialize(
            Path source,
            JsonNode identity,
            String uuid,
            String origin,
            String revision,
            final int group)
            throws Exception {
        Environment.origin(origin);
        Checks.require(revision.matches("[0-9a-f]{40}"), "DEV_REVISION");
        var directory = layout.config();
        Checks.require(
                !Files.isSymbolicLink(directory) && !Files.isSymbolicLink(directory.getParent()),
                "CONFIG_DIRECTORY");
        var contents = new TreeMap<String, byte[]>();
        contents.put("api.yml", Files.readAllBytes(source.resolve("api.yml")));
        contents.put("worker.yml", Files.readAllBytes(source.resolve("worker.yml")));
        contents.put(
                "mongodb.yml",
                Files.readAllBytes(source.resolve("../config/mongodb.yml").normalize()));
        var hashes = new TreeMap<String, String>();
        for (var entry : contents.entrySet()) {
            hashes.put(entry.getKey(), FileOps.sha(entry.getValue()));
        }
        if (FileOps.exists(directory)) {
            Checks.require(
                    Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                            && FileOps.attribute(directory, "uid") == 0
                            && FileOps.attribute(directory, "gid") == group
                            && (FileOps.attribute(directory, "mode") & 0777) == 0750,
                    "CONFIG_DIRECTORY");
            var marker =
                    Json.read(
                            FileOps.privateText(directory.resolve("environment.json")).getBytes());
            Environment.identity(marker, identity);
            Checks.require(
                    marker.path("dataVolumeUuid").asText().equals(uuid)
                            && marker.path("configSha256").equals(Json.read(Json.bytes(hashes))),
                    "EXISTING_SETTINGS_DIFFER");
            for (var entry : hashes.entrySet()) {
                var file = directory.resolve(entry.getKey());
                FileOps.protectedFile(file, "CONFIG_FILE");
                Checks.require(
                        (FileOps.attribute(file, "mode") & 0777) == 0644
                                && FileOps.sha(file).equals(entry.getValue()),
                        "EXISTING_SETTINGS_DIFFER");
            }
            var settings = Environment.load(directory);
            Checks.require(
                    settings.get("api").get("CHAT_ALLOWED_ORIGINS").equals(origin)
                            && settings.get("infra").get("DEV_REVISION").equals(revision),
                    "EXISTING_SETTINGS_DIFFER");
            return "preserved";
        }
        for (var name : java.util.List.of("postgres", "mongodb")) {
            var path = layout.data().resolve(name);
            Checks.require(!Files.isSymbolicLink(path), "ORPHANED_DEV_DATA");
            if (FileOps.exists(path)) {
                try (var files = Files.list(path)) {
                    Checks.require(files.findAny().isEmpty(), "ORPHANED_DEV_DATA");
                }
            }
        }
        var stage = Files.createTempDirectory(directory.getParent(), ".salmonbus-dev-");
        try {
            for (var entry : create(origin, revision).entrySet()) {
                writer.write(
                        stage.resolve(entry.getKey() + ".env"),
                        Environment.text(entry.getValue()).getBytes(),
                        0600);
            }
            for (var entry : contents.entrySet()) {
                writer.write(stage.resolve(entry.getKey()), entry.getValue(), 0644);
            }
            writer.write(
                    stage.resolve("environment.json"),
                    Json.bytes(
                            Map.of(
                                    "environment",
                                    "dev",
                                    "instanceId",
                                    identity.path("instanceId").asText(),
                                    "accountId",
                                    identity.path("accountId").asText(),
                                    "dataVolumeUuid",
                                    uuid,
                                    "configSha256",
                                    hashes)),
                    0600);
            Environment.load(stage);
            FileOps.owner(stage, 0, group);
            FileOps.mode(stage, 0750);
            FileOps.sync(stage);
            FileOps.moveNew(stage, directory);
        } finally {
            FileOps.removeTree(stage);
        }
        return "prepared";
    }
}
