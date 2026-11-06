package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class FixtureDigestCompatibilityTest {
    private Map<String, String> files(String contract) throws Exception {
        Path data = Path.of("../../../dev/data");
        Map<String, String> files = new TreeMap<>();
        for (String name : DataPreparation.FILES) files.put("tools/data/" + name, FileOps.sha(data.resolve(name)));
        files.put("tools/data-contract.sha256", contract);
        files.put("infra/compose.yaml", "unchanged");
        return files;
    }

    private RevisionStore.Revision revision(Map<String, String> files) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
            "c".repeat(64), false, Map.copyOf(files), "d".repeat(64));
    }

    @Test
    void 기존_초기_데이터를_그대로_둔_지문_변환_코드만_교체하고_복구할_수_있다() throws Exception {
        // given
        var before = files(CompatiblePreparation.NEXT);
        var after = files(CompatiblePreparation.FIXTURE_DIGEST);

        // when / then
        assertDoesNotThrow(() -> BasisLifecycle.preserveData(revision(before), revision(after), null));
        assertDoesNotThrow(() -> BasisLifecycle.preserveData(revision(after), revision(before), null));
        assertEquals("a7dea7fd5aaccbee29e343b932bc658fb20e4131847fd50370e8f5305492513d", before.get("tools/data/routes.json"));
        assertEquals("0c1d09aec2660e03475c7a82930fc3632432c177b148c7b7d7689f74ffd3997a", before.get("tools/data/catalog-routes.json"));
    }

    @Test
    void 지문_변환_코드가_허용되어도_초기_데이터나_인프라_파일이_달라지면_거절한다() throws Exception {
        // given
        var before = files(CompatiblePreparation.NEXT);
        var after = files(CompatiblePreparation.FIXTURE_DIGEST);

        // when / then
        for (String name : before.keySet()) {
            if (name.equals("tools/data-contract.sha256")) continue;
            var changed = new TreeMap<>(after);
            changed.put(name, "changed");
            assertEquals("DEV_DATA_CHANGE_REQUIRES_PLAN", assertThrows(IllegalArgumentException.class,
                () -> BasisLifecycle.preserveData(revision(before), revision(changed), null), name).getMessage());
            changed.remove(name);
            assertEquals("DEV_DATA_CHANGE_REQUIRES_PLAN", assertThrows(IllegalArgumentException.class,
                () -> BasisLifecycle.preserveData(revision(before), revision(changed), null), name).getMessage());
        }
    }
}
