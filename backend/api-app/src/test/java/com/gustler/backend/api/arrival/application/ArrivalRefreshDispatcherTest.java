package com.gustler.backend.api.arrival.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/**
 * 비동기 갱신이 응답에 싣는 갱신 상태를 고정한다. 상태는 Worker가 실제로 준 답이거나 갱신을 요청하지 않았을 때의 값이어야 한다.
 */
class ArrivalRefreshDispatcherTest {
    private static final Instant RECEIVED = Instant.parse("2026-11-13T03:00:00Z");
    private static final Instant NOW = RECEIVED.plusSeconds(45);
    private static final String STATION = "206000637";
    private static final ArrivalSnapshot SNAPSHOT = snapshot(RECEIVED);
    private static final ArrivalRefresh UNREQUESTED = new ArrivalRefresh(Status.IDLE, null, RECEIVED.plusSeconds(40));

    private final ArrivalRefreshGateway gateway = mock(ArrivalRefreshGateway.class);
    private final ManualExecutor executor = new ManualExecutor();
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ArrivalRefreshDispatcher dispatcher =
        ArrivalRefreshDispatcher.async(gateway, executor, clock, 16, meters);

    private static ArrivalSnapshot snapshot(Instant receivedAt) {
        return new ArrivalSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"), STATION, receivedAt,
            receivedAt.plusSeconds(40), receivedAt.plusSeconds(90), "20261113120000", List.of());
    }

    private void answer(ArrivalRefresh reply) {
        when(gateway.request(STATION)).thenReturn(reply);
        dispatcher.request(STATION, SNAPSHOT);
        executor.runAll();
    }

    @Test
    void 기억한_답이_없으면_Worker를_기다리지_않고_요청하지_않았을_때의_값을_싣는다() {
        // when
        ArrivalRefresh refresh = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(refresh).isEqualTo(UNREQUESTED);
        assertThat(executor.pending()).isEqualTo(1);
        verifyNoInteractions(gateway);
    }

    @Test
    void Worker_답은_다음_조회부터_그대로_싣는다() {
        // given
        ArrivalRefresh accepted = new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(1));
        answer(accepted);

        // when
        ArrivalRefresh refresh = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(refresh).isEqualTo(accepted);
        assertThat(executor.pending()).isZero();
    }

    @Test
    void 진행_중인_요청이_있으면_다시_보내지_않고_요청하지_않았을_때의_값을_싣는다() {
        // given
        dispatcher.request(STATION, SNAPSHOT);

        // when
        ArrivalRefresh second = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(second).isEqualTo(UNREQUESTED);
        assertThat(executor.pending()).isEqualTo(1);
        assertThat(meters.counter("salmonbus.arrival.refresh.dispatch", "outcome", "in_flight").count()).isEqualTo(1.0);
    }

    @Test
    void Worker가_제한으로_답하면_권장_시각까지_그_답을_싣고_다시_보내지_않는다() {
        // given
        ArrivalRefresh limited = new ArrivalRefresh(Status.DEFERRED, "RATE_LIMITED", NOW.plusSeconds(30));
        answer(limited);
        clock.advance(Duration.ofSeconds(10));

        // when
        ArrivalRefresh reused = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(reused).isEqualTo(limited);
        assertThat(executor.pending()).isZero();
        verify(gateway, times(1)).request(STATION);
    }

    @Test
    void Worker가_실패로_답하는_동안에는_다시_보내도_실패를_그대로_싣고_수집_중을_내지_않는다() {
        // given
        ArrivalRefresh failed = new ArrivalRefresh(Status.UNAVAILABLE, "WORKER_UNAVAILABLE", NOW.plusSeconds(2));
        answer(failed);
        clock.advance(Duration.ofSeconds(2));

        // when
        ArrivalRefresh resent = dispatcher.request(STATION, SNAPSHOT);
        ArrivalRefresh whileSending = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(resent).isEqualTo(failed);
        assertThat(whileSending).isEqualTo(failed);
        assertThat(executor.pending()).isEqualTo(1);
    }

    @Test
    void 지금_수신본을_받기_전의_답은_싣지_않는다() {
        // given
        answer(new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(1)));
        clock.advance(Duration.ofMinutes(10));
        ArrivalSnapshot newer = snapshot(NOW.plusSeconds(5));

        // when
        ArrivalRefresh refresh = dispatcher.request(STATION, newer);

        // then
        assertThat(refresh).isEqualTo(new ArrivalRefresh(Status.IDLE, null, newer.refreshAfterAt()));
        assertThat(executor.pending()).isEqualTo(1);
    }

    @Test
    void 실행기에_자리가_없으면_보내지_않은_것을_그대로_내고_다음_조회에서_다시_시도한다() {
        // given
        executor.rejecting = true;
        ArrivalRefresh rejected = dispatcher.request(STATION, SNAPSHOT);
        executor.rejecting = false;

        // when
        dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(rejected).isEqualTo(UNREQUESTED);
        assertThat(executor.pending()).isEqualTo(1);
        assertThat(meters.counter("salmonbus.arrival.refresh.dispatch", "outcome", "rejected").count()).isEqualTo(1.0);
    }

    @Test
    void 실행기에_자리가_없어도_기억한_답은_지우지_않는다() {
        // given
        ArrivalRefresh limited = new ArrivalRefresh(Status.DEFERRED, "RATE_LIMITED", NOW.plusSeconds(1));
        answer(limited);
        clock.advance(Duration.ofSeconds(1));
        executor.rejecting = true;

        // when
        ArrivalRefresh first = dispatcher.request(STATION, SNAPSHOT);
        ArrivalRefresh second = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(first).isEqualTo(limited);
        assertThat(second).isEqualTo(limited);
    }

    @Test
    void 요청이_예외로_끝나면_기억한_답을_되살리고_다음_조회에서_다시_보낸다() {
        // given
        ArrivalRefresh limited = new ArrivalRefresh(Status.DEFERRED, "BUSY", NOW.plusSeconds(1));
        answer(limited);
        clock.advance(Duration.ofSeconds(1));
        when(gateway.request(STATION)).thenThrow(new IllegalStateException("worker"));
        dispatcher.request(STATION, SNAPSHOT);
        executor.runAll();

        // when
        ArrivalRefresh refresh = dispatcher.request(STATION, SNAPSHOT);

        // then
        assertThat(refresh).isEqualTo(limited);
        assertThat(executor.pending()).isEqualTo(1);
    }

    @Test
    void 실행기가_바로_실행하면_그_Worker_답을_싣는다() {
        // given
        ArrivalRefresh accepted = new ArrivalRefresh(Status.ACCEPTED, "COLLECTING", NOW.plusSeconds(1));
        when(gateway.request(STATION)).thenReturn(accepted);
        ArrivalRefreshDispatcher inline = ArrivalRefreshDispatcher.async(gateway, Runnable::run, clock, 16, meters);

        // when
        ArrivalRefresh refresh = inline.request(STATION, SNAPSHOT);

        // then
        assertThat(refresh).isEqualTo(accepted);
    }

    @Test
    void 동기_실행기는_요청_스레드에서_보내고_Worker_답을_그대로_돌려준다() {
        // given
        ArrivalRefresh reply = new ArrivalRefresh(Status.UNAVAILABLE, "WORKER_UNAVAILABLE", NOW.minusSeconds(1));
        when(gateway.request(STATION)).thenReturn(reply);
        ArrivalRefreshDispatcher synchronous = ArrivalRefreshDispatcher.synchronous(gateway, clock, meters);

        // when
        ArrivalRefresh refresh = synchronous.request(STATION, SNAPSHOT);

        // then
        assertThat(refresh).isEqualTo(reply);
        assertThat(meters.timer("salmonbus.arrival.refresh.request", "mode", "immediate", "status", "UNAVAILABLE").count())
            .isEqualTo(1);
    }

    private static final class ManualExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private boolean rejecting;

        @Override
        public void execute(Runnable command) {
            if (rejecting) {
                throw new RejectedExecutionException("full");
            }
            tasks.add(command);
        }

        int pending() {
            return tasks.size();
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.poll().run();
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
