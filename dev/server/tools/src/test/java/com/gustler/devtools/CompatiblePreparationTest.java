package com.gustler.devtools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CompatiblePreparationTest {
    @Test
    void 모델_파일_교체는_노선이나_DB_변경으로_취급하지_않는다() {
        assertThat(CompatiblePreparation.permits(revision(CompatiblePreparation.FIXTURE_DIGEST),
                revision(CompatiblePreparation.ENV_MODEL_DATA))).isTrue();
        var previous = new TreeMap<String, String>();
        previous.put("tools/data-contract.sha256", CompatiblePreparation.ALIGHTING_AWARE_EVALUATION_DATA);
        previous.put("infra/compose.yaml", "same");
        previous.put("tools/data/routes.json", "routes");
        previous.put("tools/data/catalog-routes.json", "catalog");
        previous.put("tools/data/model-reference/manifest.json", "old");
        previous.put("tools/data/model-reference/weights.safetensors", "old");
        var next = new TreeMap<>(previous);
        next.put("tools/data-contract.sha256", CompatiblePreparation.ENV_MODEL_DATA);
        next.remove("tools/data/model-reference/weights.safetensors");
        next.put("tools/data/model-reference/manifest.json", "new");
        next.put("tools/data/model-reference/base.json", "new");
        assertThatCode(() -> BasisLifecycle.preserveData(revisionWithFiles(previous), revisionWithFiles(next), null))
                .doesNotThrowAnyException();
        next.put("tools/data/routes.json", "different");
        assertThatThrownBy(() -> BasisLifecycle.preserveData(revisionWithFiles(previous), revisionWithFiles(next), null))
                .hasMessage("DEV_DATA_CHANGE_REQUIRES_PLAN");
    }

    private RevisionStore.Revision revision(String contract) {
        return revisionWithFiles(Map.of("tools/data-contract.sha256", contract));
    }

    @Test
    void 검토한_이전_버전에서_새_버전으로만_초기화_코드를_교체한다() {
        RevisionStore.Revision old = revision(CompatiblePreparation.PREVIOUS);
        RevisionStore.Revision next = revision(CompatiblePreparation.NEXT);

        assertSoftly(softly -> {
            softly.assertThat(CompatiblePreparation.permits(old, next)).isTrue();
            softly.assertThat(CompatiblePreparation.permits(old, revision(CompatiblePreparation.COLLECTED_DATA)))
                .isTrue();
            softly.assertThat(CompatiblePreparation.permits(revision(CompatiblePreparation.COLLECTED_DATA), next))
                .isTrue();
            softly.assertThat(CompatiblePreparation.permits(next, revision(CompatiblePreparation.COLLECTED_DATA)))
                .isFalse();
            softly.assertThat(CompatiblePreparation.permits(next, old)).isFalse();
            RevisionStore.Revision fixture = revision(CompatiblePreparation.FIXTURE_DIGEST);
            softly.assertThat(CompatiblePreparation.permits(next, fixture)).isTrue();
            softly.assertThat(CompatiblePreparation.permits(fixture, next)).isTrue();
            softly.assertThat(CompatiblePreparation.permits(revision("unknown"), next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(old, revision("unknown"))).isFalse();
        });
    }

    @Test
    void 하차_반영_정산_초기화는_검토한_과거_판에서만_전환한다() {
        RevisionStore.Revision next = revision(CompatiblePreparation.ALIGHTING_AWARE_EVALUATION_DATA);
        assertSoftly(softly -> {
            for (String previous : List.of(CompatiblePreparation.PREVIOUS, CompatiblePreparation.COLLECTED_DATA,
                CompatiblePreparation.EVALUATION_DATA, CompatiblePreparation.NEXT,
                CompatiblePreparation.FIXTURE_DIGEST, CompatiblePreparation.PREVIOUS_ALIGHTING_AWARE_EVALUATION_DATA)) {
                softly.assertThat(CompatiblePreparation.permits(revision(previous), next)).isTrue();
                softly.assertThat(CompatiblePreparation.permits(next, revision(previous))).isFalse();
            }
            softly.assertThat(CompatiblePreparation.permits(revision("unknown"), next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(next, next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(revisionWithFiles(Map.of()), next)).isFalse();
        });
    }

    @Test
    void 원본_보관_초기화는_검토한_과거_판에서만_전환한다() {
        RevisionStore.Revision next = revision(CompatiblePreparation.ALIGHTING_MODEL_DATA);
        assertSoftly(softly -> {
            for (String previous : List.of(CompatiblePreparation.PREVIOUS, CompatiblePreparation.COLLECTED_DATA,
                CompatiblePreparation.EVALUATION_DATA, CompatiblePreparation.NEXT,
                CompatiblePreparation.FIXTURE_DIGEST, CompatiblePreparation.PREVIOUS_ALIGHTING_AWARE_EVALUATION_DATA,
                CompatiblePreparation.ALIGHTING_AWARE_EVALUATION_DATA, CompatiblePreparation.ARCHIVE_DATA)) {
                softly.assertThat(CompatiblePreparation.permits(revision(previous), next)).isTrue();
                softly.assertThat(CompatiblePreparation.permits(next, revision(previous))).isFalse();
            }
            softly.assertThat(CompatiblePreparation.permits(revision("unknown"), next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(next, next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(revisionWithFiles(Map.of()), next)).isFalse();
        });
    }

    @Test
    void 허용한_코드_전환이어도_기존_초기_데이터_변경은_거절한다() {
        Map<String, String> oldFiles = new TreeMap<>();
        for (String name : DataPreparation.FILES) {
            oldFiles.put("tools/data/" + name, "unchanged");
        }
        for (String name : List.of("config/mongodb.yml", "infra/compose.yaml", "infra/init-chat.js")) {
            oldFiles.put(name, "unchanged");
        }
        oldFiles.put("tools/data-contract.sha256", CompatiblePreparation.PREVIOUS);
        Map<String, String> newFiles = new TreeMap<>(oldFiles);
        newFiles.put("tools/data-contract.sha256", CompatiblePreparation.ARCHIVE_DATA);
        RevisionStore.Revision old = revisionWithFiles(oldFiles);

        assertThatCode(() -> BasisLifecycle.preserveData(old, revisionWithFiles(newFiles), null))
            .doesNotThrowAnyException();
        newFiles.put("tools/data/routes.json", "changed");
        assertThatThrownBy(() -> BasisLifecycle.preserveData(old, revisionWithFiles(newFiles), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("DEV_DATA_CHANGE_REQUIRES_PLAN");
    }

    @Test
    void 증분_정산_초기화는_이전_계약을_보존하면서_앞으로만_전환한다() {
        var next = revision(CompatiblePreparation.INCREMENTAL_SETTLEMENT_DATA);
        assertSoftly(softly -> {
            for (String previous : List.of(CompatiblePreparation.PREVIOUS, CompatiblePreparation.COLLECTED_DATA,
                CompatiblePreparation.EVALUATION_DATA, CompatiblePreparation.NEXT, CompatiblePreparation.FIXTURE_DIGEST,
                CompatiblePreparation.PREVIOUS_ALIGHTING_AWARE_EVALUATION_DATA, CompatiblePreparation.ALIGHTING_AWARE_EVALUATION_DATA,
                CompatiblePreparation.ARCHIVE_DATA, CompatiblePreparation.ALIGHTING_MODEL_DATA, CompatiblePreparation.SINGLE_MODEL_DATA,
                CompatiblePreparation.DATABASE_CLOCK_DATA, CompatiblePreparation.ENV_MODEL_DATA)) {
                softly.assertThat(CompatiblePreparation.permits(revision(previous), next)).isTrue();
                softly.assertThat(CompatiblePreparation.permits(next, revision(previous))).isFalse();
            }
            softly.assertThat(CompatiblePreparation.permits(revision(CompatiblePreparation.DATABASE_CLOCK_DATA),
                revision(CompatiblePreparation.ENV_MODEL_DATA))).isTrue();
            softly.assertThat(CompatiblePreparation.permits(next, next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(revision("unknown"), next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(next, revision("unknown"))).isFalse();
            softly.assertThat(CompatiblePreparation.permits(revisionWithFiles(Map.of()), next)).isFalse();
            softly.assertThat(CompatiblePreparation.permits(next, revisionWithFiles(Map.of()))).isFalse();
        });
    }

    @Test
    void 증분_정산_초기화_전환에도_기존_데이터의_변경과_누락을_거절한다() {
        var previous = new TreeMap<String, String>();
        for (String name : DataPreparation.FILES) {
            previous.put("tools/data/" + name, "unchanged");
        }
        previous.put("infra/compose.yaml", "unchanged");
        previous.put("tools/data-contract.sha256", CompatiblePreparation.ENV_MODEL_DATA);
        var next = new TreeMap<>(previous);
        next.put("tools/data-contract.sha256", CompatiblePreparation.INCREMENTAL_SETTLEMENT_DATA);
        assertThatCode(() -> BasisLifecycle.preserveData(revisionWithFiles(previous), revisionWithFiles(next), null))
            .doesNotThrowAnyException();
        for (String path : previous.keySet().stream().filter(key -> (key.startsWith("tools/data/")
            && !key.startsWith("tools/data/model-reference/")) || key.equals("infra/compose.yaml")).toList()) {
            var changed = new TreeMap<>(next);
            changed.put(path, "changed");
            assertThatThrownBy(() -> BasisLifecycle.preserveData(revisionWithFiles(previous), revisionWithFiles(changed), null))
                .hasMessage("DEV_DATA_CHANGE_REQUIRES_PLAN");
            changed.remove(path);
            assertThatThrownBy(() -> BasisLifecycle.preserveData(revisionWithFiles(previous), revisionWithFiles(changed), null))
                .hasMessage("DEV_DATA_CHANGE_REQUIRES_PLAN");
        }
    }

    private RevisionStore.Revision revisionWithFiles(Map<String, String> files) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
            "c".repeat(64), false, Map.copyOf(files), "d".repeat(64));
    }

    @Test
    void 실제_초기화_코드와_후보_번들의_해시가_검토한_전환과_일치한다() throws Exception {
        Path repo = Path.of("../../..").toAbsolutePath().normalize();
        String contract = new Packager(repo, new Host()).preparationContract();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest((contract + "\n").getBytes(StandardCharsets.UTF_8)));

        assertThat(hash).isEqualTo(CompatiblePreparation.INCREMENTAL_SETTLEMENT_DATA);
    }
}
