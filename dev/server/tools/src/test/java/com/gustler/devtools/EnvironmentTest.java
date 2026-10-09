package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

class EnvironmentTest {
    private Map<String, Map<String, String>> settings;

    @BeforeEach
    void prepare() {
        settings = new HashMap<>();
        SettingsPreparation.create("https://dev.example.test", "a".repeat(40))
                .forEach((name, values) -> settings.put(name, new HashMap<>(values)));
    }

    private void rejected(String code) {
        assertEquals(
                code,
                assertThrows(IllegalArgumentException.class, () -> Environment.validate(settings))
                        .getMessage());
    }

    @Test
    void acceptsIsolatedDevelopmentSettings() {
        assertDoesNotThrow(() -> Environment.validate(settings));
    }

    @Test
    void rejectsOtherDatabaseHostsPortsNamesAndOptions() {
        for (var component : Set.of("api", "worker")) {
            for (var url :
                    java.util.List.of(
                            "jdbc:postgresql://production.invalid:5432/salmonbus",
                            "jdbc:postgresql://127.0.0.1:5432/salmonbus_dev",
                            Environment.DATABASE + "?options=-csearch_path=public")) {
                settings.get(component).put("DB_URL", url);
                rejected("DEV_DATABASE");
            }
            settings.get(component).put("DB_URL", Environment.DATABASE);
        }
    }

    @Test
    void rejectsMismatchedPasswords() {
        settings.get("worker").put("DB_PASSWORD", "e".repeat(48));
        rejected("DEV_DATABASE");
    }

    @Test
    void rejectsRealGbisKeysInEverySlot() {
        for (var slot :
                Set.of(
                        "GBIS_SERVICE_KEY",
                        "GBIS_SERVICE_KEY_B",
                        "GBIS_SERVICE_KEY_C",
                        "GBIS_SERVICE_KEY_D")) {
            var previous = settings.get("worker").put(slot, "external-test-key");
            rejected("DEV_GBIS_KEY");
            settings.get("worker").put(slot, previous);
        }
    }

    @Test
    void rejectsMongoAdminAndRemoteConnections() {
        var original = settings.get("api").get("CHAT_MONGODB_URI");
        for (var uri :
                java.util.List.of(
                        original.replace("salmonbus_dev_chat:", "salmonbus_dev_admin:"),
                        original.replace("127.0.0.1", "production.invalid"))) {
            settings.get("api").put("CHAT_MONGODB_URI", uri);
            rejected("DEV_MONGO_URI");
        }
    }

    @Test
    void rejectsReusingAdminPasswordForChat() {
        settings.get("mongodb")
                .put(
                        "MONGO_CHAT_PASSWORD",
                        settings.get("mongodb").get("MONGO_INITDB_ROOT_PASSWORD"));
        rejected("DEV_MONGO_PASSWORD");
    }

    @Test
    void rejectsProductionWildcardAndNonOriginFrontendValues() {
        for (var value :
                java.util.List.of(
                        "https://salmonbus.com",
                        "https://www.salmonbus.com",
                        "http://dev.example.test",
                        "https://dev.example.test/path",
                        "https://user@dev.example.test",
                        "*")) {
            assertThrows(IllegalArgumentException.class, () -> Environment.origin(value));
        }
    }

    @Test
    void rejectsExplicitDefaultHttpsPort() {
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> Environment.origin("https://dev.example.test:443"));
        assertEquals("DEV_FRONTEND_ORIGIN", error.getMessage());
        assertDoesNotThrow(() -> Environment.origin("https://dev.example.test"));
    }

    @Test
    void rejectsMutableImageTagsAndShellExpressions() {
        for (var value : java.util.List.of("latest", "$(command)", "a".repeat(7))) {
            settings.get("infra").put("DEV_REVISION", value);
            rejected("DEV_REVISION");
        }
    }

    @Test
    void rejectsDuplicateAndUnknownEnvironmentKeys() {
        for (var text :
                java.util.List.of(
                        "DB_URL=one\nDB_URL=two\n", "DB_URL=one\nSPRING_APPLICATION_JSON={}\n")) {
            assertEquals(
                    "ENV_KEYS",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> Environment.parse(text, Set.of("DB_URL")))
                            .getMessage());
        }
    }

    @Test
    void rejectsQuotedWhitespaceNullAndEscapedValues() {
        for (var value :
                java.util.List.of("one two", "\"one\"", "one\\ntwo", "one\u0000two", "one\rtwo")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Environment.parse("DB_URL=" + value, Set.of("DB_URL")));
        }
    }

    @Test
    void rejectsWrongInstanceAccountAndEnvironment() {
        var identity =
                Json.read(
                        Json.bytes(Map.of("instanceId", "test-dev", "accountId", "test-account")));
        Environment.identity(
                Json.read(
                        Json.bytes(
                                Map.of(
                                        "environment",
                                        "dev",
                                        "instanceId",
                                        "test-dev",
                                        "accountId",
                                        "test-account"))),
                identity);
        for (var marker :
                java.util.List.of(
                        Map.of(
                                "environment",
                                "prod",
                                "instanceId",
                                "test-dev",
                                "accountId",
                                "test-account"),
                        Map.of(
                                "environment",
                                "dev",
                                "instanceId",
                                "test-prod",
                                "accountId",
                                "test-account"),
                        Map.of(
                                "environment",
                                "dev",
                                "instanceId",
                                "test-dev",
                                "accountId",
                                "other-account"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Environment.identity(Json.read(Json.bytes(marker)), identity));
        }
    }

    @Test
    void rejectsInheritedConnectionAndJvmOverrides() {
        var expected = settings.get("api");
        Environment.effective(expected, Environment.combined(expected, Map.of("PATH", "/usr/bin")));
        for (var extra :
                java.util.List.of(
                        Map.of("DB_URL", "other"),
                        Map.of("SPRING_DATASOURCE_URL", "other"),
                        Map.of("SPRING_APPLICATION_JSON", "{}"),
                        Map.of("JAVA_TOOL_OPTIONS", "-Dgbis.base-url=https://external.invalid"),
                        Map.of("DOCKER_HOST", "tcp://external.invalid:2375"),
                        Map.of("COMPOSE_PROJECT_NAME", "other"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Environment.effective(expected, Environment.combined(expected, extra)));
        }
    }
}
