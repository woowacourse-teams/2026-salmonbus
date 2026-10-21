package com.gustler.backend.arrivals.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArrivalRefreshCoordinatorTest {
    private static final String STATION = "206000637";
    private static final Instant BASE = Instant.parse("2026-10-14T03:00:00Z");
    private final AtomicLong millis = new AtomicLong();
    private final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return BASE.plusMillis(millis.get());
        }
    };
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final ArrivalStore store = mock(ArrivalStore.class);
    private final ArrivalSource source = mock(ArrivalSource.class);
    private final ArrivalCallQuota quota = mock(ArrivalCallQuota.class);
    private ArrivalRefreshCoordinator coordinator;

    @BeforeEach
    void setUp() {
        when(store.supported(anyString())).thenReturn(true);
        when(store.latest(anyString())).thenReturn(Optional.empty());
        when(quota.reserve(any())).thenAnswer(call -> Optional.of(new ArrivalCallQuota.Reservation("a",
            ((Instant) call.getArgument(0)).atZone(ArrivalCallQuota.KOREA).toLocalDate())));
        when(source.fetch(anyString(), anyString())).thenAnswer(call -> new Success(clock.instant(), null, List.of()));
        coordinator = coordinator(queue::add, 1000, 100);
        millis.set(40_000);
    }

    private ArrivalRefreshCoordinator coordinator(Executor executor, final double rate, final int burst) {
        ArrivalCollectionPolicy defaults = ArrivalCollectionPolicy.defaults();
        ArrivalCollectionPolicy policy =
            new ArrivalCollectionPolicy(defaults.refreshInterval(), defaults.validity(), defaults.retryBase(),
                defaults.retryMaximum(), defaults.startupDelay(), defaults.queueWait(), 10_000, rate, burst, 2);
        return new ArrivalRefreshCoordinator(store, source, quota, executor, policy, clock,
            () -> millis.get() * 1_000_000);
    }

    @Test
    void 같은_정류장_천_요청은_한_작업과_한_호출로_합쳐진다() throws Exception {
        // when
        List<ArrivalRefresh> replies = new ArrayList<>();
        try (ExecutorService callers = Executors.newFixedThreadPool(16)) {
            List<Future<ArrivalRefresh>> requests = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                requests.add(callers.submit(() -> coordinator.request(STATION)));
            }
            for (Future<ArrivalRefresh> request : requests) {
                replies.add(request.get(5, TimeUnit.SECONDS));
            }
        }

        // then
        assertThat(replies.stream()
                .filter(reply -> reply.status() == Status.ACCEPTED)).hasSize(1);
        assertThat(queue).hasSize(1);
        verifyNoInteractions(source, quota);
        queue.remove().run();
        verify(source, times(1)).fetch(STATION, "a");
        verify(quota, times(1)).reserve(any());
        verify(store, times(1)).save(any());
        assertThat(coordinator.request(STATION).reason()).isEqualTo("NOT_DUE");
    }

    @Test
    void 실패_대기는_새_요청에_연장되지_않고_연속_실패에는_늘어난다() {
        // when
        when(source.fetch(anyString(), anyString())).thenReturn(new Failed(Reason.TRANSPORT_ERROR));
        coordinator.request(STATION);
        queue.remove().run();
        Instant first = coordinator.request(STATION).suggestedRefreshAt();

        // then
        assertThat(first).isEqualTo(BASE.plusSeconds(80));
        millis.addAndGet(10_000);
        assertThat(coordinator.request(STATION).suggestedRefreshAt()).isEqualTo(first);
        millis.set(80_000);
        assertThat(coordinator.request(STATION).status()).isEqualTo(Status.ACCEPTED);
        queue.remove().run();
        assertThat(coordinator.request(STATION).suggestedRefreshAt()).isEqualTo(BASE.plusSeconds(160));
    }

    @Test
    void 저장_중에도_합류하고_저장_재시도는_GBIS를_다시_부르지_않는다() {
        // when
        AtomicInteger saves = new AtomicInteger();
        doAnswer(call -> {

            // then
            assertThat(coordinator.request(STATION).status()).isEqualTo(Status.IN_PROGRESS);
            if (saves.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary storage failure");
            }
            return null;
        }).when(store).save(any());
        coordinator.request(STATION);
        queue.remove().run();
        assertThat(saves).hasValue(2);
        verify(source, times(1)).fetch(anyString(), anyString());
        verify(quota, times(1)).reserve(any());
        assertThat(coordinator.request(STATION).reason()).isEqualTo("NOT_DUE");
    }

    @Test
    void 대기열에서_만료된_작업은_예산을_쓰지_않는다() {
        // when
        coordinator.request(STATION);
        millis.addAndGet(2_001);
        queue.remove().run();

        // then
        verifyNoInteractions(source, quota);
        assertThat(coordinator.request(STATION).reason()).isEqualTo("QUEUE_EXPIRED");
    }

    @Test
    void 대기열_거절은_진행중_표시를_남기지_않는다() {
        // when
        AtomicBoolean full = new AtomicBoolean(true);
        coordinator = coordinator(command -> {
            if (full.get()) {
                throw new RejectedExecutionException();
            }
            queue.add(command);
        }, 1000, 100);
        millis.addAndGet(40_000);

        // then
        assertThat(coordinator.request(STATION).reason()).isEqualTo("BUSY");
        verifyNoInteractions(source, quota);
        full.set(false);
        millis.addAndGet(2_000);
        assertThat(coordinator.request(STATION).status()).isEqualTo(Status.ACCEPTED);
        queue.remove().run();
        verify(source).fetch(STATION, "a");
    }

    @Test
    void 실행_직전_이미_갱신된_수신결과를_찾으면_호출하지_않는다() {
        // when
        ArrivalSnapshot snapshot = new ArrivalSnapshot(UUID.randomUUID(), STATION, clock.instant(),
            clock.instant().plusSeconds(40), clock.instant().plusSeconds(60), null, List.of());
        when(store.latest(STATION)).thenReturn(Optional.of(snapshot));
        coordinator.request(STATION);
        queue.remove().run();

        // then
        verifyNoInteractions(source, quota);
        assertThat(coordinator.request(STATION).reason()).isEqualTo("NOT_DUE");
    }

    @Test
    void 전체_제한이_발생하면_이미_대기중이던_다른_정류장도_호출하지_않는다() {
        // when
        when(source.fetch(anyString(), anyString())).thenReturn(new Failed(Reason.RATE_LIMITED));
        coordinator.request(STATION);
        coordinator.request("206000532");
        queue.remove().run();
        queue.remove().run();

        // then
        verify(source, times(1)).fetch(anyString(), anyString());
        verify(quota, times(1)).reserve(any());
        assertThat(coordinator.request("206000532").reason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void 재시작_유예와_예산소진은_추가_예약을_막는다() {
        // when
        coordinator = coordinator(queue::add, 1000, 100);

        // then
        assertThat(coordinator.request(STATION).reason()).isEqualTo("STARTING");
        millis.addAndGet(40_000);
        doReturn(Optional.empty()).when(quota).reserve(any());
        coordinator.request(STATION);
        queue.remove().run();
        assertThat(coordinator.request("206000532").reason()).isEqualTo("DAILY_QUOTA_EXHAUSTED");
        verify(quota, times(1)).reserve(any());
        verifyNoInteractions(source);
    }

    @Test
    void 토큰이_소진되면_다른_정류장은_예산예약_전에_대기한다() {
        // when
        coordinator = coordinator(queue::add, 0.5, 1);
        millis.addAndGet(40_000);
        coordinator.request(STATION);
        queue.remove().run();
        coordinator.request("206000532");
        queue.remove().run();

        // then
        verify(quota, times(1)).reserve(any());
        verify(source, times(1)).fetch(anyString(), anyString());
        assertThat(coordinator.request("206000532").reason()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void 상류의_RetryAfter가_더_길면_전체_호출을_그만큼_미룬다() {
        // when
        when(source.fetch(anyString(), anyString())).thenReturn(new Failed(
            Reason.RATE_LIMITED, Duration.ofSeconds(120)));
        coordinator.request(STATION);
        queue.remove().run();

        // then
        assertThat(coordinator.request("206000532").suggestedRefreshAt()).isEqualTo(BASE.plusSeconds(160));
        millis.addAndGet(50_000);
        assertThat(coordinator.request("206000532").suggestedRefreshAt()).isEqualTo(BASE.plusSeconds(160));
        verify(quota, times(1)).reserve(any());
    }
}
