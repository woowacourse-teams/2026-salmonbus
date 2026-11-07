package com.gustler.backend.api.station.application;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.arrival.domain.RouteArrivals;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.station.StationNotFoundException;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 정류장 하나로 오는 모든 버스를 모은다.
 *
 * <p>우리 노선 행은 방문마다 판정보드를 기준으로 만들고 GBIS 도착정보의 ETA·현재 좌석을 덧붙인다({@link SalmonRouteRows}).
 * 그래서 GBIS 수신이 막혀도 우리 노선 차량과 예보는 나온다. 다른 노선 행은 GBIS 도착정보를 그대로 쓴다.
 * 판정보드 읽기는 {@link StationBoardReader}가 맡는다.
 */
@Service
public class StationDetailService {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final StationReadRepository stations;
    private final ArrivalQueryService arrivals;
    private final StationBoardReader boards;
    private final Clock clock;

    StationDetailService(
        StationReadRepository stations,
        ArrivalQueryService arrivals,
        StationBoardReader boards,
        Clock clock
    ) {
        this.stations = stations;
        this.arrivals = arrivals;
        this.boards = boards;
        this.clock = clock;
    }

    public StationDetailOverview get(String stationId) {
        if (stationId == null || !stationId.matches("[0-9]{9}")) {
            throw new ArrivalQuery.Failure(ErrorCode.INVALID_REQUEST);
        }
        StationInfo info = stations.info(stationId).orElseThrow(StationNotFoundException::new);
        StationArrivalsOverview overview = arrivals.getForKnownStation(stationId);
        List<ArrivalVisit> visits = arrivals.visits(stationId);
        Map<String, StationBoardReader.BoardLoad> loaded = boards.read(visits.stream()
            .map(ArrivalVisit::routeId)
            .toList());
        Map<String, ArrivalSnapshot.RouteArrival> sources = sourcesOf(overview);
        final boolean received = overview.snapshot() != null;
        LocalTime now = LocalTime.now(clock.withZone(KOREA));
        List<StationDetailOverview.RouteView> routes = new ArrayList<>();
        Set<RouteArrivals> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ArrivalVisit visit : visits) {
            RouteArrivals gbis = overview.routes()
                .stream()
                .filter(route -> SalmonRouteRows.sameVisit(route, visit))
                .findFirst()
                .orElse(null);
            if (gbis != null) {
                consumed.add(gbis);
            }
            StationBoardReader.BoardLoad load = loaded.get(visit.routeId());
            ArrivalSnapshot.RouteArrival source = sourceOf(gbis, sources);
            routes.add(SalmonRouteRows.of(visit, load.board(), load.failure(), gbis, received, source, now));
        }
        Set<String> ourRoutes = new HashSet<>();
        visits.forEach(visit -> ourRoutes.add(visit.routeId()));
        for (RouteArrivals route : overview.routes()) {
            if (consumed.contains(route)) {
                continue;
            }
            ArrivalSnapshot.RouteArrival source = sources.get(route.routeId() + "/" + route.sourceStopOrder());
            if (ourRoutes.contains(route.routeId())) {
                routes.add(SalmonRouteRows.unverified(route, source));
                continue;
            }
            routes.add(otherRoute(route, source));
        }
        routes.sort(
            Comparator.comparing((StationDetailOverview.RouteView view) -> !view.supported())
            .thenComparing(
                StationDetailService::firstRemaining,
                Comparator.nullsLast(Comparator.naturalOrder())
            )
        );
        return new StationDetailOverview(info, overview, routes, overview.cacheMaxAge());
    }

    private static ArrivalSnapshot.RouteArrival sourceOf(
        RouteArrivals route,
        Map<String, ArrivalSnapshot.RouteArrival> sources
    ) {
        if (route == null) {
            return null;
        }
        return sources.get(route.routeId() + "/" + route.sourceStopOrder());
    }

    private static StationDetailOverview.RouteView otherRoute(
        RouteArrivals route,
        ArrivalSnapshot.RouteArrival source
    ) {
        List<StationDetailOverview.VehicleView> vehicles = route.arrivals()
            .stream()
            .map(prediction -> new StationDetailOverview.VehicleView(
                prediction.source().vehicleId(),
                prediction.source().remainingStops(),
                StationDetailOverview.PositionSource.ARRIVAL,
                prediction,
                null,
                null
            ))
            .toList();
        return new StationDetailOverview.RouteView(
            route.routeId(),
            route.name(),
            Optional.ofNullable(source).map(ArrivalSnapshot.RouteArrival::destinationName).orElse(null),
            Optional.ofNullable(source).map(ArrivalSnapshot.RouteArrival::routeType).orElse(null),
            false,
            null,
            route.status().name(),
            route.reason(),
            vehicles
        );
    }

    private static Map<String, ArrivalSnapshot.RouteArrival> sourcesOf(StationArrivalsOverview overview) {
        Map<String, ArrivalSnapshot.RouteArrival> sources = new HashMap<>();
        if (overview.snapshot() == null) {
            return sources;
        }
        for (ArrivalSnapshot.RouteArrival route : overview.snapshot().routes()) {
            sources.put(route.routeId() + "/" + route.sourceStopOrder(), route);
        }
        return sources;
    }

    private static Long firstRemaining(StationDetailOverview.RouteView view) {
        return view.arrivals()
            .stream()
            .map(SalmonRouteRows::remainingSecondsOf)
            .filter(Objects::nonNull)
            .findFirst()
            .orElse(null);
    }
}
