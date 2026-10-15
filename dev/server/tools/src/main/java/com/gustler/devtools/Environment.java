package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

final class Environment {
    static final String DATABASE = "jdbc:postgresql://127.0.0.1:15432/salmonbus_dev";
    static final String KEY = "shared-dev-placeholder";
    static final Map<String, Set<String>> KEYS =
            Map.of(
                    "api",
                            Set.of(
                                    "DB_URL",
                                    "DB_USERNAME",
                                    "DB_PASSWORD",
                                    "CHAT_ENABLED",
                                    "CHAT_MONGODB_URI",
                                    "CHAT_ALLOWED_ORIGINS"),
                    "worker",
                            Set.of(
                                    "DB_URL",
                                    "DB_USERNAME",
                                    "DB_PASSWORD",
                                    "GBIS_SERVICE_KEY",
                                    "GBIS_SERVICE_KEY_B",
                                    "GBIS_SERVICE_KEY_C",
                                    "GBIS_SERVICE_KEY_D"),
                    "postgres", Set.of("POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"),
                    "mongodb",
                            Set.of(
                                    "MONGO_INITDB_ROOT_USERNAME",
                                    "MONGO_INITDB_ROOT_PASSWORD",
                                    "MONGO_CHAT_PASSWORD"),
                    "infra", Set.of("DEV_REVISION"));
    static final Set<String> RELEASE_KEYS =
            Set.of(
                    "INFO_COMPONENT",
                    "INFO_COMMIT",
                    "INFO_SOURCEDIGEST",
                    "INFO_ENVIRONMENT",
                    "MANAGEMENT_INFO_ENV_ENABLED");

    private Environment() {}

    static Map<String, String> parse(String text, Set<String> keys) {
        var values = new TreeMap<String, String>();
        for (var line : text.split("\n", -1)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int separator = line.indexOf('=');
            Checks.require(separator > 0, "ENV_KEYS");
            var key = line.substring(0, separator);
            var value = line.substring(separator + 1);
            Checks.require(keys.contains(key) && !values.containsKey(key), "ENV_KEYS");
            Checks.require(
                    value.chars()
                            .noneMatch(
                                    c ->
                                            Character.isWhitespace(c)
                                                    || "\u0000\\\"'".indexOf(c) >= 0),
                    "ENV_VALUE");
            values.put(key, value);
        }
        Checks.require(values.keySet().equals(keys), "ENV_KEYS");
        return values;
    }

    static String text(Map<String, String> values) {
        var output = new StringBuilder();
        new TreeMap<>(values)
                .forEach((key, value) -> output.append(key).append('=').append(value).append('\n'));
        return output.toString();
    }

    static void origin(String value) {
        var uri = URI.create(value);
        var host = uri.getHost();
        Checks.require(
                "https".equals(uri.getScheme())
                        && host != null
                        && host.matches("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}")
                        && !Set.of("salmonbus.com", "www.salmonbus.com").contains(host)
                        && uri.getRawUserInfo() == null
                        && uri.getRawQuery() == null
                        && uri.getRawFragment() == null
                        && uri.getRawPath().isEmpty()
                        && uri.getPort() == -1,
                "DEV_FRONTEND_ORIGIN");
    }

    static Map<String, Map<String, String>> load(Path directory) throws Exception {
        var values = new TreeMap<String, Map<String, String>>();
        for (var entry : KEYS.entrySet()) {
            values.put(
                    entry.getKey(),
                    parse(
                            FileOps.privateText(directory.resolve(entry.getKey() + ".env")),
                            entry.getValue()));
        }
        validate(values);
        return values;
    }

    static void validate(Map<String, Map<String, String>> settings) {
        var postgres = settings.get("postgres");
        var api = settings.get("api");
        var worker = settings.get("worker");
        var mongo = settings.get("mongodb");
        Checks.require(
                "salmonbus_dev".equals(postgres.get("POSTGRES_DB"))
                        && "salmonbus_dev".equals(postgres.get("POSTGRES_USER")),
                "DEV_DATABASE");
        var password = postgres.get("POSTGRES_PASSWORD");
        Checks.require(password != null && password.matches("[0-9a-f]{48}"), "DEV_PASSWORD");
        for (var app : java.util.List.of(api, worker)) {
            Checks.require(
                    DATABASE.equals(app.get("DB_URL"))
                            && "salmonbus_dev".equals(app.get("DB_USERNAME"))
                            && password.equals(app.get("DB_PASSWORD")),
                    "DEV_DATABASE");
        }
        Checks.require(
                KEY.equals(worker.get("GBIS_SERVICE_KEY"))
                        && java.util.List.of(
                                        "GBIS_SERVICE_KEY_B",
                                        "GBIS_SERVICE_KEY_C",
                                        "GBIS_SERVICE_KEY_D")
                                .stream()
                                .allMatch(key -> "".equals(worker.get(key))),
                "DEV_GBIS_KEY");
        Checks.require("true".equals(api.get("CHAT_ENABLED")), "DEV_CHAT");
        Checks.require(
                "salmonbus_dev_admin".equals(mongo.get("MONGO_INITDB_ROOT_USERNAME")),
                "DEV_MONGO_USER");
        Checks.require(
                java.util.List.of("MONGO_INITDB_ROOT_PASSWORD", "MONGO_CHAT_PASSWORD").stream()
                                .allMatch(
                                        key ->
                                                mongo.get(key) != null
                                                        && mongo.get(key).matches("[0-9a-f]{48}"))
                        && !mongo.get("MONGO_CHAT_PASSWORD")
                                .equals(mongo.get("MONGO_INITDB_ROOT_PASSWORD")),
                "DEV_MONGO_PASSWORD");
        Checks.require(
                ("mongodb://salmonbus_dev_chat:"
                                + mongo.get("MONGO_CHAT_PASSWORD")
                                + "@127.0.0.1:27017/salmonbus_chat_dev?authSource=salmonbus_chat_dev")
                        .equals(api.get("CHAT_MONGODB_URI")),
                "DEV_MONGO_URI");
        origin(api.get("CHAT_ALLOWED_ORIGINS"));
        Checks.require(
                settings.get("infra").get("DEV_REVISION").matches("[0-9a-f]{40}"), "DEV_REVISION");
    }

    static void identity(JsonNode marker, JsonNode identity) {
        Checks.require(
                marker.path("environment").asText().equals("dev")
                        && !marker.path("instanceId").asText().isEmpty()
                        && !marker.path("accountId").asText().isEmpty()
                        && marker.path("instanceId").equals(identity.path("instanceId"))
                        && marker.path("accountId").equals(identity.path("accountId")),
                "DEV_INSTANCE");
    }

    static void effective(Map<String, String> expected, Map<String, String> actual) {
        Checks.require(actual.entrySet().containsAll(expected.entrySet()), "DEV_EFFECTIVE_ENV");
        var prefixes =
                java.util.List.of(
                        "SPRING_",
                        "GBIS_",
                        "DB_",
                        "CHAT_",
                        "MODEL_",
                        "COLLECTION_",
                        "FORECAST_",
                        "JAVA_",
                        "JDK_",
                        "_JAVA_",
                        "SERVER_",
                        "MANAGEMENT_",
                        "COMPOSE_",
                        "DOCKER_",
                        "LD_");
        Checks.require(
                actual.keySet().stream()
                        .noneMatch(
                                key ->
                                        !expected.containsKey(key)
                                                && prefixes.stream().anyMatch(key::startsWith)),
                "DEV_EFFECTIVE_ENV");
    }

    static Map<String, String> combined(Map<String, String> first, Map<String, String> second) {
        var result = new HashMap<>(first);
        result.putAll(second);
        return result;
    }
}
