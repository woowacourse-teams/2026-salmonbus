package com.gustler.backend.api.board.application;

import com.gustler.backend.api.board.domain.Board;
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

/**
 * 탑승판과 지도 차량을 같은 관측 배치로 조회한다.
 * 두 결과의 차량 수 차이는 기록하고 더 짧은 유효 시간으로 캐시를 제한한다.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
@RequiredArgsConstructor
public class WatchBoardQueryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WatchBoardQueryService.class);

    private final BoardQueryService boardQueryService;
    private final VehicleQueryRepository vehicleQueryRepository;
    private final Clock clock;
    private final Map<String, Long> lastMismatchedBatchByRoute = new ConcurrentHashMap<>();

    /**
     * 승차판에 선택된 관측 묶음의 차량을 함께 조회한다. 두 결과의 관측 기준을 맞추고 만료 시각까지 캐시를 제한한다.
     */
    public WatchBoardOverview getWatchBoard(
        RouteId routeId
    ) {
        BoardReading reading = boardQueryService.readBoard(routeId);
        Board board = reading.board();
        List<ObservedVehicle> vehicles = vehicleQueryRepository.findVehicles(reading.observationBatchId());
        if (vehicles.size() != board.vehiclesInService()) {
            warnMismatchOnce(routeId, reading, vehicles.size());
        }
        return new WatchBoardOverview(board, vehicles, cacheMaxAgeOf(reading));
    }

    private Duration cacheMaxAgeOf(
        BoardReading reading
    ) {
        Board board = reading.board();
        Duration untilStale = Duration.between(OffsetDateTime.now(clock), board.staleAt());
        Duration bounded = untilStale;
        if (untilStale.isNegative()) {
            bounded = Duration.ZERO;
        }
        if (bounded.compareTo(reading.cacheMaxAge()) < 0) {
            return bounded;
        }
        return reading.cacheMaxAge();
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
        Board board = reading.board();
        LOGGER.warn(
            "기준 관측의 차량 수와 읽어낸 차량 수가 다르다. 노선={} 묶음={} 기록={} 읽음={}",
            routeId.value(),
            reading.observationBatchId(),
            board.vehiclesInService(),
            readVehicles);
    }
}
