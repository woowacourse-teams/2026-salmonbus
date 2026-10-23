package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ChatRetirementTest {
    private Map<String, String> files(final boolean legacy) {
        var files = new TreeMap<String, String>();
        files.put("tools/data-contract.sha256", "unchanged");
        files.put("infra/compose.yaml", legacy ? ChatRetirement.OLD_COMPOSE : ChatRetirement.NEW_COMPOSE);
        if (legacy) {
            files.put("config/mongodb.yml", ChatRetirement.OLD_CONFIG);
            files.put("infra/init-chat.js", ChatRetirement.OLD_INIT);
        }
        for (var name : DataPreparation.FILES) files.put("tools/data/" + name, "unchanged");
        return files;
    }

    private RevisionStore.Revision revision(Map<String, String> files) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
                "c".repeat(64), false, Map.copyOf(files), "d".repeat(64));
    }

    @Test
    void exactInfrastructureRetirementAndRollbackPreserveAllBusinessData() {
        var old = revision(files(true));
        var next = revision(files(false));
        assertDoesNotThrow(() -> BasisLifecycle.preserveData(old, next, null));
        assertDoesNotThrow(() -> BasisLifecycle.preserveData(next, old, null));
        for (var path : files(false).keySet()) {
            var changed = files(false);
            changed.put(path, "changed");
            assertThrows(IllegalArgumentException.class,
                    () -> BasisLifecycle.preserveData(old, revision(changed), null), path);
            changed.remove(path);
            assertThrows(IllegalArgumentException.class,
                    () -> BasisLifecycle.preserveData(old, revision(changed), null), path);
        }
    }

    @Test
    void rejectsUnreviewedLegacyConfigurationAndInfrastructure() {
        for (var path : ChatRetirement.INFRA_FILES) {
            var old = files(true);
            old.put(path, "unreviewed");
            assertThrows(IllegalArgumentException.class,
                    () -> BasisLifecycle.preserveData(revision(old), revision(files(false)), null), path);
        }
    }

    @Test
    void composedInfrastructureMatchesTheReviewedRetirement() throws Exception {
        assertEquals(ChatRetirement.NEW_COMPOSE, FileOps.sha(Path.of("../compose.yaml")));
    }

    @Test
    void freshSettingsRequireOnlyTheBusinessDatabaseAndMock() {
        var settings = SettingsPreparation.create(null, "a".repeat(40));
        assertEquals(java.util.Set.of("api", "worker", "postgres", "infra"), settings.keySet());
        assertEquals(java.util.Set.of("DB_URL", "DB_USERNAME", "DB_PASSWORD"), settings.get("api").keySet());
        assertDoesNotThrow(() -> Environment.validate(settings));
    }
}
