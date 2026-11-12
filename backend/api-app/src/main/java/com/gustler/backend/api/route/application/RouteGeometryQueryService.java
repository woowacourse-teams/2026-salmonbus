package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.InvalidReferenceVersionException;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository.Reference;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 현재 노선 판본의 정류장·형상을 읽어 지도 조회 결과를 만든다.
 * 요청 판본이 다르면 충돌로 처리한다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class RouteGeometryQueryService {
    private final RouteGeometryQueryRepository repository;

    private final Optional<RouteDisplaySelector> display;

    public RouteGeometryQueryService(RouteGeometryQueryRepository repository) {
        this(repository, Optional.empty());
    }

    @Autowired
    public RouteGeometryQueryService(
            RouteGeometryQueryRepository repository, Optional<RouteDisplaySelector> display) {
        this.repository = repository;
        this.display = display;
    }

    /**
     * 현재 판본의 형상과 정류장을 조립한다. 잘못된 판본 값·노선 부재·현재 판본 불일치를 서로 다른 오류로 처리한다.
     */
    public RouteGeometry get(RouteId routeId, Long requestedVersion) {
        RouteGeometry source = source(routeId, requestedVersion);
        return display.map(selector -> selector.enrich(source)).orElse(source);
    }

    /**
     * {@link #get}과 같은 원본을 한 번 읽어 지문을 만들고, {@code responder}가 그 지문의 응답을 이미 갖고 있으면 조립 없이 그 응답을
     * 준다. 없으면 같은 원본과 같은 표시 선택 입력으로 조립해 {@code responder}가 응답을 만들게 한다. {@link #get}과 같은 순서로 같은
     * 오류를 던진다.
     *
     * <p>지문과 조립은 같은 시각에 읽은 같은 입력을 쓰므로 조립 결과는 지문이 정한다. 형상이 없거나 저장 자료 식별값을 읽지 못하면 지문
     * 없이 조립한다. 조립에 쓰는 조회는 지문을 만들 때 읽은 값으로 대신하므로 {@link #get}보다 늘지 않는다.
     */
    public <T> T respond(RouteId routeId, Long requestedVersion, RouteGeometryResponder<T> responder) {
        RouteGeometry source = source(routeId, requestedVersion);
        Optional<RouteDisplaySelector.Prepared> prepared = display.map(selector -> selector.prepare(source));
        Optional<RouteGeometryFingerprint> fingerprint = fingerprintOf(source, prepared);
        if (fingerprint.isPresent()) {
            Optional<T> reused = responder.reuse(fingerprint.get());
            if (reused.isPresent()) {
                return reused.get();
            }
        }
        RouteGeometry geometry = prepared
                .map(selection -> display.orElseThrow().enrich(selection))
                .orElse(source);
        return responder.create(geometry, fingerprint.orElse(null));
    }

    private RouteGeometry source(RouteId routeId, Long requestedVersion) {
        if (requestedVersion != null && requestedVersion < 1) {
            throw new InvalidReferenceVersionException();
        }
        Reference reference = repository.reference(routeId.value()).orElseThrow(RouteNotFoundException::new);
        if (requestedVersion != null && requestedVersion != reference.versionId()) {
            throw new RouteGeometryReferenceConflictException();
        }
        return new RouteGeometry(
                reference.routeId(),
                reference.versionId(),
                reference.name(),
                reference.startPoint(),
                reference.endPoint(),
                reference.turnSequence(),
                repository.stops(reference.versionId()),
                repository.shape(reference.versionId()).orElse(null));
    }

    private static Optional<RouteGeometryFingerprint> fingerprintOf(
            RouteGeometry source, Optional<RouteDisplaySelector.Prepared> prepared) {
        Shape shape = source.shape();
        if (shape == null) {
            return Optional.empty();
        }
        Optional<RouteDisplayFingerprint> selected = prepared
                .map(RouteDisplaySelector.Prepared::fingerprint)
                .orElseGet(() -> Optional.of(RouteDisplayFingerprint.disabled()));
        return selected.map(value -> new RouteGeometryFingerprint(
                source.routeId(),
                source.revision(),
                shape.sourceManifestId(),
                shape.evidenceEvaluatedAt(),
                shape.unboundSourceManifestId(),
                shape.evidence().revision(),
                value));
    }
}
