package com.gustler.backend.collector;

import com.gustler.backend.collector.GbisRouteResult.Failed;
import com.gustler.backend.collector.GbisRouteResult.Success;
import java.time.OffsetDateTime;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    public RouteCatalogLoader(
        CurrentRouteVersionRepository currentRouteVersionRepository,
        CallQuotaLedger callQuotaLedger,
        GbisRouteSource routeSource,
        RouteRegistry routeRegistry,
        RouteVersionLoader routeVersionLoader,
        TransactionTemplate transactionTemplate
    ) {
        this.currentRouteVersionRepository = currentRouteVersionRepository;
        this.callQuotaLedger = callQuotaLedger;
        this.routeSource = routeSource;
        this.routeRegistry = routeRegistry;
        this.routeVersionLoader = routeVersionLoader;
        this.transactionTemplate = transactionTemplate;
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

        // 두 자리를 먼저 잡는다. 노선정보에서 실패해 정류소를 안 부르면 한 자리가 안 쓰인 채 닳는데
        // 그대로 둔다. 안 썼다고 돌려주면 그 자리가 이미 나간 호출의 것인지 아닌지를 여기서 알 수 없다.
        // 판본이 없을 때만 지나는 길이라 자주 생기지도 않는다.

        return switch (routeSource.read(upstreamRouteId)) {
            case Failed failed -> {
                log.warn("노선정보를 읽지 못해 판본을 못 열었다. 노선={} 사유={}", upstreamRouteId, failed.reason());
                yield OptionalLong.empty();
            }
            case Success success -> OptionalLong.of(open(success.route(), readAt));
        };
    }

    /**
     * 노선 행 확보와 판본 열기를 한 트랜잭션에 묶는다.
     *
     * <p>@Transactional 을 안 쓴다. 이 메서드를 부르는 데가 같은 객체 안의 openFromUpstream 뿐이라
     * 자기 호출이 되고, 그러면 스프링이 감싼 대리 객체를 안 거쳐서 애노테이션이 아무 일도 안 한다.
     * 실제로 그렇게 두고 있었고 동키가 리뷰에서 잡아줬다.
     *
     * <p>currentVersionOf 에 애노테이션을 올리는 길도 있는데, 그러면 상류 HTTP 호출이
     * 트랜잭션 안에 들어간다. 응답을 기다리는 내내 커넥션을 쥐고 있게 되므로 그쪽은 접었다.
     * 경계가 메서드가 아니라 코드 블록이라 TransactionTemplate 으로 그 자리에 직접 적는다.
     */
    private long open(
        UpstreamRoute route,
        OffsetDateTime readAt
    ) {
        return transactionTemplate.execute(status -> routeVersionLoader.load(
            routeRegistry.register(route), route.stops(), route.timetable(), readAt));
    }
}
