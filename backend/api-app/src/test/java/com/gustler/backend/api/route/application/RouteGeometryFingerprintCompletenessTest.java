package com.gustler.backend.api.route.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 지도 형상 응답 캐시의 지문이 응답을 정하는 입력을 모두 담는지 지키는 시험이다.
 *
 * <p>아래 레코드의 구성 요소나 저장소의 조회 메서드가 바뀌면 실패한다. 실패하면 새 값이 응답을 바꾸는지 확인하고, 바꾼다면
 * {@link RouteGeometryFingerprint}(원본 판본 {@link RouteGeometry#revision()}이 덮지 않으면 따로)와
 * {@link RouteDisplayRepository#findStoredInputs}가 그 값을 담게 고친 뒤 이 목록을 고친다. 지문이 입력을 놓치면 같은 지문에 낡은 응답이
 * 안전 상한({@code route-geometry.response-cache.max-age})까지 나간다.
 */
class RouteGeometryFingerprintCompletenessTest {

    private static List<String> components(Class<?> type) {
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<String> readMethods(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
            .map(Method::getName)
            .distinct()
            .sorted()
            .toList();
    }

    @Test
    void 원본_형상을_이루는_레코드의_구성_요소가_바뀌면_지문을_다시_확인한다() {
        assertThat(components(RouteGeometry.class)).containsExactly(
            "routeId", "referenceVersionId", "name", "startPoint", "endPoint", "turnSequence", "busStops", "shape",
            "display");
        assertThat(components(RouteGeometry.Shape.class)).containsExactly(
            "id", "receivedAt", "points", "evidence", "sourceManifestId", "evidenceEvaluatedAt",
            "unboundSourceManifestId");
        assertThat(components(RouteGeometry.Stop.class)).containsExactly(
            "stationId", "stopSequence", "name", "direction", "boardingAllowed", "point");
        assertThat(components(RouteGeometry.Point.class)).containsExactly("x", "y");
        assertThat(components(RouteShapeEvidence.class)).containsExactly("selectedSource", "sources", "webPoints");
        assertThat(components(RouteShapeEvidence.Source.class)).containsExactly("id", "status", "digest", "validUntil");
        assertThat(components(RouteGeometryQueryRepository.Reference.class)).containsExactly(
            "routeId", "versionId", "name", "startPoint", "endPoint", "turnSequence");
    }

    @Test
    void 지문과_저장_자료_식별값의_구성_요소가_바뀌면_지문을_다시_확인한다() {
        assertThat(components(RouteGeometryFingerprint.class)).containsExactly(
            "routeId", "sourceRevision", "sourceManifestId", "evidenceEvaluatedAt", "unboundSourceManifestId",
            "storedEvidenceRevision", "display");
        assertThat(components(RouteDisplayFingerprint.class)).containsExactly(
            "enabled", "inputDigest", "evidenceRevision", "stored");
        assertThat(components(RouteDisplayRepository.StoredInputs.class)).containsExactly(
            "manual", "manifest", "artifacts", "generationStates", "sourceRecovery");
        assertThat(components(RouteDisplayRepository.ManualRow.class)).containsExactly(
            "id", "contentDigest", "rowVersion");
        assertThat(components(RouteDisplayRepository.ManifestRow.class)).containsExactly(
            "manifestId", "routeId", "referenceVersionId", "inputDigest", "evidenceRevision", "acquisitionManifestId",
            "publicRouteId");
        assertThat(components(RouteDisplayRepository.ArtifactRow.class)).containsExactly(
            "generationKey", "algorithmRevision", "documentSha256", "sourceManifestId", "rowVersion");
        assertThat(components(RouteDisplayRepository.GenerationState.class)).containsExactly(
            "status", "canProgress", "initialAttempt");
    }

    @Test
    void 응답_본문의_구성_요소가_바뀌면_새_값이_지문의_입력에서_오는지_확인한다() {
        assertThat(components(RouteGeometryResponse.class)).containsExactly(
            "routeId", "referenceVersionId", "geometryRevision", "name", "startPoint", "endPoint", "status", "reason",
            "coordinateSystem", "turningPoint", "busStops", "points", "paths", "boundary", "displayPaths",
            "displaySource", "displayEvidence", "displayQuality");
    }

    @Test
    void 형상과_표시_선택이_읽는_저장소_조회가_늘면_지문을_다시_확인한다() {
        assertThat(readMethods(RouteGeometryQueryRepository.class)).containsExactly("reference", "shape", "stops");
        assertThat(readMethods(RouteDisplayRepository.class)).containsExactly(
            "findAutomatic", "findGenerationStates", "findManual", "findSourceManifest", "findSourceRecovery",
            "findStoredInputs");
    }
}
