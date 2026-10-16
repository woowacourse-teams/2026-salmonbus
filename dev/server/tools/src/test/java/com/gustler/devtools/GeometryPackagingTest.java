package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeometryPackagingTest {
    @TempDir Path root;

    @Test
    void replayFixtureIsCopiedAndChangesBasisWithoutChangingDatabasePreparation() throws Exception {
        Path repo = root.resolve("repo");
        for (String name : List.of(
            "dev/server/tools/src/main/Tool.java", "dev/server/tools/build.gradle",
            "dev/server/tools/settings.gradle", "dev/server/api.yml", "dev/server/worker.yml",
            "dev/server/replay.json", "dev/server/compose.yaml", "dev/server/init-chat.js",
            "dev/server/wiremock.Dockerfile", "dev/server/systemd/salmonbus-dev-infra.service",
            "dev/config/mongodb.yml", "dev/data/java/Data.java", "dev/data/routes.json",
            "dev/data/catalog-routes.json", "dev/data/route-shapes.json",
            "dev/data/model-reference/manifest.json", "dev/wiremock/mappings/route-line.json",
            "dev/wiremock-extension/src/main/Replay.java",
            "dev/wiremock-extension/build/libs/local-gbis-replay.jar",
            "backend/business/forecasting/src/main/java/com/gustler/backend/forecasting/domain/model/ForecastFeatureContract.java")) {
            Path file = repo.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, "fixture:" + name);
        }
        var packager = new Packager(repo, new Host());
        String basis = packager.basisContract();
        String preparation = packager.preparationContract();
        Path image = root.resolve("image");
        packager.imageContext(image);
        assertEquals(-1, Files.mismatch(repo.resolve("dev/data/route-shapes.json"),
            image.resolve("data/route-shapes.json")));
        assertTrue(Files.exists(image.resolve("mappings/route-line.json")));
        assertFalse(Files.exists(image.resolve("data/model-reference")));

        Files.writeString(repo.resolve("dev/data/route-shapes.json"), "new road snapshot");
        assertNotEquals(basis, packager.basisContract());
        assertEquals(preparation, packager.preparationContract());
        Path replacement = root.resolve("replacement");
        packager.imageContext(replacement);
        assertEquals("new road snapshot", Files.readString(replacement.resolve("data/route-shapes.json")));
    }
}
