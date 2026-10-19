package com.gustler.backend.arrivals.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrivals.api.ArrivalCollectionPolicy;
import com.gustler.backend.arrivals.domain.ArrivalSource;
import com.gustler.backend.arrivals.domain.ArrivalStore;
import com.gustler.backend.quota.api.ArrivalCallQuota;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ArrivalCollectingPollTest {
    @Test
    void 접수와_수집중_재조회는_1초뒤를_안내하고_같은_작업을_공유한다() {
        Instant base = Instant.parse("2026-10-19T03:00:00Z");
        AtomicLong seconds = new AtomicLong();
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return base.plusSeconds(seconds.get()); }
        };
        Queue<Runnable> jobs = new ArrayDeque<>();
        var store = mock(ArrivalStore.class);
        var source = mock(ArrivalSource.class);
        var quota = mock(ArrivalCallQuota.class);
        var coordinator = new ArrivalRefreshCoordinator(store, source, quota, jobs::add,
            ArrivalCollectionPolicy.defaults(), clock, () -> Duration.ofSeconds(seconds.get()).toNanos());
        seconds.set(40);
        ArrivalRefresh first = coordinator.request("206000637");
        assertThat(first.status()).isEqualTo(ArrivalRefresh.Status.ACCEPTED);
        assertThat(first.suggestedRefreshAt()).isEqualTo(base.plusSeconds(41));
        for (int index = 41; index <= 43; index++) {
            seconds.set(index);
            ArrivalRefresh next = coordinator.request("206000637");
            assertThat(next.status()).isEqualTo(ArrivalRefresh.Status.IN_PROGRESS);
            assertThat(next.reason()).isEqualTo("COLLECTING");
            assertThat(next.suggestedRefreshAt()).isEqualTo(base.plusSeconds(index + 1));
        }
        assertThat(jobs).hasSize(1);
        verifyNoInteractions(store, source, quota);
    }
}
