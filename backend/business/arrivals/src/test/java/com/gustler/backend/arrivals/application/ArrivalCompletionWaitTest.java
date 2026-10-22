package com.gustler.backend.arrivals.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;

class ArrivalCompletionWaitTest {
    private static final String STATION = "206000637";
    private static final Instant NOW = Instant.parse("2026-10-19T03:00:00Z");
    private final ArrivalStore store = mock(ArrivalStore.class);
    private final ArrivalSource source = mock(ArrivalSource.class);
    private final ArrivalCallQuota quota = mock(ArrivalCallQuota.class);

    private ArrivalRefreshCoordinator coordinator(Executor executor) {
        when(store.supported(anyString())).thenReturn(true);
        when(store.latest(anyString())).thenReturn(Optional.empty());
        when(quota.reserve(any())).thenReturn(Optional.of(new ArrivalCallQuota.Reservation("a", NOW.atZone(ArrivalCallQuota.KOREA)
            .toLocalDate())));
        when(source.fetch(anyString(), anyString())).thenReturn(new Success(NOW, null, List.of()));
        ArrivalCollectionPolicy defaults = ArrivalCollectionPolicy.defaults();
        AtomicLong nanos = new AtomicLong();
        ArrivalRefreshCoordinator result = new ArrivalRefreshCoordinator(store, source, quota, executor, defaults,
            Clock.fixed(NOW, ZoneOffset.UTC), nanos::get);
        nanos.set(Duration.ofSeconds(40).toNanos());
        return result;
    }

    @Test
    void 같은_수집_완료를_여러_요청이_기다려도_상류는_한번만_호출한다() throws Exception {
        // when
        try (ExecutorService worker = Executors.newSingleThreadExecutor(); ExecutorService callers =
            Executors.newFixedThreadPool(20)) {
            ArrivalRefreshCoordinator coordinator = coordinator(worker);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<ArrivalSnapshot> saved = new AtomicReference<>();
            when(store.latest(STATION)).thenAnswer(call -> Optional.ofNullable(saved.get()));
            when(source.fetch(anyString(), anyString())).thenAnswer(call -> {
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                return new Success(NOW, null, List.of());
            });
            doAnswer(call -> {
                saved.set(call.getArgument(0));
                return null;
            }).when(store).save(any());
            List<Future<ArrivalRefresh>> requests = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                requests.add(callers.submit(() -> coordinator.requestAndAwait(STATION, Duration.ofSeconds(1))));
            }

            // then
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            for (Future<ArrivalRefresh> request : requests) {
                ArrivalRefresh reply = request.get(2, TimeUnit.SECONDS);
                assertThat(reply.status()).isIn(Status.IDLE, Status.DEFERRED);
                assertThat(reply.reason()).isIn(null, "NOT_DUE");
            }
            assertThat(saved.get()).isNotNull();
            verify(source, times(1)).fetch(anyString(), anyString());
            verify(quota, times(1)).reserve(any());
        }
    }

    @Test
    void 제한시간을_넘겨도_수집을_중복하거나_작업을_취소하지_않는다() throws Exception {
        // when
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            ArrivalRefreshCoordinator coordinator = coordinator(worker);
            CountDownLatch release = new CountDownLatch(1);
            when(source.fetch(anyString(), anyString())).thenAnswer(call -> {
                release.await(2, TimeUnit.SECONDS);
                return new Success(NOW, null, List.of());
            });
            final long began = System.nanoTime();
            ArrivalRefresh reply = coordinator.requestAndAwait(STATION, Duration.ofMillis(100));
            final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);

            // then
            assertThat(reply.status()).isEqualTo(Status.IN_PROGRESS);
            assertThat(elapsed).isBetween(75L, 1000L);
            verify(store, never()).save(any());
            assertThat(coordinator.request(STATION).status()).isEqualTo(Status.IN_PROGRESS);
            release.countDown();
            worker.shutdown();
            assertThat(worker.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            verify(source, times(1)).fetch(anyString(), anyString());
            verify(store).save(any());
        }
    }

    @Test
    void 완료_대기가_상태_잠금을_잡지_않아_다른_정류장을_접수할_수_있다() throws Exception {
        // when
        Queue<Runnable> jobs = new ConcurrentLinkedQueue<>();
        CountDownLatch queued = new CountDownLatch(1);
        ArrivalRefreshCoordinator coordinator = coordinator(job -> {
            jobs.add(job);
            queued.countDown();
        });
        try (ExecutorService callers = Executors.newFixedThreadPool(2)) {
            Future<ArrivalRefresh> waiting =
                callers.submit(() -> coordinator.requestAndAwait(STATION, Duration.ofMillis(300)));

            // then
            assertThat(queued.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(callers.submit(() -> coordinator.request("206000638")).get(200, TimeUnit.MILLISECONDS).status())
                .isEqualTo(Status.ACCEPTED);
            assertThat(waiting.get(1, TimeUnit.SECONDS).status()).isEqualTo(Status.IN_PROGRESS);
        }
    }

    @Test
    void 상류_실패도_완료를_알리고_성공_상태로_꾸미지_않는다() {
        // when
        ArrivalRefreshCoordinator coordinator = coordinator(Runnable::run);
        when(source.fetch(anyString(), anyString())).thenReturn(new Failed(Reason.UPSTREAM_ERROR));
        ArrivalRefresh reply = coordinator.requestAndAwait(STATION, Duration.ofMillis(500));

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reply.status()).isEqualTo(Status.DEFERRED);
            softly.assertThat(reply.reason()).isEqualTo("UPSTREAM_ERROR");
        }
        verify(store, never()).save(any());
    }
}
