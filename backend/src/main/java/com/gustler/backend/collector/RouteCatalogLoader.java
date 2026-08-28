package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRouteResult.Failed;
import com.gustler.backend.collector.GbisRouteResult.Success;
import java.time.OffsetDateTime;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 수집을 돌리려면 노선 판본이 먼저 있어야 한다. observation_batch.route_version_id 가 NOT NULL 이라
 * 판본 없이는 판을 열 수가 없다.
 *
 * <p>판본이 없을 때만 상류에서 받아 연다. 이미 있으면 아무것도 안 한다.
 * 그래서 노선이 개편돼도 앱을 다시 띄우는 것만으로는 새 판본이 안 열린다.
 * 개편을 알아보려면 주기적으로 노선정보를 다시 받아 RouteVersionLoader 에 넘겨야 하고, 그것은 아직 없다.
 */
@Component
public class RouteCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(RouteCatalogLoader.class);

    private final CurrentRouteVersionRepository currentRouteVersionRepository;
    private final CallQuotaLedger callQuotaLedger;
    private final GbisRouteSource routeSource;
    private final RouteRegistry routeRegistry;
    private final RouteVersionLoader routeVersionLoader;

    public RouteCatalogLoader(
        CurrentRouteVersionRepository currentRouteVersionRepository,
        CallQuotaLedger callQuotaLedger,
        GbisRouteSource routeSource,
        RouteRegistry routeRegistry,
        RouteVersionLoader routeVersionLoader
    ) {
        this.currentRouteVersionRepository = currentRouteVersionRepository;
        this.callQuotaLedger = callQuotaLedger;
        this.routeSource = routeSource;
        this.routeRegistry = routeRegistry;
        this.routeVersionLoader = routeVersionLoader;
    }

    /** 지금 쓰는 판본을 준다. 없으면 상류에서 받아 연다. 못 열면 비어 있다. */
    public OptionalLong currentVersionOf(
        String upstreamRouteId,
        OffsetDateTime readAt
    ) {
        OptionalLong opened = currentRouteVersionRepository.findIdOf(upstreamRouteId);
        if (opened.isPresent()) {
            return opened;
        }
        return openFromUpstream(upstreamRouteId, readAt);
    }

    private OptionalLong openFromUpstream(
        String upstreamRouteId,
        OffsetDateTime readAt
    ) {
        if (!callQuotaLedger.reserve(CallQuota.BUS_ROUTE, readAt, GbisRouteSource.UPSTREAM_CALLS_PER_READ)) {
            log.warn("하루 호출 한도가 남지 않아 노선정보를 못 받았다. 이 노선은 수집을 못 한다. 노선={}",
                upstreamRouteId);
            return OptionalLong.empty();
        }

        return switch (routeSource.read(upstreamRouteId)) {
            case Failed failed -> {
                log.warn("노선정보를 읽지 못해 판본을 못 열었다. 노선={} 사유={}", upstreamRouteId, failed.reason());
                yield OptionalLong.empty();
            }
            case Success success -> OptionalLong.of(open(success.route(), readAt));
        };
    }

    @Transactional
    public long open(
        UpstreamRoute route,
        OffsetDateTime readAt
    ) {
        return routeVersionLoader.load(
            routeRegistry.register(route), route.stops(), route.timetable(), readAt);
    }
}
