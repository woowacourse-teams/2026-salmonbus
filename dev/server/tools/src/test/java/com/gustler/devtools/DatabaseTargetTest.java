package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;

class DatabaseTargetTest {
    private static final String DIGEST = "a".repeat(64);
    private static final String ENDPOINT =
            "salmonbus-dev-db.example.ap-northeast-2.rds.amazonaws.com";

    private Map<String, Object> target() {
        return new HashMap<>(Map.of(
                "environment", "dev",
                "dbInstanceIdentifier", "salmonbus-dev-db",
                "dbInstanceArn", "arn:aws:rds:ap-northeast-2:000000000000:db:salmonbus-dev-db",
                "endpoint", ENDPOINT,
                "port", 5432,
                "certificateSha256", DIGEST));
    }

    private JsonNode marker(String environment, String account) {
        return Json.read(Json.bytes(Map.of("environment", environment, "accountId", account)));
    }

    private String url(Map<String, Object> values) {
        return DatabaseTarget.validatedUrl(
                Json.read(Json.bytes(values)), marker("dev", "000000000000"), DIGEST);
    }

    @Test
    void pinnedDevTargetRequiresFullCertificateAndHostnameVerification() {
        assertEquals(
                "jdbc:postgresql://" + ENDPOINT
                        + ":5432/salmonbus_dev?sslmode=verify-full&sslrootcert="
                        + DatabaseTarget.CERTIFICATE_PATH,
                url(target()));
    }

    @Test
    void rejectsProductionTargetOtherAccountsRegionsAndInjectedEndpoints() {
        var invalid = Map.of(
                "environment", "prod",
                "dbInstanceIdentifier", "salmonbus-db",
                "dbInstanceArn", "arn:aws:rds:ap-northeast-2:111111111111:db:salmonbus-dev-db",
                "port", 15432);
        for (var entry : invalid.entrySet()) {
            var values = target();
            values.put(entry.getKey(), entry.getValue());
            assertThrows(IllegalArgumentException.class, () -> url(values));
        }
        for (var endpoint : java.util.List.of(
                ENDPOINT.replace("salmonbus-dev-db.", "salmonbus-db."),
                ENDPOINT.replace("ap-northeast-2", "ap-northeast-1"),
                ENDPOINT + ".invalid",
                ENDPOINT + "/other?sslmode=disable",
                "user@" + ENDPOINT,
                "127.0.0.1")) {
            var values = target();
            values.put("endpoint", endpoint);
            assertThrows(IllegalArgumentException.class, () -> url(values));
        }
    }

    @Test
    void rejectsMismatchedAccountAndProductionMarkers() {
        for (var value : java.util.List.of(
                marker("prod", "000000000000"), marker("dev", "111111111111"))) {
            assertThrows(IllegalArgumentException.class, () ->
                    DatabaseTarget.validatedUrl(Json.read(Json.bytes(target())), value, DIGEST));
        }
    }

    @Test
    void rejectsMissingUnknownAndWronglyTypedFields() {
        for (var key : target().keySet()) {
            var values = target();
            values.remove(key);
            assertThrows(IllegalArgumentException.class, () -> url(values));
        }
        var values = target();
        values.put("sslmode", "disable");
        assertThrows(IllegalArgumentException.class, () -> url(values));
        values.remove("sslmode");
        values.put("port", "5432");
        assertThrows(IllegalArgumentException.class, () -> url(values));
    }

    @Test
    void rejectsChangedCertificate() {
        var values = target();
        values.put("certificateSha256", "b".repeat(64));
        assertEquals("DEV_RDS_CERTIFICATE",
                assertThrows(IllegalArgumentException.class, () -> url(values)).getMessage());
    }

    @Test
    void apiAndWorkerMustBothUseTheValidatedTargetAndExistingDevCredentials() {
        var settings = new HashMap<String, Map<String, String>>();
        SettingsPreparation.create("https://dev.example.test", "a".repeat(40))
                .forEach((name, values) -> settings.put(name, new HashMap<>(values)));
        var database = url(target());
        settings.get("api").put("DB_URL", database);
        assertThrows(IllegalArgumentException.class, () -> Environment.validate(settings, database));
        settings.get("worker").put("DB_URL", database);
        assertDoesNotThrow(() -> Environment.validate(settings, database));
        settings.get("worker").put("DB_USERNAME", "postgres");
        assertThrows(IllegalArgumentException.class, () -> Environment.validate(settings, database));
        // An RDS URL alone never opts a normal local configuration into remote access.
        assertThrows(IllegalArgumentException.class, () -> Environment.validate(settings));
    }
}
