package com.gustler.backend.api.route.application;

import com.gustler.backend.api.route.InvalidReferenceVersionException;
import com.gustler.backend.api.route.RouteGeometryReferenceConflictException;
import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.route.application.RouteGeometryQueryRepository.Reference;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;

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
        if (requestedVersion != null && requestedVersion < 1) {
            throw new InvalidReferenceVersionException();
        }
        Reference reference = repository.reference(routeId.value()).orElseThrow(RouteNotFoundException::new);
        if (requestedVersion != null && requestedVersion != reference.versionId()) {
            throw new RouteGeometryReferenceConflictException();
        }
        RouteGeometry geometry =
                new RouteGeometry(
                        reference.routeId(),
                        reference.versionId(),
                        reference.name(),
                        reference.startPoint(),
                        reference.endPoint(),
                        reference.turnSequence(),
                        repository.stops(reference.versionId()),
                        repository.shape(reference.versionId()).orElse(null));
        return display.map(selector -> selector.enrich(geometry)).orElse(geometry);
    }
}
