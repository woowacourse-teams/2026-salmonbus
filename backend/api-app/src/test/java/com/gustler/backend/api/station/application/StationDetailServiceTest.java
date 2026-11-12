package com.gustler.backend.api.station.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.KnownStationArrivals;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.station.domain.StationInfo;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class StationDetailServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-13T03:00:00Z");
    private static final String STATION = "206000637";
    private static final String ROUTE = "204000057";
    private static final String OTHER_ROUTE = "204000058";

    private final StationReadRepository stations = mock(StationReadRepository.class);
    private final ArrivalQueryService arrivals = mock(ArrivalQueryService.class);
    private final StationBoardReader boards = mock(StationBoardReader.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final StationDetailService service = new StationDetailService(
        stations, arrivals, boards, Clock.fixed(NOW, ZoneOffset.UTC), meters);

    @Test
    void 도착정보_조회가_쓴_방문으로_노선_보드를_한_번에_읽고_구간_시간을_남긴다() {
        // given
        given(stations.info(STATION)).willReturn(Optional.of(
            new StationInfo(STATION, "정류장", 127.0, 37.0, "12345", "수원", "다음 정류장")));
        List<ArrivalVisit> visits = List.of(
            new ArrivalVisit(ROUTE, ROUTE, 7L, 22, "UP", NOW.minusSeconds(100)),
            new ArrivalVisit(ROUTE, ROUTE, 7L, 65, "DOWN", NOW.minusSeconds(100)),
            new ArrivalVisit(OTHER_ROUTE, OTHER_ROUTE, 8L, 3, "UP", NOW.minusSeconds(100)));
        StationArrivalsOverview overview = new StationArrivalsOverview(STATION, NOW, ArrivalAvailability.UNAVAILABLE,
            "NOT_COLLECTED", null, new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(1)),
            List.of(), Duration.ZERO);
        given(arrivals.readKnownStation(STATION)).willReturn(new KnownStationArrivals(overview, visits));
        Map<String, StationBoardReader.BoardLoad> loads = new LinkedHashMap<>();
        loads.put(ROUTE, StationBoardReader.BoardLoad.failed("PREPARING"));
        loads.put(OTHER_ROUTE, StationBoardReader.BoardLoad.failed("BOARD_UNAVAILABLE"));
        given(boards.read(List.of(ROUTE, OTHER_ROUTE))).willReturn(loads);

        // when
        StationDetailOverview detail = service.get(STATION);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(detail.routes()).extracting(StationDetailOverview.RouteView::routeId)
                .containsExactlyInAnyOrder(ROUTE, ROUTE, OTHER_ROUTE);
            softly.assertThat(detail.arrivals()).isSameAs(overview);
            for (String phase : List.of("info", "arrivals", "boards", "total")) {
                softly.assertThat(meters.timer("salmonbus.station.detail.phase", "phase", phase).count())
                    .as(phase)
                    .isEqualTo(1);
            }
            softly.assertThat(meters.summary("salmonbus.station.detail.routes").totalAmount()).isEqualTo(2.0);
        }
        then(arrivals).should(never()).getForKnownStation(anyString());
        then(boards).should().read(List.of(ROUTE, OTHER_ROUTE));
    }
}
