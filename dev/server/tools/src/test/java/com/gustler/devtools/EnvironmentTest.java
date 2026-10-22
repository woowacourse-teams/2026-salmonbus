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

    private Map<String, String> parsedApi() {
        return Environment.parse(
                Environment.text(settings.get("api")),
                Environment.KEYS.get("api"),
                Environment.OPTIONAL_KEYS.get("api"));
    }

    @Test
    void acceptsExistingApiSettingsWithoutLoginKeys() {
        assertEquals(settings.get("api"), parsedApi());
        assertDoesNotThrow(() -> Environment.validate(settings));
    }

    @Test
    void acceptsPartialLoginValuesWhileLoginIsOff() {
        settings.get("api").put("AUTH_ENABLED", "false");
        settings.get("api").put("AUTH_TOKEN_SECRET", "short");
        settings.get("api").put("AUTH_PUBLIC_BASE_URL", "https://salmonbus.com");
        settings.get("api").put("AUTH_APPLE_ENABLED", "yes");
        settings.get("api").put("AUTH_APPLE_PRIVATE_KEY_PATH", "/home/user/key.p8");
        assertEquals(settings.get("api"), parsedApi());
        assertDoesNotThrow(() -> Environment.validate(settings));
    }

    @Test
    void acceptsCompleteLoginSettings() {
        settings.get("api").putAll(Fixtures.LOGIN);
        assertEquals(settings.get("api"), parsedApi());
        assertDoesNotThrow(() -> Environment.validate(settings));
        settings.get("api").put("AUTH_KAKAO_ENABLED", "false");
        settings.get("api").remove("AUTH_APPLE_ENABLED");
        settings.get("api").put("AUTH_APPLE_PRIVATE_KEY_PATH", "");
        assertDoesNotThrow(() -> Environment.validate(settings));
    }

    @Test
    void rejectsLoginKeysWithoutBooleanSwitch() {
        settings.get("api").put("AUTH_TOKEN_SECRET", "1".repeat(64));
        rejected("DEV_AUTH_ENABLED");
        for (var value : java.util.List.of("", "TRUE", "1")) {
            settings.get("api").put("AUTH_ENABLED", value);
            rejected("DEV_AUTH_ENABLED");
        }
    }

    @Test
    void rejectsMalformedAndSharedTokenSecrets() {
        settings.get("api").putAll(Fixtures.LOGIN);
        for (var key : java.util.List.of("AUTH_TOKEN_SECRET", "AUTH_REFRESH_TOKEN_SECRET")) {
            var previous = settings.get("api").get(key);
            for (var value :
                    java.util.List.of("", "1".repeat(63), "A".repeat(64), "g".repeat(64))) {
                settings.get("api").put(key, value);
                rejected("DEV_AUTH_TOKEN_SECRET");
            }
            settings.get("api").remove(key);
            rejected("DEV_AUTH_TOKEN_SECRET");
            settings.get("api").put(key, previous);
        }
        settings.get("api").put("AUTH_REFRESH_TOKEN_SECRET", "1".repeat(64));
        rejected("DEV_AUTH_TOKEN_SECRET");
    }

    @Test
    void rejectsProductionAndNonOriginLoginAddresses() {
        settings.get("api").putAll(Fixtures.LOGIN);
        for (var value :
                java.util.List.of(
                        "https://salmonbus.com",
                        "https://www.salmonbus.com",
                        "http://dev.example.test",
                        "https://dev.example.test/",
                        "https://dev.example.test?next=1",
                        "https://dev.example.test:8443",
                        "dev.example.test")) {
            settings.get("api").put("AUTH_PUBLIC_BASE_URL", value);
            rejected("DEV_AUTH_PUBLIC_BASE_URL");
        }
        settings.get("api").remove("AUTH_PUBLIC_BASE_URL");
        rejected("DEV_AUTH_PUBLIC_BASE_URL");
    }

    @Test
    void rejectsIncompleteEnabledProviders() {
        settings.get("api").putAll(Fixtures.LOGIN);
        for (var key :
                java.util.List.of(
                        "AUTH_KAKAO_CLIENT_ID",
                        "AUTH_KAKAO_CLIENT_SECRET",
                        "AUTH_KAKAO_ADMIN_KEY")) {
            var previous = settings.get("api").put(key, "");
            rejected("DEV_AUTH_KAKAO");
            settings.get("api").remove(key);
            rejected("DEV_AUTH_KAKAO");
            settings.get("api").put(key, previous);
        }
        settings.get("api").put("AUTH_KAKAO_ENABLED", "yes");
        rejected("DEV_AUTH_KAKAO");
        settings.get("api").put("AUTH_KAKAO_ENABLED", "true");
        for (var key :
                java.util.List.of(
                        "AUTH_APPLE_CLIENT_ID",
                        "AUTH_APPLE_TEAM_ID",
                        "AUTH_APPLE_KEY_ID",
                        "AUTH_APPLE_PRIVATE_KEY_PATH")) {
            var previous = settings.get("api").put(key, "");
            rejected("DEV_AUTH_APPLE");
            settings.get("api").put(key, previous);
        }
        settings.get("api").put("AUTH_APPLE_ENABLED", "on");
        rejected("DEV_AUTH_APPLE");
    }

    @Test
    void rejectsAppleKeysOutsideTheDevAppleDirectory() {
        settings.get("api").putAll(Fixtures.LOGIN);
        for (var path :
                java.util.List.of(
                        "/etc/salmonbus/apple/AuthKey_KEY0000000.p8",
                        "/etc/salmonbus-dev/AuthKey_KEY0000000.p8",
                        "/etc/salmonbus-dev/apple/AuthKey_KEY0000000.pem",
                        "/etc/salmonbus-dev/apple/../api.env.p8",
                        "/etc/salmonbus-dev/apple/keys/AuthKey_KEY0000000.p8",
                        "/etc/salmonbus-dev/apple/.p8",
                        "/home/salmonbus/AuthKey_KEY0000000.p8",
                        "etc/salmonbus-dev/apple/AuthKey_KEY0000000.p8")) {
            settings.get("api").put("AUTH_APPLE_PRIVATE_KEY_PATH", path);
            rejected("DEV_AUTH_APPLE");
        }
    }

    @Test
    void rejectsUnknownAndRepeatedLoginKeys() {
        for (var line :
                java.util.List.of(
                        "AUTH_UNKNOWN=value\n",
                        "AUTH_APPLE_PRIVATE_KEY=value\n",
                        "AUTH_ENABLED=false\nAUTH_ENABLED=true\n")) {
            assertEquals(
                    "ENV_KEYS",
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () ->
                                            Environment.parse(
                                                    Environment.text(settings.get("api")) + line,
                                                    Environment.KEYS.get("api"),
                                                    Environment.OPTIONAL_KEYS.get("api")))
                            .getMessage());
        }
        assertEquals(
                "ENV_KEYS",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        Environment.parse(
                                                Environment.text(settings.get("worker"))
                                                        + "AUTH_ENABLED=false\n",
                                                Environment.KEYS.get("worker"),
                                                Environment.OPTIONAL_KEYS.getOrDefault(
                                                        "worker", Set.of())))
                        .getMessage());
        var withoutRequired = new HashMap<>(settings.get("api"));
        withoutRequired.remove("CHAT_ENABLED");
        withoutRequired.put("AUTH_ENABLED", "false");
        assertEquals(
                "ENV_KEYS",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        Environment.parse(
                                                Environment.text(withoutRequired),
                                                Environment.KEYS.get("api"),
                                                Environment.OPTIONAL_KEYS.get("api")))
                        .getMessage());
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
    void acceptsLoginSettingsOnlyWithTheValuesFromApiEnv() {
        var expected = Environment.combined(settings.get("api"), Fixtures.LOGIN);
        Environment.effective(expected, Environment.combined(expected, Map.of("PATH", "/usr/bin")));
        assertEquals(
                "DEV_EFFECTIVE_ENV",
                assertThrows(
                                IllegalArgumentException.class,
                                () ->
                                        Environment.effective(
                                                expected,
                                                Environment.combined(
                                                        expected, Map.of("AUTH_ENABLED", "false"))))
                        .getMessage());
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
                        Map.of("AUTH_ENABLED", "true"),
                        Map.of("COMPOSE_PROJECT_NAME", "other"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Environment.effective(expected, Environment.combined(expected, extra)));
        }
    }
}
