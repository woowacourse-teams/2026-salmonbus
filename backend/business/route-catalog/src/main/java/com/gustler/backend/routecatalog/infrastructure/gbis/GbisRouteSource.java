package com.gustler.backend.routecatalog.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisRouteInfoSource;
import com.gustler.backend.gbis.api.GbisRouteResult;
import com.gustler.backend.routecatalog.domain.RouteStops;
import com.gustler.backend.routecatalog.domain.RouteSource;
import com.gustler.backend.routecatalog.domain.RouteSourceResult;
import com.gustler.backend.routecatalog.domain.RouteTimetable;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import com.gustler.backend.routecatalog.domain.UpstreamRouteStop;

import com.gustler.backend.routecatalog.domain.RouteSourceResult.Failed;
import com.gustler.backend.routecatalog.domain.RouteSourceResult.Success;
import com.gustler.backend.gbis.api.dto.BusRouteInfoResponse.RouteInfoItem;
import com.gustler.backend.gbis.api.dto.BusRouteStationResponse.RouteStationItem;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 상류가 준 노선 값을 카탈로그가 쓰는 업무 값으로 바꾼다.
 *
 * <p>통신과 응답 해석은 gbis-client 의 {@link GbisRouteInfoSource} 가 맡는다. 여기서는 회차 순번을
 * 고르고 정류소 목록과 시간표를 만든다. 성립하지 않는 응답은 판본을 열지 않고 사유를 남긴다.
 */
@Component
public class GbisRouteSource implements RouteSource {

    private static final Logger log = LoggerFactory.getLogger(GbisRouteSource.class);

    private final GbisRouteInfoSource routes;

    public GbisRouteSource(
        GbisRouteInfoSource routes
    ) {
        this.routes = routes;
    }

    @Override
    public int requiredCallsPerRead() {
        return GbisRouteInfoSource.UPSTREAM_CALLS_PER_READ;
    }

    @Override
    public RouteSourceResult read(
        final String routeId
    ) {
        return switch (routes.read(routeId)) {
            case GbisRouteResult.RouteInfoUnavailable ignored ->
                new Failed("노선 %s 의 기본 정보를 읽지 못했다".formatted(routeId));
            case GbisRouteResult.StationsUnavailable ignored ->
                new Failed("노선 %s 의 경유 정류소를 읽지 못했다".formatted(routeId));
            case GbisRouteResult.Success read when read.stations().isEmpty() ->
                new Failed("노선 %s 의 경유 정류소를 읽지 못했다".formatted(routeId));
            case GbisRouteResult.Success read -> toSuccess(routeId, read.routeInfo(), read.stations());
        };
    }

    private RouteSourceResult toSuccess(
        String routeId,
        RouteInfoItem routeInfo,
        List<RouteStationItem> stations
    ) {
        try {
            return new Success(new UpstreamRoute(
                routeId,
                routeInfo.displayName(),
                routeInfo.startStopName(),
                routeInfo.endStopName(),
                RouteStops.from(turnSequenceOf(stations), toUpstreamStops(stations)),
                timetableOf(routeInfo)));
        } catch (final IllegalArgumentException e) {
            // 순번이 겹치거나 회차 순번이 정류소 목록에 없는 응답. 판본으로 열면 뜻이 없는 노선이 된다.
            return new Failed("노선 %s 의 정류소 목록이 성립하지 않는다: %s".formatted(routeId, e.getMessage()));
        }
    }

    /**
     * 회차 순번은 정류소 목록이 준다. 노선정보 응답에는 없다.
     *
     * <p>정상 응답은 <b>모든 정류소 행에 같은 {@code turnSeq}</b> 가 실리고
     * <b>회차하는 정류소 하나만 {@code turnYn} 이 Y</b> 다. 셋 중 하나라도 어긋나면 회차 없음으로
     * 본다. 잘못 고른 순번으로 판본을 열면 정류소 절반의 방향이 뒤집힌 채 그럴듯하게 돌아간다.
     *
     * <ul>
     *   <li>값이 빠진 행이 하나라도 있으면 안 쓴다. 남은 행만으로 고르면 그 노선이 정말 회차하는지
     *       알 수 없다
     *   <li>서로 다른 값이 오면 안 쓴다
     *   <li>회차 표시가 없거나 둘 이상이면 안 쓴다
     *   <li>표시가 붙은 정류소의 순번이 {@code turnSeq} 와 다르면 안 쓴다
     * </ul>
     */
    private Integer turnSequenceOf(
        List<RouteStationItem> stations
    ) {
        if (stations.isEmpty()) {
            return null;
        }
        Set<Integer> declared = stations.stream()
            .map(RouteStationItem::turnSequence)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);
        if (declared.size() != 1 || declared.contains(null)) {
            return warnAndIgnore("회차 순번이 행마다 다르거나 빠진 행이 있다: {}", declared);
        }

        Integer turnSequence = declared.iterator().next();
        List<Integer> marked = stations.stream()
            .filter(RouteStationItem::isTurnPoint)
            .map(RouteStationItem::stopOrder)
            .toList();
        if (marked.size() != 1) {
            return warnAndIgnore("회차 표시가 붙은 정류소가 {}개다. 하나여야 한다", marked.size());
        }
        if (!marked.getFirst().equals(turnSequence)) {
            return warnAndIgnore("회차 순번 {} 과 회차 표시가 붙은 순번이 다르다",
                turnSequence + " / " + marked.getFirst());
        }
        return turnSequence;
    }

    private Integer warnAndIgnore(
        String message,
        Object detail
    ) {
        log.warn("회차 메타데이터가 성립하지 않아 단방향 노선으로 읽는다. " + message, detail);

        return null;
    }

    private List<UpstreamRouteStop> toUpstreamStops(
        List<RouteStationItem> stations
    ) {
        return stations.stream()
            .map(station -> new UpstreamRouteStop(station.stopOrder(), station.stopId(), station.name()))
            .toList();
    }

    private RouteTimetable timetableOf(
        RouteInfoItem routeInfo
    ) {
        return new RouteTimetable(
            routeInfo.upFirstDepartureTime(),
            routeInfo.upLastDepartureTime(),
            routeInfo.downFirstDepartureTime(),
            routeInfo.downLastDepartureTime());
    }
}
