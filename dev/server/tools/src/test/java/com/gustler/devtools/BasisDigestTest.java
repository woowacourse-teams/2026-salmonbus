package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BasisDigestTest {
    @TempDir Path repo;
    private Packager packager;

    @BeforeEach
    void prepare() throws Exception {
        for (var name :
                List.of(
                        "dev/server/tools/src/main/Tool.java",
                        "dev/server/tools/build.gradle",
                        "dev/server/tools/settings.gradle",
                        "dev/server/api.yml",
                        "dev/server/worker.yml",
                        "dev/server/compose.yaml",
                        "dev/server/init-chat.js",
                        "dev/server/wiremock.Dockerfile",
                        "dev/server/systemd/salmonbus-dev-infra.service",
                        "dev/config/mongodb.yml",
                        "dev/data/java/Data.java",
                        "dev/data/routes.json",
                        "dev/data/catalog-routes.json",
                        "dev/data/model-reference/manifest.json",
                        "dev/wiremock/mappings/location.json",
                        "dev/wiremock-extension/src/main/Replay.java",
                        "dev/scenarios/replay.json",
                        "dev/server/data.init",
                        "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/ForecastFeatureContract.java",
                        "backend/common/src/main/resources/db/migration/V1__initial.sql")) {
            var file = repo.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "initial input\n");
        }
        var script = repo.resolve("backend/deploy/runtime-source-inputs.sh");
        Files.createDirectories(script.getParent());
        try (var input = getClass().getResourceAsStream("/runtime-source-inputs.sh")) {
            assertNotNull(input);
            Files.copy(input, script);
        }
        packager = new Packager(repo, new Host());
    }

    @Test
    void helperBuildRuntimeAndMigrationInputsChangeTheContentFingerprint() throws Exception {
        var before = packager.basisDigest();
        for (var name :
                List.of(
                        "dev/server/data.init",
                        "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/ForecastFeatureContract.java",
                        "backend/common/src/main/resources/db/migration/V2__added.sql")) {
            Files.writeString(repo.resolve(name), "changed input\n");
            var after = packager.basisDigest();
            assertNotEquals(before, after, name);
            before = after;
        }
    }

    @Test
    void migrationOnlyChangeDoesNotForceRuntimeBasisReplacement() throws Exception {
        var source = packager.basisDigest();
        var contract = packager.basisContract();
        Files.writeString(
                repo.resolve("backend/common/src/main/resources/db/migration/V2__added.sql"),
                "SELECT 1;\n");
        assertNotEquals(source, packager.basisDigest());
        assertEquals(contract, packager.basisContract());
    }

    @Test
    void wiremockAndDataContractsRemainVisibleToRuntimeValidation() throws Exception {
        var contract = packager.basisContract();
        Files.writeString(
                repo.resolve("dev/wiremock/mappings/location.json"), "new mock response\n");
        assertNotEquals(contract, packager.basisContract());
        var preparation = packager.preparationContract();
        Files.writeString(repo.resolve("dev/data/java/Data.java"), "new fixture generator\n");
        assertNotEquals(preparation, packager.preparationContract());
    }
}
