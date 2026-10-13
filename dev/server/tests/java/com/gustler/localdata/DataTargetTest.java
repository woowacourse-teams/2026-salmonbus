package com.gustler.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataTargetTest {
    private static final String[] LOCAL = {"/local/data/routes.json", ReferenceBundle.DIRECTORY};
    private static final String[] SHARED = {"/srv/salmonbus-dev/data/routes.json",
        "/srv/salmonbus-dev/models/reference-20261002", "--shared-dev"};

    private Map<String, String> environment(final boolean shared) {
        return new HashMap<>(Map.of(
            "DB_URL", shared ? "jdbc:postgresql://127.0.0.1:15432/salmonbus_dev"
                : "jdbc:postgresql://postgres:5432/salmonbus_local",
            "DB_USERNAME", shared ? "salmonbus_dev" : "salmonbus_local",
            "DB_PASSWORD", "a".repeat(48),
            "GBIS_SERVICE_KEY", shared ? "shared-dev-placeholder" : "local-only-placeholder"));
    }

    @Test
    void localPreparationAndExistingOptionsKeepTheirTarget() {
        for (String[] args : new String[][]{LOCAL,
            {LOCAL[0], LOCAL[1], "--reset-generated"}, {LOCAL[0], LOCAL[1], "--recover-collector-only"}}) {
            var target = DataTarget.resolve(args, environment(false));
            assertThat(target.shared()).isFalse();
            assertThat(target.username()).isEqualTo("salmonbus_local");
        }
    }

    @Test
    void sharedPreparationHasItsOwnFixedTarget() {
        var target = DataTarget.resolve(SHARED, environment(true));
        assertThat(target.shared()).isTrue();
        assertThat(target.username()).isEqualTo("salmonbus_dev");
    }

    @Test
    void localAndSharedInputsCannotBeMixed() {
        assertThatThrownBy(() -> DataTarget.resolve(SHARED, environment(false))).hasMessage("LOCAL_DATABASE_ONLY");
        assertThatThrownBy(() -> DataTarget.resolve(LOCAL, environment(true))).hasMessage("LOCAL_DATABASE_ONLY");
        assertThatThrownBy(() -> DataTarget.resolve(new String[]{LOCAL[0], LOCAL[1], "--shared-dev"}, environment(true)))
            .hasMessage("LOCAL_PATHS");
    }

    @Test
    void sharedModeDoesNotExposeResetOrRecovery() {
        for (String option : new String[]{"--reset-generated", "--recover-collector-only", "--force"}) {
            assertThatThrownBy(() -> DataTarget.resolve(new String[]{SHARED[0], SHARED[1], option}, environment(true)))
                .hasMessage("LOCAL_PATHS");
        }
    }

    @Test
    void externalDatabaseAndAdditionalJdbcOptionsAreRejected() {
        for (String url : new String[]{"jdbc:postgresql://production.invalid:5432/salmonbus",
            "jdbc:postgresql://localhost:15432/salmonbus_dev", "jdbc:postgresql://127.0.0.1:15432/salmonbus_dev?sslmode=disable"}) {
            var values = environment(true);
            values.put("DB_URL", url);
            assertThatThrownBy(() -> DataTarget.resolve(SHARED, values)).hasMessage("LOCAL_DATABASE_ONLY");
        }
    }

    @Test
    void externalApiKeysInAnySlotAreRejected() {
        for (String name : new String[]{"GBIS_SERVICE_KEY", "GBIS_SERVICE_KEY_B", "GBIS_SERVICE_KEY_C", "GBIS_SERVICE_KEY_D"}) {
            var values = environment(true);
            values.put(name, "external-test-key");
            assertThatThrownBy(() -> DataTarget.resolve(SHARED, values)).hasMessage("LOCAL_KEY_ONLY");
        }
    }

    @Test
    void missingCredentialsAndUnknownOptionsAreRejected() {
        var values = environment(true);
        values.remove("DB_PASSWORD");
        assertThatThrownBy(() -> DataTarget.resolve(SHARED, values)).hasMessage("LOCAL_CREDENTIALS");
        assertThatThrownBy(() -> DataTarget.resolve(new String[0], environment(true))).hasMessage("LOCAL_PATHS");
    }
}
