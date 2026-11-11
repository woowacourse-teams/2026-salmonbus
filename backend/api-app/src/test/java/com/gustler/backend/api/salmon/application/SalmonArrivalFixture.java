package com.gustler.backend.api.salmon.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.BoardDirection;
import com.gustler.backend.api.board.domain.BoardRoute;
import com.gustler.backend.api.board.domain.DirectionInfo;
import com.gustler.backend.api.board.domain.ForecastModel;
import com.gustler.backend.api.board.domain.RouteHeadways;
import com.gustler.backend.api.board.domain.StopState;
import com.gustler.backend.api.route.domain.RouteStatus;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 노선 204000057이 순번 1부터 5까지의 정류장을 판본 31로 지나는 전광판과 도착 스냅샷을 만든다.
 * 판정을 묻는 정류장은 순번 5의 205000231이고 마감은 20분이다.
 */
final class SalmonArrivalFixture {
    static final String ROUTE = "204000057";
    static final String STATION = "205000231";
    static final int STOP_SEQUENCE = 5;
    static final String VERSION = "31";

    private static final String SOURCE_ROUTE = "224000057";

    private final Instant receivedAt;
    private final Instant staleAt;
    private final Map<String, ArrivalSnapshot> stored = new HashMap<>();
    private final ArrivalQueryService stations = mock(ArrivalQueryService.class);

    private SalmonArrivalFixture(Instant receivedAt, Instant staleAt) {
        this.receivedAt = receivedAt;
        this.staleAt = staleAt;
        given(stations.getForKnownStation(anyString())).willAnswer(call -> overview(call.getArgument(0), null));
    }

    static SalmonArrivalFixture received(Instant receivedAt, Instant staleAt) {
        return new SalmonArrivalFixture(receivedAt, staleAt);
    }

    static SalmonDueArrivals arrivals(Instant receivedAt, Instant staleAt, Prediction... predictions) {
        return received(receivedAt, staleAt).stored(STOP_SEQUENCE, predictions).reader();
    }

    static SalmonDueArrivals noSnapshot(Instant now) {
        return received(now, now.plusSeconds(600)).reader();
    }

    /** 이미 저장돼 있는 그 순번 정류장의 스냅샷. */
    SalmonArrivalFixture stored(int stopSequence, Prediction... predictions) {
        stored.put(stationOf(stopSequence), snapshot(stopSequence, predictions));
        return this;
    }

    /** 도착 조회 서비스에 물어야 받는 그 순번 정류장의 스냅샷. */
    SalmonArrivalFixture collectable(int stopSequence, Prediction... predictions) {
        String stationId = stationOf(stopSequence);
        given(stations.getForKnownStation(stationId))
            .willReturn(overview(stationId, snapshot(stopSequence, predictions)));
        return this;
    }

    ArrivalQueryService stations() {
        return stations;
    }

    SalmonDueArrivals reader() {
        ArrivalReadRepository repository = new ArrivalReadRepository() {
            @Override
            public List<ArrivalVisit> visits(String stationId) {
                return List.of(new ArrivalVisit(ROUTE, SOURCE_ROUTE, Long.parseLong(VERSION), sequenceOf(stationId),
                    "UP", receivedAt.minusSeconds(3600)));
            }

            @Override
            public Optional<ArrivalSnapshot> latest(String stationId) {
                return Optional.ofNullable(stored.get(stationId));
            }
        };
        return new SalmonDueArrivals(repository, stations, SalmonSettingsFixture.adopted());
    }

    static Board board(Instant observedAt, Instant staleAt, ApproachingVehicle... approaching) {
        return boardOfVersion(VERSION, observedAt, staleAt, approaching);
    }

    static Board boardOfVersion(String version, Instant observedAt, Instant staleAt,
                                ApproachingVehicle... approaching) {
        BoardRoute route = new BoardRoute(ROUTE, "9300", "기점", "종점", RouteStatus.FORECAST_READY, null,
            List.of(new DirectionInfo(BoardDirection.UP, "종점 방면", "기점", "종점", "05:00", "22:30")),
            version, RouteHeadways.none());
        List<StopState> stops = new ArrayList<>();
        for (int sequence = 1; sequence < STOP_SEQUENCE; sequence++) {
            stops.add(new StopState(sequence, stationOf(sequence), "정류장 " + sequence, BoardDirection.UP, true,
                List.of()));
        }
        stops.add(new StopState(STOP_SEQUENCE, STATION, "정류장", BoardDirection.UP, true, List.of(approaching)));
        return new Board(route, observedAt.atOffset(ZoneOffset.UTC), staleAt.atOffset(ZoneOffset.UTC),
            new ForecastModel(1, "release", observedAt.atOffset(ZoneOffset.UTC).minusDays(1)), 1, stops);
    }

    static String stationOf(int stopSequence) {
        return stopSequence == STOP_SEQUENCE ? STATION : "20500010" + stopSequence;
    }

    static Prediction inSeconds(String vehicleId, int ordinal, int seconds) {
        return new Prediction(vehicleId, ordinal, seconds, null, 3, 10, null, null, null, null);
    }

    static Prediction inMinutes(String vehicleId, int ordinal, int minutes) {
        return new Prediction(vehicleId, ordinal, null, minutes, 3, 10, null, null, null, null);
    }

    private static int sequenceOf(String stationId) {
        return STATION.equals(stationId) ? STOP_SEQUENCE : Integer.parseInt(stationId.substring(8));
    }

    private ArrivalSnapshot snapshot(int stopSequence, Prediction... predictions) {
        RouteArrival route = new RouteArrival(SOURCE_ROUTE, "9300", stopSequence, null, null, null, null,
            List.of(predictions));
        return new ArrivalSnapshot(UUID.nameUUIDFromBytes(new byte[] {(byte) stopSequence}), stationOf(stopSequence),
            receivedAt, receivedAt.plusSeconds(60), staleAt, "query", List.of(route));
    }

    private static StationArrivalsOverview overview(String stationId, ArrivalSnapshot snapshot) {
        return new StationArrivalsOverview(stationId, Instant.EPOCH,
            snapshot == null ? ArrivalAvailability.UNAVAILABLE : ArrivalAvailability.READY, null, snapshot, null,
            List.of(), Duration.ZERO);
    }
}
