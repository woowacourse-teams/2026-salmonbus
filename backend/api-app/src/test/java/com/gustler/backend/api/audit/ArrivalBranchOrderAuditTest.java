package com.gustler.backend.api.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.application.ArrivalFirstResultPolicy;
import com.gustler.backend.api.arrival.application.ArrivalFreshnessPolicy;
import com.gustler.backend.api.arrival.application.ArrivalQueryService;
import com.gustler.backend.api.arrival.application.ArrivalReadRepository;
import com.gustler.backend.api.arrival.application.ArrivalRefreshGateway;
import com.gustler.backend.api.arrival.application.StationArrivalsOverview;
import com.gustler.backend.api.arrival.domain.ArrivalAvailability;
import com.gustler.backend.api.arrival.domain.ArrivalVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.RouteArrival;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** 분기 정리 전후의 시간 경계와 실패 시 대기 슬롯 반환을 독립적으로 확인하는 회귀 테스트다. */
class ArrivalBranchOrderAuditTest {
    private static final String STATION = "206000637";
    private static final String ROUTE = "204000057";
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final Duration WAIT = Duration.ofMillis(500);
    private final ArrivalReadRepository repository = mock(ArrivalReadRepository.class);
    private final ArrivalRefreshGateway gateway = mock(ArrivalRefreshGateway.class);
    private final Clock clock = mock(Clock.class);
    private final ArrivalFirstResultPolicy policy = new ArrivalFirstResultPolicy(WAIT, 1);
    private final ArrivalQueryService service = new ArrivalQueryService(
        repository, gateway, new ArrivalFreshnessPolicy(), clock, policy);

    @Test
    void 첫_수신을_다시_읽고_판본을_확인한_뒤의_시각으로_응답을_만든다() {
        // given
        Instant firstCheck = NOW;
        Instant refreshCheck = NOW.plusMillis(1);
        Instant resultCheck = NOW.plusMillis(2);
        Instant collectedRefreshCheck = NOW.plusMillis(3);
        Instant generatedAt = NOW.plusMillis(4);
        when(clock.instant()).thenReturn(
            firstCheck, refreshCheck, resultCheck, collectedRefreshCheck, generatedAt);
        when(repository.visits(STATION)).thenReturn(visits());
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(snapshot()));
        when(gateway.request(STATION, WAIT)).thenReturn(
            new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));

        // when
        StationArrivalsOverview result = service.get(query());

        // then
        assertThat(result.status()).isEqualTo(ArrivalAvailability.READY);
        assertThat(result.generatedAt()).isEqualTo(generatedAt);
        assertThat(result.refresh().status()).isEqualTo(Status.IDLE);
        InOrder order = inOrder(repository, clock, gateway);
        order.verify(repository).visits(STATION);
        order.verify(repository).latest(STATION);
        order.verify(clock, times(2)).instant();
        order.verify(gateway).request(STATION, WAIT);
        order.verify(repository).latest(STATION);
        order.verify(clock).instant();
        order.verify(repository).visits(STATION);
        order.verify(clock, times(2)).instant();
        order.verifyNoMoreInteractions();
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 첫_대기_요청이_예외로_실패해도_슬롯을_반환하고_같은_예외를_전파한다() {
        // given
        RuntimeException failure = new IllegalStateException("worker failure");
        when(clock.instant()).thenReturn(NOW);
        when(repository.visits(STATION)).thenReturn(visits());
        when(repository.latest(STATION)).thenReturn(Optional.empty());
        when(gateway.request(STATION, WAIT)).thenThrow(failure);

        // when
        Throwable thrown = catchThrowable(() -> service.get(query()));

        // then
        assertThat(thrown).isSameAs(failure);
        verify(repository).latest(STATION);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 갱신_뒤의_DB_재조회가_실패해도_성공으로_가리지_않고_슬롯을_반환한다() {
        // given
        RuntimeException failure = new IllegalStateException("repository failure");
        when(clock.instant()).thenReturn(NOW);
        when(repository.visits(STATION)).thenReturn(visits());
        when(repository.latest(STATION)).thenReturn(Optional.empty()).thenThrow(failure);
        when(gateway.request(STATION, WAIT)).thenReturn(
            new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));

        // when
        Throwable thrown = catchThrowable(() -> service.get(query()));

        // then
        assertThat(thrown).isSameAs(failure);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 지원하지_않는_정류장은_수신결과와_시계와_Worker를_조회하기_전에_거절한다() {
        // given
        when(repository.visits(STATION)).thenReturn(List.of());

        // when
        Throwable thrown = catchThrowable(() -> service.get(query()));

        // then
        assertThat(thrown).isInstanceOfSatisfying(ArrivalQuery.Failure.class,
            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.STATION_NOT_FOUND));
        verify(repository).visits(STATION);
        verifyNoMoreInteractions(repository);
        verifyNoInteractions(clock, gateway);
    }

    private static ArrivalQuery query() {
        return new ArrivalQuery(STATION, ROUTE, 7L, 22);
    }

    private static List<ArrivalVisit> visits() {
        return List.of(new ArrivalVisit(ROUTE, ROUTE, 7, 22, "UP", NOW.minusSeconds(100)));
    }

    private static ArrivalSnapshot snapshot() {
        Prediction prediction = new Prediction("bus", 1, 180, 3, 3, 0, null, null, null, false);
        return new ArrivalSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), STATION,
            NOW, NOW.plusSeconds(40), NOW.plusSeconds(60), null,
            List.of(new RouteArrival(ROUTE, "3330", 22, 12, null, null, "RUN", List.of(prediction))));
    }
}
