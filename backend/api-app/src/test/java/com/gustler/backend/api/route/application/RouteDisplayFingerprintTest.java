package com.gustler.backend.api.route.application;

import static com.gustler.backend.routegeometry.api.RouteDisplaySamples.manual;
import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.route.application.RouteDisplayRepository.ArtifactRow;
import com.gustler.backend.api.route.application.RouteDisplayRepository.GenerationState;
import com.gustler.backend.api.route.application.RouteDisplayRepository.ManifestRow;
import com.gustler.backend.api.route.application.RouteDisplayRepository.ManualRow;
import com.gustler.backend.api.route.application.RouteDisplayRepository.StoredInputs;
import com.gustler.backend.api.route.application.RouteDisplayRepository.StoredRead;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * 표시 선택 지문이 {@link RouteDisplaySelector#enrich}가 읽는 저장 자료를 모두 덮는지 검사한다.
 *
 * <p>응답 캐시는 지문이 같으면 같은 응답을 낸다고 본다. 그래서 enrich가 부르는 조회의 인자가 지문을 읽을 때 넘긴 인자 안에 있어야 하고,
 * 지문을 읽은 경로가 예전 경로보다 조회를 더 하지 않아야 한다.
 */
class RouteDisplayFingerprintTest {
    private static final Instant NOW = Instant.parse("2026-11-13T00:00:00Z");
    private static final String MANIFEST = "a".repeat(64);

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, RouteDisplayRepository.Automatic> artifacts = new HashMap<>();
    private final Map<String, String> algorithms = new HashMap<>();
    private final Map<String, RouteDisplayRepository.SourceManifest> manifests = new HashMap<>();
    private final Set<String> corruptedManifests = new HashSet<>();
    private final Map<String, RouteDisplayRepository.GenerationState> jobs = new HashMap<>();
    private RouteDisplayRepository.Manual reviewed;
    private final List<List<Object>> reads = new ArrayList<>();
    private final List<List<Object>> fingerprintArguments = new ArrayList<>();

    private final RouteDisplayRepository repository = new RouteDisplayRepository() {
        @Override
        public Optional<Manual> findManual(String routeId, String digest) {
            reads.add(List.of("manual", routeId, digest));
            return Optional.ofNullable(reviewed);
        }

        @Override
        public Optional<Automatic> findAutomatic(String key, String routeId, String digest, String evidence,
                String algorithm) {
            reads.add(List.of("automatic", key, routeId, digest, evidence));
            return Optional.ofNullable(artifacts.get(key));
        }

        @Override
        public Map<String, GenerationState> findGenerationStates(String routeId, String digest, String evidence,
                List<String> keys) {
            reads.add(List.of("states", routeId, digest, evidence, keys));
            var found = new HashMap<String, GenerationState>();
            keys.stream().filter(jobs::containsKey).forEach(key -> found.put(key, jobs.get(key)));
            return found;
        }

        @Override
        public Optional<GenerationState> findSourceRecovery(String routeId, long versionId) {
            reads.add(List.of("recovery", routeId, versionId));
            return Optional.empty();
        }

        @Override
        public Optional<SourceManifest> findSourceManifest(String id) {
            reads.add(List.of("manifest", id));
            if (corruptedManifests.contains(id)) {
                throw new IllegalArgumentException("Corrupt sealed provenance");
            }
            return Optional.ofNullable(manifests.get(id));
        }

        @Override
        public Optional<StoredRead> findStoredInputs(String routeId, final long versionId, String digest,
                String evidence, String manifestId, List<String> keys) {
            if (!storedReadable) {
                return Optional.empty();
            }
            var arguments = new ArrayList<Object>(List.of(routeId, versionId, digest, evidence));
            arguments.add(manifestId);
            arguments.add(List.copyOf(keys));
            fingerprintArguments.add(arguments);
            ManualRow manual = reviewed == null ? null : new ManualRow(1, reviewed.revision(), "1");
            ManifestRow manifest = manifestId == null
                    || !manifests.containsKey(manifestId) && !corruptedManifests.contains(manifestId) ? null
                    : new ManifestRow(manifestId, 1, versionId, digest, evidence, null, routeId);
            var rows = keys.stream().filter(artifacts::containsKey).sorted()
                    .map(key -> new ArtifactRow(key, algorithms.get(key), artifacts.get(key).sha256(),
                            artifacts.get(key).sourceManifestId(), "1"))
                    .toList();
            var states = new HashMap<String, GenerationState>();
            keys.stream().filter(jobs::containsKey).forEach(key -> states.put(key, jobs.get(key)));
            return Optional.of(new StoredRead(new StoredInputs(manual, manifest, rows, states, null),
                    reviewed == null ? null : reviewed.document()));
        }
    };
    private boolean storedReadable = true;

    private RouteDisplaySelector selector(Instant now) {
        return new RouteDisplaySelector(repository, json, true, Optional.of(Clock.fixed(now, ZoneOffset.UTC)));
    }

    private RouteGeometry geometry(String manifestId, RouteShapeEvidence evidence) {
        Point a = new Point(127, 37);
        Point b = new Point(127.001, 37);
        Point end = new Point(127, 37.0001);
        return new RouteGeometry("204000057", 1, "3330", "A", "B", 2,
                List.of(new Stop("A", 1, "A", "UP", true, a), new Stop("B", 2, "B", "UP", true, b),
                        new Stop("C", 3, "C", "DOWN", true, end)),
                new Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH,
                        List.of(a, b, new Point(127.001, 37.0001), end), evidence, manifestId));
    }

    private String key(RouteGeometry g, String algorithm, String manifestId) {
        return RouteDisplayGeneration.key(RouteDisplayGeneration.input(g).digest(), g.shape().evidence().revision(),
                algorithm, manifestId);
    }

    private void store(RouteGeometry g, RouteDisplayGeometry display, String algorithm, String manifestId) {
        String text = json.writeValueAsString(display);
        algorithms.put(key(g, algorithm, manifestId), algorithm);
        artifacts.put(key(g, algorithm, manifestId),
                new RouteDisplayRepository.Automatic(text, RouteDisplayGeneration.sha256(text), manifestId));
    }

    /**
     * 저장 자료 식별값을 읽는 경로와 그렇지 않은 예전 경로가 같은 결과를 내는지, 식별값을 읽는 경로의 조회 수(식별값 1회 포함)가 예전 경로보다
     * 많지 않은지, 그리고 그 경로가 지문이 식별한 문서만 읽는지 본다.
     */
    private void assertReadsCovered(RouteGeometry g, Consumer<RouteGeometry> arrange) {
        arrange.accept(g);
        storedReadable = false;
        reads.clear();
        var legacySelector = selector(NOW);
        assertThat(legacySelector.fingerprint(g)).isEmpty();
        reads.clear();
        RouteGeometry legacy = legacySelector.enrich(g);
        final int legacyReads = reads.size();

        storedReadable = true;
        reads.clear();
        fingerprintArguments.clear();
        var selector = selector(NOW);
        var prepared = selector.prepare(g);
        assertThat(prepared.fingerprint()).isPresent();
        RouteGeometry selected = selector.enrich(prepared);

        assertThat(selected).isEqualTo(legacy);
        assertThat(fingerprintArguments).hasSize(1);
        assertThat(1 + reads.size()).isLessThanOrEqualTo(legacyReads);
        var arguments = fingerprintArguments.getFirst();
        String digest = (String) arguments.get(2);
        String evidence = (String) arguments.get(3);
        @SuppressWarnings("unchecked") List<String> keys = (List<String>) arguments.get(5);
        assertThat(arguments.get(0)).isEqualTo(g.routeId());
        assertThat(arguments.get(1)).isEqualTo(g.referenceVersionId());
        assertThat(arguments.get(4)).isEqualTo(g.shape().sourceManifestId());
        var identified = prepared.fingerprint().orElseThrow().stored();
        for (List<Object> read : reads) {
            switch ((String) read.getFirst()) {
                case "automatic" -> {
                    assertThat(keys).contains((String) read.get(1));
                    assertThat(identified.artifacts()).anySatisfy(row -> assertThat(row.generationKey()).isEqualTo(read.get(1)));
                    assertThat(read.subList(2, 5)).containsExactly(g.routeId(), digest, evidence);
                }
                case "manifest" -> {
                    assertThat(read).containsExactly("manifest", g.shape().sourceManifestId());
                    assertThat(identified.manifest()).isNotNull();
                }
                default -> throw new AssertionError("식별값으로 대신해야 하는 조회를 다시 했다: " + read);
            }
        }
    }

    @Test
    void 표시선택이_읽는_조회는_모두_지문의_인자_안에_있다() {
        var plain = geometry(null, RouteShapeEvidence.EMPTY);
        assertReadsCovered(plain, g -> { });
        assertReadsCovered(plain, g -> store(g, AutomaticRouteDisplay.build(g, RouteDisplayGeneration.input(g).digest()),
                AutomaticRouteDisplay.REVISION, null));
        assertReadsCovered(plain, g -> reviewed = new RouteDisplayRepository.Manual(
                json.writeValueAsString(manual(RouteDisplayGeneration.input(g), 0.0001)), "reviewed"));
        assertReadsCovered(plain, g -> reviewed = new RouteDisplayRepository.Manual("{}", "broken"));
        reviewed = null;
        artifacts.clear();
        assertReadsCovered(plain, g -> {
            store(g, AutomaticRouteDisplay.sourceOnly(g, RouteDisplayGeneration.input(g).digest()),
                    RouteDisplayGeneration.SOURCE_REVISION, null);
            jobs.put(key(g, AutomaticRouteDisplay.REVISION, null),
                    new RouteDisplayRepository.GenerationState("FAILED", false));
        });
    }

    @Test
    void 입력출처가_있으면_출처가_맞든_틀리든_손상됐든_읽는_생성키를_모두_덮는다() {
        var captured = geometry(MANIFEST, RouteShapeEvidence.EMPTY);
        String digest = RouteDisplayGeneration.input(captured).digest();
        assertReadsCovered(captured, g -> manifests.put(MANIFEST, new RouteDisplayRepository.SourceManifest(
                digest, g.shape().evidence().revision(), g.referenceVersionId())));
        assertReadsCovered(captured, g -> manifests.put(MANIFEST, new RouteDisplayRepository.SourceManifest(
                "b".repeat(64), g.shape().evidence().revision(), g.referenceVersionId())));
        assertReadsCovered(captured, g -> store(g, AutomaticRouteDisplay.build(g, digest),
                AutomaticRouteDisplay.REVISION, null));
        assertReadsCovered(captured, g -> reviewed = new RouteDisplayRepository.Manual(
                json.writeValueAsString(manual(RouteDisplayGeneration.input(g), 0.0001)), "reviewed"));
        reviewed = null;
        assertReadsCovered(captured, g -> corruptedManifests.add(MANIFEST));

        @SuppressWarnings("unchecked") List<String> keys = (List<String>) fingerprintArguments.getFirst().get(5);
        assertThat(keys).containsExactly(
                key(captured, AutomaticRouteDisplay.REVISION, MANIFEST),
                key(captured, RouteDisplayGeneration.SOURCE_REVISION, MANIFEST),
                key(captured, AutomaticRouteDisplay.REVISION, null),
                key(captured, RouteDisplayGeneration.SOURCE_REVISION, null));
    }

    @Test
    void 보조근거의_유효기한이_지나면_같은_저장자료여도_지문이_달라진다() {
        var evidence = new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("GG_BMS_NODES", "AVAILABLE", null, NOW.plusSeconds(60))),
                List.of());
        var g = geometry(null, evidence);

        var before = selector(NOW).fingerprint(g);
        var same = selector(NOW.plusSeconds(59)).fingerprint(g);
        var after = selector(NOW.plusSeconds(60)).fingerprint(g);

        assertThat(same).isEqualTo(before);
        assertThat(after).isNotEqualTo(before);
        assertThat(after.orElseThrow().evidenceRevision()).isEqualTo(evidence.at(NOW.plusSeconds(60)).revision());
    }

    @Test
    void 꺼져_있거나_형상이_없거나_입력이_무효이면_저장자료를_읽지_않는다() {
        var g = geometry(null, RouteShapeEvidence.EMPTY);
        var disabled = new RouteDisplaySelector(repository, json, false);
        assertThat(disabled.fingerprint(g)).contains(RouteDisplayFingerprint.disabled());

        var noShape = new RouteGeometry(g.routeId(), 1, g.name(), g.startPoint(), g.endPoint(), 2, g.busStops(), null);
        assertThat(selector(NOW).fingerprint(noShape)).isEmpty();

        var noVisits = new RouteGeometry(g.routeId(), 1, g.name(), g.startPoint(), g.endPoint(), 2, List.of(), g.shape());
        assertThat(selector(NOW).fingerprint(noVisits))
                .contains(RouteDisplayFingerprint.invalidInput(RouteShapeEvidence.EMPTY.revision()));
        assertThat(fingerprintArguments).isEmpty();
    }
}
