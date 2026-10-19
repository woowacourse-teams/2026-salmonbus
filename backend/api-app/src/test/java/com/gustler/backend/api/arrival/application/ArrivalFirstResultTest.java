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

class ArrivalFirstResultTest {
    private static final String STATION = "206000637";
    private static final String ROUTE = "204000057";
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private static final Duration WAIT = Duration.ofMillis(500);
    private final ArrivalReadRepository repository = mock(ArrivalReadRepository.class);
    private final ArrivalRefreshGateway gateway = mock(ArrivalRefreshGateway.class);
    private final ArrivalFirstResultPolicy policy = new ArrivalFirstResultPolicy(WAIT, 1);
    private final ArrivalQueryService service = new ArrivalQueryService(repository, gateway, new ArrivalFreshnessPolicy(),
        Clock.fixed(NOW, ZoneOffset.UTC), policy);
    private final ArrivalQuery query = new ArrivalQuery(STATION, ROUTE, 1L, 1);

    @BeforeEach
    void setUp() {
        when(repository.visits(STATION)).thenReturn(List.of(visit(1)));
        when(repository.latest(STATION)).thenReturn(Optional.empty());
        when(gateway.request(STATION, WAIT)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.IDLE, null, NOW.plusSeconds(40)));
    }

    @Test
    void 첫_수집이_끝나면_첫_응답에_실제_예측을_담고_갱신중_표시를_없앤다() {
        ArrivalSnapshot collected = snapshot(NOW, true);
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(collected));
        var result = service.get(query);
        assertThat(result.status()).isEqualTo(ArrivalAvailability.READY);
        assertThat(result.snapshot()).isSameAs(collected);
        assertThat(result.routes().getFirst().arrivals().getFirst().remainingSeconds()).isEqualTo(180);
        assertThat(result.refresh().status()).isEqualTo(ArrivalRefresh.Status.IDLE);
        assertThat(result.cacheMaxAge()).isEqualTo(Duration.ofSeconds(5));
        verify(repository, times(2)).latest(STATION);
        verify(repository, times(2)).visits(STATION);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 정상_빈_수신은_예측이_나올때까지_기다리지_않고_NO_PREDICTION으로_반환한다() {
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(snapshot(NOW, false)));
        assertThat(service.get(query).status()).isEqualTo(ArrivalAvailability.NO_PREDICTION);
    }

    @Test
    void 대기_한도에_수신이_없으면_기존_COLLECTING으로_돌아간다() {
        when(gateway.request(STATION, WAIT)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.IN_PROGRESS, "COLLECTING", NOW.plusSeconds(2)));
        var result = service.get(query);
        assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("NOT_COLLECTED");
        assertThat(result.refresh().reason()).isEqualTo("COLLECTING");
        verify(repository, times(2)).latest(STATION);
        verify(repository, times(1)).visits(STATION);
    }

    @Test
    void 갱신_시각이_지나도_유효한_수신이_있으면_대기하지_않는다() {
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(NOW.minusSeconds(45), true)));
        when(gateway.request(STATION)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));
        assertThat(service.get(query).status()).isEqualTo(ArrivalAvailability.READY);
        verify(gateway).request(STATION);
        verify(gateway, never()).request(anyString(), any());
        verify(repository, times(1)).latest(STATION);
    }

    @Test
    void 만료된_수신을_대기_시간만큼_연장하지_않는다() {
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(NOW.minusSeconds(60), true)));
        when(gateway.request(STATION, WAIT)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.IN_PROGRESS, "COLLECTING", NOW.plusSeconds(2)));
        var result = service.get(query);
        assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
        assertThat(result.reason()).isEqualTo("STALE");
        assertThat(result.routes()).isEmpty();
        assertThat(result.snapshot().staleAt()).isEqualTo(NOW);
    }

    @Test
    void 한도_초과나_실패_응답에는_추가_DB_조회를_하지_않는다() {
        when(gateway.request(STATION, WAIT)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.DEFERRED, "DAILY_QUOTA_EXHAUSTED", NOW.plusSeconds(100)));
        assertThat(service.get(query).refresh().reason()).isEqualTo("DAILY_QUOTA_EXHAUSTED");
        verify(repository, times(1)).latest(STATION);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 대기_슬롯이_차면_요청을_쌓지_않고_기존_빠른_접수를_사용한다() {
        assertThat(policy.tryAcquire()).isTrue();
        when(gateway.request(STATION)).thenReturn(new ArrivalRefresh(ArrivalRefresh.Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));
        assertThat(service.get(query).reason()).isEqualTo("NOT_COLLECTED");
        verify(gateway).request(STATION);
        verify(gateway, never()).request(anyString(), any());
        policy.release();
    }

    @Test
    void 대기하는_사이에_판본이_바뀌면_이전_방문의_예측을_반환하지_않는다() {
        when(repository.visits(STATION)).thenReturn(List.of(visit(1)), List.of(visit(2)));
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(snapshot(NOW, true)));
        assertThatThrownBy(() -> service.get(query)).isInstanceOfSatisfying(ArrivalQuery.Failure.class,
            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.REFERENCE_VERSION_CONFLICT));
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 대기는_명시적으로_켜야_하고_최대_1초까지만_설정한다() {
        assertThat(new ArrivalFirstResultPolicy(Duration.ZERO, 32).tryAcquire()).isFalse();
        assertThatThrownBy(() -> new ArrivalFirstResultPolicy(Duration.ofMillis(1001), 32)).isInstanceOf(IllegalArgumentException.class);
    }

    private ArrivalVisit visit(final long version) {
        return new ArrivalVisit(ROUTE, ROUTE, version, 1, "UP", NOW.minusSeconds(100));
    }

    private ArrivalSnapshot snapshot(Instant received, final boolean withPrediction) {
        return new ArrivalSnapshot(UUID.randomUUID(), STATION, received, received.plusSeconds(40), received.plusSeconds(60), null,
            withPrediction ? List.of(new RouteArrival(ROUTE, "3330", 1, 12, null, null, "RUN",
                List.of(new Prediction("test-bus", 1, 180, 3, 3, 0, null, null, null, null)))) : List.of());
    }
}
