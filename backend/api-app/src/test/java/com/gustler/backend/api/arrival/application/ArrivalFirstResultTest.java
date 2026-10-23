package com.gustler.backend.api.arrival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.arrival.ArrivalQuery;
import com.gustler.backend.api.arrival.ArrivalQuery.Failure;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.AutoCloseableSoftAssertions;
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
    private final ArrivalQueryService service =
        new ArrivalQueryService(repository, gateway, new ArrivalFreshnessPolicy(),
            Clock.fixed(NOW, ZoneOffset.UTC), policy);
    private final ArrivalQuery query = new ArrivalQuery(STATION, ROUTE, 1L, 1);

    @BeforeEach
    void setUp() {
        when(repository.visits(STATION)).thenReturn(List.of(visit(1)));
        when(repository.latest(STATION)).thenReturn(Optional.empty());
        when(gateway.request(STATION, WAIT)).thenReturn(new ArrivalRefresh(Status.IDLE, null, NOW.plusSeconds(40)));
    }

    @Test
    void 첫_수집이_끝나면_첫_응답에_실제_예측을_담고_갱신중_표시를_없앤다() {
        // when
        ArrivalSnapshot collected = snapshot(NOW, true);
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(collected));
        StationArrivalsOverview result = service.get(query);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.status()).isEqualTo(ArrivalAvailability.READY);
            softly.assertThat(result.snapshot()).isSameAs(collected);
        }
        assertThat(result.routes().getFirst().arrivals().getFirst().remainingSeconds()).isEqualTo(180);
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.refresh().status()).isEqualTo(Status.IDLE);
            softly.assertThat(result.cacheMaxAge()).isEqualTo(Duration.ofSeconds(5));
        }
        verify(repository, times(2)).latest(STATION);
        verify(repository, times(2)).visits(STATION);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 정상_빈_수신은_예측이_나올때까지_기다리지_않고_NO_PREDICTION으로_반환한다() {
        // when
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(snapshot(NOW, false)));

        // then
        assertThat(service.get(query).status()).isEqualTo(ArrivalAvailability.NO_PREDICTION);
    }

    @Test
    void 대기_한도에_수신이_없으면_기존_COLLECTING으로_돌아간다() {
        // when
        when(gateway.request(STATION, WAIT)).thenReturn(
            new ArrivalRefresh(Status.IN_PROGRESS, "COLLECTING", NOW.plusSeconds(2)));
        StationArrivalsOverview result = service.get(query);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
            softly.assertThat(result.reason()).isEqualTo("NOT_COLLECTED");
            softly.assertThat(result.refresh().reason()).isEqualTo("COLLECTING");
        }
        verify(repository, times(2)).latest(STATION);
        verify(repository, times(1)).visits(STATION);
    }

    @Test
    void 갱신_시각이_지나도_유효한_수신이_있으면_대기하지_않는다() {
        // when
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(NOW.minusSeconds(45), true)));
        when(gateway.request(STATION)).thenReturn(
            new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));

        // then
        assertThat(service.get(query).status()).isEqualTo(ArrivalAvailability.READY);
        verify(gateway).request(STATION);
        verify(gateway, never()).request(anyString(), any());
        verify(repository, times(1)).latest(STATION);
    }

    @Test
    void 만료된_수신을_대기_시간만큼_연장하지_않는다() {
        // when
        when(repository.latest(STATION)).thenReturn(Optional.of(snapshot(NOW.minusSeconds(60), true)));
        when(gateway.request(STATION, WAIT)).thenReturn(
            new ArrivalRefresh(Status.IN_PROGRESS, "COLLECTING", NOW.plusSeconds(2)));
        StationArrivalsOverview result = service.get(query);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.status()).isEqualTo(ArrivalAvailability.UNAVAILABLE);
            softly.assertThat(result.reason()).isEqualTo("STALE");
        }
        assertThat(result.routes()).isEmpty();
        assertThat(result.snapshot().staleAt()).isEqualTo(NOW);
    }

    @Test
    void 한도_초과나_실패_응답에는_추가_DB_조회를_하지_않는다() {
        // when
        when(gateway.request(STATION, WAIT)).thenReturn(
            new ArrivalRefresh(Status.DEFERRED, "DAILY_QUOTA_EXHAUSTED", NOW.plusSeconds(100)));

        // then
        assertThat(service.get(query).refresh().reason()).isEqualTo("DAILY_QUOTA_EXHAUSTED");
        verify(repository, times(1)).latest(STATION);
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 대기_슬롯이_차면_요청을_쌓지_않고_기존_빠른_접수를_사용한다() {
        // when
        final boolean acquired = policy.tryAcquire();

        // then
        assertThat(acquired).isTrue();

        // given
        when(gateway.request(STATION)).thenReturn(
            new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(2)));

        // when
        String reason = service.get(query).reason();

        // then
        assertThat(reason).isEqualTo("NOT_COLLECTED");
        verify(gateway).request(STATION);
        verify(gateway, never()).request(anyString(), any());
        policy.release();
    }

    @Test
    void 대기하는_사이에_판본이_바뀌면_이전_방문의_예측을_반환하지_않는다() {
        // when
        when(repository.visits(STATION)).thenReturn(List.of(visit(1)), List.of(visit(2)));
        when(repository.latest(STATION)).thenReturn(Optional.empty(), Optional.of(snapshot(NOW, true)));

        // then

        // when
        Throwable phaseFailure1 = catchThrowable(() -> service.get(query));

        // then
        assertThat(phaseFailure1)
            .isNotNull().isInstanceOfSatisfying(Failure.class,
            failure -> assertThat(failure.code()).isEqualTo(ErrorCode.REFERENCE_VERSION_CONFLICT));
        assertThat(policy.tryAcquire()).isTrue();
        policy.release();
    }

    @Test
    void 대기는_명시적으로_켜야_하고_최대_1초까지만_설정한다() {
        // when
        final boolean acquired = new ArrivalFirstResultPolicy(Duration.ZERO, 32).tryAcquire();

        // then
        assertThat(acquired).isFalse();

        // when
        Throwable caughtFailure = catchThrowable(() ->
            new ArrivalFirstResultPolicy(Duration.ofMillis(1001), 32));

        // then
        assertThat(caughtFailure).isNotNull().isInstanceOf(IllegalArgumentException.class);
    }

    private ArrivalVisit visit(final long version) {
        return new ArrivalVisit(ROUTE, ROUTE, version, 1, "UP", NOW.minusSeconds(100));
    }

    private ArrivalSnapshot snapshot(Instant received, final boolean withPrediction) {
        return new ArrivalSnapshot(UUID.randomUUID(), STATION, received, received.plusSeconds(40),
            received.plusSeconds(60), null,
            arrivals(withPrediction));
    }

    private static List<RouteArrival> arrivals(final boolean withPrediction) {
        if (!withPrediction) {
            return List.of();
        }
        return List.of(new RouteArrival(ROUTE, "3330", 1, 12, null, null, "RUN",
            List.of(new Prediction("test-bus", 1, 180, 3, 3, 0, null, null, null, null))));
    }
}
