package com.gustler.backend.routecatalog.application;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.CurrentRouteVersion;
import com.gustler.backend.routecatalog.api.RouteReference;
import com.gustler.backend.routecatalog.domain.CurrentRouteVersionRepository;
import com.gustler.backend.routecatalog.domain.RouteRegistry;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import com.gustler.backend.routecatalog.domain.RouteSource;

import com.gustler.backend.routecatalog.domain.RouteSourceResult.Failed;
import com.gustler.backend.routecatalog.domain.RouteSourceResult.Success;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 수집을 시작하려면 노선 버전이 먼저 있어야 한다. observation_batch.route_version_id가 NOT NULL이므로
 * 노선 버전 없이는 수집 배치를 생성할 수 없다.
 *
 * <p>버전이 없을 때만 상류에서 노선정보를 받아 생성한다. 이미 있으면 아무것도 안 한다.
 * 노선이 개편돼도 앱을 다시 시작하는 것만으로는 새 버전이 생성되지 않는다.
 * 개편을 반영하려면 노선정보를 주기적으로 다시 받아 RouteVersionLoader에 넘겨야 하지만 아직 구현하지 않았다.
 */
@Component
public class RouteCatalogLoader implements CurrentRouteVersion {

    private static final Logger log = LoggerFactory.getLogger(RouteCatalogLoader.class);

    private final CurrentRouteVersionRepository currentRouteVersionRepository;
    private final ApiCallQuota apiCallQuota;
    private final RouteSource routeSource;
    private final RouteRegistry routeRegistry;
    private final RouteVersionLoader routeVersionLoader;
    private final TransactionTemplate transactionTemplate;

    public RouteCatalogLoader(
        CurrentRouteVersionRepository currentRouteVersionRepository,
        ApiCallQuota apiCallQuota,
        RouteSource routeSource,
        RouteRegistry routeRegistry,
        RouteVersionLoader routeVersionLoader,
        TransactionTemplate transactionTemplate
    ) {
        this.currentRouteVersionRepository = currentRouteVersionRepository;
        this.apiCallQuota = apiCallQuota;
        this.routeSource = routeSource;
        this.routeRegistry = routeRegistry;
        this.routeVersionLoader = routeVersionLoader;
        this.transactionTemplate = transactionTemplate;
    }

    /** 현재 노선 버전을 반환한다. 없으면 상류에서 받아 생성하고, 생성하지 못하면 빈 결과를 반환한다. */
    @Override
    public Optional<RouteReference> currentVersionOf(
        String sourceRouteId,
        OffsetDateTime readAt
    ) {
        OptionalLong opened = currentRouteVersionRepository.findIdOf(sourceRouteId);
        if (opened.isPresent()) {
            return Optional.of(new RouteReference(opened.getAsLong()));
        }
        OptionalLong created = openFromUpstream(sourceRouteId, readAt);
        return created.isPresent() ? Optional.of(new RouteReference(created.getAsLong())) : Optional.empty();
    }

    private OptionalLong openFromUpstream(
        String sourceRouteId,
        OffsetDateTime readAt
    ) {
        if (!apiCallQuota.reserveRouteCatalog(readAt, routeSource.requiredCallsPerRead())) {
            log.warn("하루 호출 한도가 남지 않아 노선정보를 못 받았다. 이 노선은 수집을 못 한다. 노선={}",
                sourceRouteId);
            return OptionalLong.empty();
        }

        // 호출 두 번을 먼저 예약한다. 노선정보 조회가 실패해 정류소를 조회하지 않아도 예약은 유지한다.
        // 예약을 취소하려 해도 이미 전송한 호출에 해당하는지 여기서는 확인할 수 없다.
        // 노선 버전이 없을 때만 실행하므로 자주 발생하지 않는다.

        return switch (routeSource.read(sourceRouteId)) {
            case Failed failed -> {
                log.warn("노선정보를 읽지 못해 판본을 못 열었다. 노선={} 사유={}", sourceRouteId, failed.reason());
                yield OptionalLong.empty();
            }
            case Success success -> OptionalLong.of(open(success.route(), readAt));
        };
    }

    /**
     * 노선 행 확보와 버전 생성을 한 트랜잭션에서 처리한다.
     *
     * <p>@Transactional을 사용하지 않는다. 같은 객체의 openFromUpstream에서만 호출하므로
     * 스프링 프록시를 거치지 않아 애노테이션이 적용되지 않는다.
     * 기존 코드의 이 문제는 동키가 리뷰에서 발견했다.
     *
     * <p>currentVersionOf에 애노테이션을 붙이면 상류 HTTP 호출까지 트랜잭션에 포함되어
     * 응답을 기다리는 동안 커넥션을 점유한다. 이를 피하려고 TransactionTemplate으로
     * 필요한 코드 블록에만 트랜잭션을 적용한다.
     */
    private long open(
        UpstreamRoute route,
        OffsetDateTime readAt
    ) {
        return transactionTemplate.execute(status -> routeVersionLoader.load(
            routeRegistry.register(route), route.stops(), route.timetable(), readAt));
    }
}
