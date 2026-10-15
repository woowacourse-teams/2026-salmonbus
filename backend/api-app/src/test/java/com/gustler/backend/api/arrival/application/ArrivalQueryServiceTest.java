package com.gustler.backend.api.arrival.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArrivalQueryServiceTest {
    private static final Instant RECEIVED = Instant.parse("2026-10-14T03:00:00Z");
    private static final String STATION = "206000637";
    private static final String ROUTE = "204000057";
    private final ArrivalReadRepository repository = mock(ArrivalReadRepository.class);
    private final ArrivalRefreshGateway gateway = mock(ArrivalRefreshGateway.class);
    private ArrivalQueryService service;

    @BeforeEach
    void setUp() {
        service = serviceAt(10);
        when(repository.visits(STATION)).thenReturn(List.of(
            new ArrivalVisit(ROUTE, ROUTE, 7L, 22, "UP", RECEIVED.minusSeconds(100)),
            new ArrivalVisit(ROUTE, ROUTE, 7L, 65, "DOWN", RECEIVED.minusSeconds(100))));
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(List.of(route(ROUTE, 22), route("204000155", 1)))));
        when(gateway.request(STATION)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.ACCEPTED,
            "COLLECTING", RECEIVED.plusSeconds(50)));
    }

    private ArrivalQueryService serviceAt(int seconds) {
        return new ArrivalQueryService(repository, gateway, new ArrivalFreshnessPolicy(), Clock.fixed(RECEIVED.plusSeconds(seconds), ZoneOffset.UTC));
    }

    private ArrivalSnapshot snapshot(List<RouteArrival> routes) {
        return new ArrivalSnapshot(UUID.randomUUID(), STATION, RECEIVED, RECEIVED.plusSeconds(40),
            RECEIVED.plusSeconds(60), "source query time", routes);
    }

    private RouteArrival route(String route, int order) {
        return new RouteArrival(route, "bus", order, 12, null, null, "RUN", List.of(
            new Prediction("vehicle-1", 1, 180, 3, 3, 0, null, null, 0, false)));
    }

    @Test
    void 공유_수신결과에서_선택한_노선과_방문만_반환한다() {
        var result = service.get(new ArrivalQuery(STATION, ROUTE, 7L, 22));
        assertThat(result.status()).isEqualTo(ArrivalAvailability.READY);
        assertThat(result.routes()).hasSize(1);
        var route = result.routes().getFirst();
        assertThat(route.targetVisit().referenceVersionId()).isEqualTo(7L);
        assertThat(route.targetVisit().stopSequence()).isEqualTo(22);
        assertThat(route.arrivals().getFirst().remainingSeconds()).isEqualTo(170);
        assertThat(route.arrivals().getFirst().estimateBaseAt()).isEqualTo(RECEIVED);
        assertThat(route.arrivals().getFirst().source().remainingSeats()).isZero();
        verifyNoInteractions(gateway);
    }

    @Test
    void 전체조회는_미등록_노선도_보존하되_판본을_만들어주지_않는다() {
        var result = service.get(new ArrivalQuery(STATION, null, null, null));
        assertThat(result.routes()).hasSize(2);
        assertThat(result.routes().get(1).targetVisit()).isNull();
    }

    @Test
    void 같은_정류장의_다른_방문을_도착정보_없음으로_단정하지_않는다() {
        var result = service.get(new ArrivalQuery(STATION, ROUTE, 7L, 65));
        assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("NO_VERIFIED_ROUTE_MATCH");
    }

    @Test
    void 오래된_판본은_갱신요청_전에_충돌로_처리한다() {
        assertThatThrownBy(() -> service.get(new ArrivalQuery(STATION, ROUTE, 6L, 22)))
            .isInstanceOfSatisfying(ArrivalQuery.Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(ErrorCode.REFERENCE_VERSION_CONFLICT));
        verifyNoInteractions(gateway);
        verify(repository, never()).latest(anyString());
    }

    @Test
    void 갱신시점이_지난_유효값은_반환하고_갱신만_요청한다() {
        var result = serviceAt(45).get(new ArrivalQuery(STATION, ROUTE, 7L, 22));
        assertThat(result.status()).isEqualTo(ArrivalAvailability.READY);
        assertThat(result.refresh().status()).isEqualTo(ArrivalRefresh.Status.ACCEPTED);
        assertThat(result.snapshot().receivedAt()).isEqualTo(RECEIVED);
        assertThat(result.snapshot().staleAt()).isEqualTo(RECEIVED.plusSeconds(60));
        verify(gateway).request(STATION);
    }

    @Test
    void 만료된_값은_갱신실패해도_최신인_것처럼_반환하지_않는다() {
        when(gateway.request(STATION)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.UNAVAILABLE,
            "WORKER_UNAVAILABLE", RECEIVED.plusSeconds(63)));
        var result = serviceAt(61).get(new ArrivalQuery(STATION, ROUTE, 7L, 22));
        assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("STALE");
        assertThat(result.routes()).isEmpty();
        assertThat(result.snapshot().staleAt()).isEqualTo(RECEIVED.plusSeconds(60));
    }

    @Test
    void 정상_빈_수신과_최초_수집중을_구별한다() {
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(List.of())));
        assertThat(service.get(new ArrivalQuery(STATION, null, null, null)).status()).isEqualTo(ArrivalAvailability.NO_PREDICTION);
        when(repository.latest(STATION)).thenReturn(Optional.empty());
        var pending = service.get(new ArrivalQuery(STATION, null, null, null));
        assertThat(pending.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
        assertThat(pending.refresh().status()).isEqualTo(ArrivalRefresh.Status.ACCEPTED);
    }

    @Test
    void 수신_이후에_생긴_판본으로_기존_ETA를_연결하지_않는다() {
        when(repository.visits(STATION)).thenReturn(List.of(new ArrivalVisit(
            ROUTE, ROUTE, 8, 22, "UP", RECEIVED.plusSeconds(1))));
        assertThat(service.get(new ArrivalQuery(STATION, ROUTE, 8L, 22)).status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
    }

    @Test
    void 잘못된_필터조합은_입력_단계에서_거절한다() {
        assertThatThrownBy(() -> new ArrivalQuery(STATION, ROUTE, 7L, null)).isInstanceOf(ArrivalQuery.Failure.class);
        assertThatThrownBy(() -> new ArrivalQuery(STATION, null, 7L, 22)).isInstanceOf(ArrivalQuery.Failure.class);
        assertThatThrownBy(() -> new ArrivalQuery("bad", null, null, null)).isInstanceOf(ArrivalQuery.Failure.class);
    }
}
