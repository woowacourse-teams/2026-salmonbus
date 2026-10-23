package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompatiblePreparationTest {
    private RevisionStore.Revision revision(String contract) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
            "c".repeat(64), false, Map.of("tools/data-contract.sha256", contract), "d".repeat(64));
    }

    @Test
    void 검토한_이전_버전에서_새_버전으로만_초기화_코드를_교체한다() {
        // given
        var old = revision(CompatiblePreparation.PREVIOUS);
        var next = revision(CompatiblePreparation.NEXT);

        // when / then
        assertTrue(CompatiblePreparation.permits(old, next));
        assertTrue(CompatiblePreparation.permits(old, revision(CompatiblePreparation.COLLECTED_DATA)));
        assertTrue(CompatiblePreparation.permits(revision(CompatiblePreparation.COLLECTED_DATA), next));
        assertFalse(CompatiblePreparation.permits(next, revision(CompatiblePreparation.COLLECTED_DATA)));
        assertFalse(CompatiblePreparation.permits(next, old));
        assertFalse(CompatiblePreparation.permits(revision("unknown"), next));
        assertFalse(CompatiblePreparation.permits(old, revision("unknown")));
    }

    @Test
    void 허용한_코드_전환이어도_기존_초기_데이터_변경은_거절한다() {
        // given
        var oldFiles = new java.util.TreeMap<String, String>();
        for (var name : DataPreparation.FILES) oldFiles.put("tools/data/" + name, "unchanged");
        for (var name : java.util.List.of("config/mongodb.yml", "infra/compose.yaml", "infra/init-chat.js"))
            oldFiles.put(name, "unchanged");
        oldFiles.put("tools/data-contract.sha256", CompatiblePreparation.PREVIOUS);
        var newFiles = new java.util.TreeMap<>(oldFiles);
        newFiles.put("tools/data-contract.sha256", CompatiblePreparation.NEXT);
        var old = revisionWithFiles(oldFiles);

        // when / then
        assertDoesNotThrow(() -> BasisLifecycle.preserveData(old, revisionWithFiles(newFiles), null));
        newFiles.put("tools/data/routes.json", "changed");
        assertEquals("DEV_DATA_CHANGE_REQUIRES_PLAN", assertThrows(IllegalArgumentException.class,
            () -> BasisLifecycle.preserveData(old, revisionWithFiles(newFiles), null)).getMessage());
    }

    private RevisionStore.Revision revisionWithFiles(Map<String, String> files) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
            "c".repeat(64), false, Map.copyOf(files), "d".repeat(64));
    }

    @Test
    void 실제_초기화_코드와_후보_번들의_해시가_검토한_전환과_일치한다() throws Exception {
        // given
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        var contract = new Packager(repo, new Host()).preparationContract();

        // when
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((contract + "\n").getBytes(StandardCharsets.UTF_8)));

        // then
        assertEquals(CompatiblePreparation.NEXT, hash);
    }
}
