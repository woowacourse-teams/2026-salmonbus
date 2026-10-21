package com.gustler.backend.arrivals.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalSource.Failed;
import com.gustler.backend.arrivals.domain.ArrivalSource.Reason;
import com.gustler.backend.arrivals.domain.ArrivalSource.Success;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import com.gustler.backend.quota.api.ArrivalCallQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

class ArrivalCoordinatorPreservationAuditTest {

    private static final String STATION = "206000637";
    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-20T14:59:59.999Z");
    private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-10-20T15:00:00Z");

    private final ArrivalStore store = mock(ArrivalStore.class);
    private final ArrivalSource source = mock(ArrivalSource.class);
    private final ArrivalCallQuota quota = mock(ArrivalCallQuota.class);
    private final Clock clock = mock(Clock.class);

    @Test
    void 한국_자정_직후에는_두번째_예약의_키로만_전송한다() {
        // given
        when(clock.instant()).thenReturn(BEFORE_MIDNIGHT);
        ArrivalCallQuota.Reservation previous = reservation("a", BEFORE_MIDNIGHT);
        ArrivalCallQuota.Reservation current = reservation("b", AFTER_MIDNIGHT);
        when(quota.reserve(BEFORE_MIDNIGHT)).thenAnswer(call -> {
            when(clock.instant()).thenReturn(AFTER_MIDNIGHT);
            return Optional.of(previous);
        });
        when(quota.reserve(AFTER_MIDNIGHT)).thenReturn(Optional.of(current));
        when(source.fetch(STATION, "b")).thenReturn(new Success(AFTER_MIDNIGHT, null, List.of()));
        ArrivalRefreshCoordinator coordinator = coordinator(Runnable::run);

        // when
        ArrivalRefresh result = coordinator.requestAndAwait(STATION, Duration.ofMillis(100));

        // then
        assertThat(result.status()).isEqualTo(Status.IDLE);
        InOrder order = inOrder(store, quota, source);
        order.verify(store).supported(STATION);
        order.verify(store).latest(STATION);
        order.verify(quota).reserve(BEFORE_MIDNIGHT);
        order.verify(quota).reserve(AFTER_MIDNIGHT);
        order.verify(source).fetch(STATION, "b");
        order.verify(store).save(any());
        verify(source, never()).fetch(STATION, "a");
        verify(quota, never()).exclude(any());
    }

    @Test
    void 자정_재예약이_거절되면_이전날_키로_전송하지_않는다() {
        // given
        when(clock.instant()).thenReturn(BEFORE_MIDNIGHT);
        when(quota.reserve(BEFORE_MIDNIGHT)).thenAnswer(call -> {
            when(clock.instant()).thenReturn(AFTER_MIDNIGHT);
            return Optional.of(reservation("a", BEFORE_MIDNIGHT));
        });
        when(quota.reserve(AFTER_MIDNIGHT)).thenReturn(Optional.empty());
        ArrivalRefreshCoordinator coordinator = coordinator(Runnable::run);

        // when
        ArrivalRefresh result = coordinator.requestAndAwait(STATION, Duration.ofMillis(100));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(result.status()).isEqualTo(Status.DEFERRED);
            softly.assertThat(result.reason()).isEqualTo("DAILY_QUOTA_EXHAUSTED");
            softly.assertThat(result.suggestedRefreshAt()).isEqualTo(Instant.parse("2026-10-21T15:00:00Z"));
        }
        verify(source, never()).fetch(anyString(), anyString());
        verify(store, never()).save(any());
        verify(quota, never()).exclude(any());
    }

    @Test
    void 거절된_키는_실제_전송에_쓴_예약_날짜로_제외한다() {
        // given
        when(clock.instant()).thenReturn(AFTER_MIDNIGHT);
        ArrivalCallQuota.Reservation reserved = reservation("b", AFTER_MIDNIGHT);
        when(quota.reserve(AFTER_MIDNIGHT)).thenReturn(Optional.of(reserved));
        when(source.fetch(STATION, "b")).thenReturn(new Failed(Reason.KEY_REJECTED));
        ArrivalRefreshCoordinator coordinator = coordinator(Runnable::run);

        // when
        ArrivalRefresh result = coordinator.requestAndAwait(STATION, Duration.ofMillis(100));

        // then
        assertThat(result.reason()).isEqualTo("KEY_REJECTED");
        InOrder order = inOrder(quota, source);
        order.verify(quota).reserve(AFTER_MIDNIGHT);
        order.verify(source).fetch(STATION, "b");
        order.verify(quota).exclude(reserved);
        verify(store, never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("invalidWaits")
    void 완료_대기가_허용범위_밖이면_작업을_접수하지_않는다(Duration maximumWait) {
        // given
        Queue<Runnable> jobs = new ArrayDeque<>();
        ArrivalRefreshCoordinator coordinator = coordinator(jobs::add);

        // when
        Throwable failure = catchThrowable(() -> coordinator.requestAndAwait(STATION, maximumWait));

        // then
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        assertThat(jobs).isEmpty();
        verify(quota, never()).reserve(any());
    }

    private static Stream<Duration> invalidWaits() {
        return Stream.of(null, Duration.ofNanos(-1), Duration.ofMillis(ArrivalRefresh.MAX_WAIT_MILLIS + 1L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void 시간초과나_인터럽트가_반복돼도_완료대기_자리를_반환한다(final boolean interrupt) throws Exception {
        // given
        when(clock.instant()).thenReturn(AFTER_MIDNIGHT);
        when(quota.reserve(AFTER_MIDNIGHT)).thenReturn(Optional.of(reservation("a", AFTER_MIDNIGHT)));
        when(source.fetch(STATION, "a")).thenReturn(new Success(AFTER_MIDNIGHT, null, List.of()));
        Queue<Runnable> jobs = new ArrayDeque<>();
        ArrivalRefreshCoordinator coordinator = coordinator(jobs::add);
        for (int attempt = 0; attempt < 40; attempt++) {
            try {
                if (interrupt) {
                    Thread.currentThread().interrupt();
                }
                coordinator.requestAndAwait(STATION, Duration.ofMillis(1));
            } finally {
                Thread.interrupted();
            }
        }

        // when
        try (ExecutorService caller = Executors.newSingleThreadExecutor()) {
            Future<ArrivalRefresh> waiting = caller.submit(
                () -> coordinator.requestAndAwait(STATION, Duration.ofSeconds(1)));
            Throwable earlyReturn = catchThrowable(() -> waiting.get(100, TimeUnit.MILLISECONDS));
            jobs.remove().run();
            ArrivalRefresh completed = waiting.get(1, TimeUnit.SECONDS);

            // then
            assertThat(earlyReturn).isInstanceOf(TimeoutException.class);
            assertThat(completed.status()).isEqualTo(Status.IDLE);
            verify(source).fetch(STATION, "a");
        }
    }

    private ArrivalRefreshCoordinator coordinator(Executor executor) {
        when(store.supported(STATION)).thenReturn(true);
        when(store.latest(STATION)).thenReturn(Optional.empty());
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        AtomicLong nanos = new AtomicLong();
        ArrivalRefreshCoordinator coordinator = new ArrivalRefreshCoordinator(store, source, quota, executor,
            ArrivalCollectionPolicy.defaults(), clock, nanos::get);
        nanos.set(Duration.ofSeconds(40).toNanos());
        return coordinator;
    }

    private static ArrivalCallQuota.Reservation reservation(String alias, Instant at) {
        return new ArrivalCallQuota.Reservation(alias, at.atZone(ArrivalCallQuota.KOREA).toLocalDate());
    }
}
