package com.gustler.backend.api.board.application;

import com.gustler.backend.api.route.RouteId;
import com.gustler.backend.api.vehicle.application.VehicleQueryRepository;
import com.gustler.backend.api.vehicle.domain.ObservedVehicle;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class WatchBoardQueryService {

    private static final Logger log = LoggerFactory.getLogger(WatchBoardQueryService.class);

    private final BoardQueryService boardQueryService;
    private final VehicleQueryRepository vehicleQueryRepository;
    private final Clock clock;
    private final Map<String, Long> lastMismatchedBatchByRoute = new ConcurrentHashMap<>();

    public WatchBoardOverview getWatchBoard(
        RouteId routeId
    ) {
        BoardReading reading = boardQueryService.readBoard(routeId);
        List<ObservedVehicle> vehicles = vehicleQueryRepository.findVehicles(reading.observationBatchId());
        if (vehicles.size() != reading.board().vehiclesInService()) {
            warnMismatchOnce(routeId, reading, vehicles.size());
        }
        return new WatchBoardOverview(reading.board(), vehicles, cacheMaxAgeOf(reading));
    }

    private Duration cacheMaxAgeOf(
        BoardReading reading
    ) {
        Duration untilStale = Duration.between(OffsetDateTime.now(clock), reading.board().staleAt());
        Duration bounded = untilStale.isNegative() ? Duration.ZERO : untilStale;
        return bounded.compareTo(reading.cacheMaxAge()) < 0 ? bounded : reading.cacheMaxAge();
    }

    private void warnMismatchOnce(
        RouteId routeId,
        BoardReading reading,
        final int readVehicles
    ) {
        Long previous = lastMismatchedBatchByRoute.put(routeId.value(), reading.observationBatchId());
        if (previous != null && previous == reading.observationBatchId()) {
            return;
        }
        log.warn(
            "기준 관측의 차량 수와 읽어낸 차량 수가 다르다. 노선={} 묶음={} 기록={} 읽음={}",
            routeId.value(),
            reading.observationBatchId(),
            reading.board().vehiclesInService(),
            readVehicles);
    }
}
