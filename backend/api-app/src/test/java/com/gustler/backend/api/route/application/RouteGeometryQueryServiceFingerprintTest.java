package com.gustler.backend.api.route.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.route.InvalidReferenceVersionException;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.application.RouteDisplayRepository.GenerationState;
import com.gustler.backend.api.route.application.RouteDisplayRepository.StoredInputs;
import com.gustler.backend.api.route.application.RouteDisplayRepository.StoredRead;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RouteGeometryQueryServiceFingerprintTest {
    private static final RouteId ID = new RouteId("204000057");
    private static final UUID SHAPE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private String name = "3330";
    private double stopX = 127.001;
    private Shape shape = shape(RouteShapeEvidence.EMPTY);
    private int storedReads;
    private int legacyReads;

    private final RouteGeometryQueryRepository source = new RouteGeometryQueryRepository() {
        @Override
        public Optional<Reference> reference(String routeId) {
            if (!routeId.equals(ID.value())) {
                return Optional.empty();
            }
            return Optional.of(new Reference(routeId, 7, name, "A", "B", 2));
        }

        @Override
        public List<Stop> stops(final long versionId) {
            return List.of(new Stop("A", 1, "A", "UP", true, new Point(127, 37)),
                    new Stop("B", 2, "B", "UP", true, new Point(stopX, 37)),
                    new Stop("C", 3, "C", "DOWN", true, new Point(127, 37.0001)));
        }

        @Override
        public Optional<Shape> shape(final long versionId) {
            return Optional.ofNullable(shape);
        }
    };

    private final RouteDisplayRepository displays = new RouteDisplayRepository() {
        @Override
        public Optional<Manual> findManual(String routeId, String inputDigest) {
            legacyReads++;
            return Optional.empty();
        }

        @Override
        public Map<String, GenerationState> findGenerationStates(String routeId, String inputDigest,
                String evidenceRevision, List<String> keys) {
            legacyReads++;
            return Map.of(keys.getFirst(), new GenerationState("RUNNING", true, false));
        }

        @Override
        public Optional<StoredRead> findStoredInputs(String routeId, final long versionId, String digest,
                String evidence, String manifestId, List<String> keys) {
            storedReads++;
            return Optional.of(new StoredRead(new StoredInputs(null, null, List.of(),
                    Map.of(keys.getFirst(), new GenerationState("RUNNING", true, false)), null), null));
        }
    };

    private final RouteGeometryQueryService service = new RouteGeometryQueryService(
            source, Optional.of(new RouteDisplaySelector(displays, new ObjectMapper(), true)));

    private static Shape shape(RouteShapeEvidence evidence) {
        return new Shape(SHAPE_ID, Instant.EPOCH, List.of(new Point(127, 37), new Point(127.001, 37),
                new Point(127.001, 37.0001), new Point(127, 37.0001)), evidence, null);
    }

    /** 서비스가 부른 순서를 기록하고, {@code cached}가 있으면 재사용으로 답한다. */
    private static final class Recorder implements RouteGeometryResponder<RouteGeometry> {
        private final RouteGeometry cached;
        private final List<RouteGeometryFingerprint> asked = new java.util.ArrayList<>();
        private RouteGeometryFingerprint created;
        private int creations;

        private Recorder(RouteGeometry cached) {
            this.cached = cached;
        }

        @Override
        public Optional<RouteGeometry> reuse(RouteGeometryFingerprint fingerprint) {
            asked.add(fingerprint);
            return Optional.ofNullable(cached);
        }

        @Override
        public RouteGeometry create(RouteGeometry geometry, RouteGeometryFingerprint fingerprint) {
            creations++;
            created = fingerprint;
            return geometry;
        }
    }

    private RouteGeometryFingerprint fingerprint() {
        Recorder recorder = new Recorder(null);
        service.respond(ID, null, recorder);
        return recorder.created;
    }

    @Test
    void 미적중이면_지문을_물은_뒤_같은_입력으로_조립하고_get과_같은_형상을_만든다() {
        Recorder recorder = new Recorder(null);

        RouteGeometry geometry = service.respond(ID, null, recorder);

        assertThat(recorder.asked).hasSize(1);
        assertThat(recorder.created).isEqualTo(recorder.asked.getFirst());
        assertThat(geometry).isEqualTo(service.get(ID, null));
        assertThat(geometry.display()).isNotNull();
    }

    @Test
    void 적중이면_조립하지_않고_재사용한_응답을_준다() {
        RouteGeometry cached = service.get(ID, null);
        Recorder recorder = new Recorder(cached);

        RouteGeometry geometry = service.respond(ID, 7L, recorder);

        assertThat(geometry).isSameAs(cached);
        assertThat(recorder.creations).isZero();
    }

    @Test
    void 미적중_경로는_저장_자료를_한_번만_읽고_예전_조립보다_조회가_많지_않다() {
        storedReads = 0;
        legacyReads = 0;
        service.respond(ID, null, new Recorder(null));
        final int prepared = storedReads + legacyReads;

        storedReads = 0;
        legacyReads = 0;
        RouteDisplaySelector legacy = new RouteDisplaySelector(new RouteDisplayRepository() {
            @Override
            public Optional<Manual> findManual(String routeId, String inputDigest) {
                return displays.findManual(routeId, inputDigest);
            }

            @Override
            public Map<String, GenerationState> findGenerationStates(String routeId, String inputDigest,
                    String evidenceRevision, List<String> keys) {
                return displays.findGenerationStates(routeId, inputDigest, evidenceRevision, keys);
            }
        }, new ObjectMapper(), true);
        new RouteGeometryQueryService(source, Optional.of(legacy)).get(ID, null);

        assertThat(storedReads).isZero();
        assertThat(prepared).isLessThanOrEqualTo(legacyReads);
    }

    @Test
    void 이름_정류장좌표_저장된_보조근거가_바뀌면_지문이_달라진다() {
        var original = fingerprint();

        name = "3330-1";
        var renamed = fingerprint();
        name = "3330";
        stopX = 127.0011;
        var moved = fingerprint();
        stopX = 127.001;
        shape = shape(new RouteShapeEvidence("GBIS_API",
                List.of(new RouteShapeEvidence.Source("SUPPLEMENTARY_CACHE", "INVALID", null)), List.of()));
        var reevidenced = fingerprint();

        assertThat(List.of(renamed, moved, reevidenced)).doesNotContain(original).doesNotHaveDuplicates();
        assertThat(reevidenced.sourceRevision()).isEqualTo(original.sourceRevision());
    }

    @Test
    void 형상이_없으면_재사용을_묻지_않고_지문_없이_조립하며_판본오류는_get과_같게_던진다() {
        shape = null;
        Recorder recorder = new Recorder(null);
        service.respond(ID, null, recorder);
        assertThat(recorder.asked).isEmpty();
        assertThat(recorder.created).isNull();
        assertThat(recorder.creations).isEqualTo(1);
        assertThat(storedReads).isZero();

        assertThatThrownBy(() -> service.respond(ID, 0L, new Recorder(null)))
                .isInstanceOf(InvalidReferenceVersionException.class);
        assertThatThrownBy(() -> service.respond(new RouteId("204000058"), null, new Recorder(null)))
                .isInstanceOf(RouteNotFoundException.class);
        assertThatThrownBy(() -> service.respond(ID, 6L, new Recorder(null)))
                .isInstanceOf(RouteGeometryReferenceConflictException.class);
    }

    @Test
    void 표시선택이_없으면_원본만으로_지문을_만든다() {
        var plain = new RouteGeometryQueryService(source);
        Recorder recorder = new Recorder(null);

        plain.respond(ID, null, recorder);

        assertThat(recorder.created.display()).isEqualTo(RouteDisplayFingerprint.disabled());
        assertThat(recorder.created.sourceRevision()).isEqualTo(plain.get(ID, null).revision());
        assertThat(storedReads).isZero();
    }
}
