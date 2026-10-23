package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RefactoredPreparationCompatibilityTest {
    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("reviewedTransitions")
    void 검토한_계약의_순방향_전환만_허용하고_기존_전환도_유지한다(
            Contract from, Contract to, final boolean permitted) {
        // given
        RevisionStore.Revision previous = revision(contractFiles(from.hash()));
        RevisionStore.Revision next = revision(contractFiles(to.hash()));

        // when
        final boolean actual = CompatiblePreparation.permits(previous, next);

        // then
        assertEquals(permitted, actual);
    }

    private static Stream<Arguments> reviewedTransitions() {
        List<Contract> contracts = List.of(
                new Contract("previous", CompatiblePreparation.PREVIOUS),
                new Contract("collected", CompatiblePreparation.COLLECTED_DATA),
                new Contract("evaluation", CompatiblePreparation.EVALUATION_DATA),
                new Contract("refactored", CompatiblePreparation.NEXT),
                new Contract("unknown", "e".repeat(64)),
                new Contract("missing", null));
        Set<String> permitted = Set.of(
                "previous:collected",
                "previous:evaluation",
                "previous:refactored",
                "collected:evaluation",
                "collected:refactored",
                "evaluation:refactored");
        return contracts.stream().flatMap(from -> contracts.stream().map(to ->
                Arguments.of(from, to, permitted.contains(from.name() + ":" + to.name()))));
    }

    @ParameterizedTest(name = "{0}, 제거={1}")
    @MethodSource("protectedDataChanges")
    void 컨벤션_정리_전환에서도_기존_데이터와_설정의_변경이나_누락을_거절한다(
            String path, final boolean remove) {
        // given
        Map<String, String> previousFiles = new TreeMap<>();
        for (String file : DataPreparation.FILES) {
            previousFiles.put("tools/data/" + file, "unchanged");
        }
        for (String file : List.of("config/mongodb.yml", "infra/compose.yaml", "infra/init-chat.js")) {
            previousFiles.put(file, "unchanged");
        }
        previousFiles.put("tools/data-contract.sha256", CompatiblePreparation.EVALUATION_DATA);
        Map<String, String> nextFiles = new TreeMap<>(previousFiles);
        nextFiles.put("tools/data-contract.sha256", CompatiblePreparation.NEXT);
        if (remove) {
            nextFiles.remove(path);
        }
        if (!remove) {
            nextFiles.put(path, "changed");
        }
        RevisionStore.Revision previous = revision(previousFiles);
        RevisionStore.Revision next = revision(nextFiles);

        // when
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> BasisLifecycle.preserveData(previous, next, null));

        // then
        assertEquals("DEV_DATA_CHANGE_REQUIRES_PLAN", exception.getMessage());
    }

    private static Stream<Arguments> protectedDataChanges() {
        Stream<String> data = DataPreparation.FILES.stream().map(file -> "tools/data/" + file);
        Stream<String> config = Stream.of("config/mongodb.yml", "infra/compose.yaml", "infra/init-chat.js");
        return Stream.concat(data, config).flatMap(path -> Stream.of(
                Arguments.of(path, false), Arguments.of(path, true)));
    }

    private static Map<String, String> contractFiles(String hash) {
        if (hash == null) {
            return Map.of();
        }
        return Map.of("tools/data-contract.sha256", hash);
    }

    private static RevisionStore.Revision revision(Map<String, String> files) {
        return new RevisionStore.Revision(1, "dev", "basis", "a".repeat(40), "b".repeat(64),
                "c".repeat(64), false, Map.copyOf(files), "d".repeat(64));
    }

    private record Contract(String name, String hash) {
        @Override
        public String toString() {
            return name;
        }
    }
}
