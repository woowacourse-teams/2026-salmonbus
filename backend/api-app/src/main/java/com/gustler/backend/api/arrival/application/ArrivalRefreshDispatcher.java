package com.gustler.backend.api.arrival.application;

import com.gustler.backend.arrival.contract.ArrivalRefresh;
import com.gustler.backend.arrival.contract.ArrivalRefresh.Status;
import com.gustler.backend.arrival.contract.ArrivalSnapshot;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 쓸 수 있는 수신본이 있는데 갱신 시점이 지난 조회의 Worker 갱신 요청을 요청 스레드 밖에서 보낸다.
 *
 * <p>응답에 싣는 갱신 상태는 Worker가 실제로 준 답이거나, 갱신을 요청하지 않았을 때와 같은 값(IDLE, 사유 없음, 수신본의
 * {@code refreshAfterAt})뿐이다. API가 상태나 재조회 시각을 지어내지 않는다.
 * <ul>
 *     <li>정류장마다 마지막 Worker 답을 기억한다. 그 답의 재조회 권장 시각 전이면 다시 보내지 않고 그 답을 그대로 싣는다.</li>
 *     <li>권장 시각이 지났으면 다시 보낸다. 새 답은 기다리지 않으므로 이번 응답에는 기억한 답을 권장 시각까지 그대로 싣는다.
 *     기억한 답이 지금 수신본을 받기 전에 받은 답이면 지금 수신본의 갱신과 관계없으므로 요청하지 않았을 때의 값을 싣는다.</li>
 *     <li>같은 정류장에 진행 중인 요청이 있거나 실행기에 자리가 없어 보내지 못했으면 위와 같이 기억한 답이나 요청하지 않았을 때의
 *     값을 싣는다. 보내지 못한 요청은 다음 조회에서 다시 시도한다.</li>
 * </ul>
 * 답을 기억한 정류장이 상한을 넘으면 진행 중이 아니고 권장 시각이 지난 답부터 지운다.
 *
 * <p>{@link #synchronous}로 만들면 기억·중복 제거 없이 요청 스레드에서 바로 보내고 그 답을 그대로 돌려준다.
 */
public final class ArrivalRefreshDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArrivalRefreshDispatcher.class);
    private static final Duration[] LATENCY_BUCKETS = {
        Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50), Duration.ofMillis(100),
        Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1)
    };

    private final ArrivalRefreshGateway gateway;
    private final Executor executor;
    private final Clock clock;
    private final int maximumStations;
    private final boolean async;
    private final MeterRegistry meters;
    private final Map<String, Counter> outcomes;
    private final Map<String, Entry> stations = new ConcurrentHashMap<>();

    private ArrivalRefreshDispatcher(
        ArrivalRefreshGateway gateway,
        Executor executor,
        Clock clock,
        final int maximumStations,
        final boolean async,
        MeterRegistry meters
    ) {
        this.gateway = gateway;
        this.executor = executor;
        this.clock = clock;
        this.maximumStations = maximumStations;
        this.async = async;
        this.meters = meters;
        this.outcomes = Map.of(
            "submitted", outcomeCounter(meters, "submitted"),
            "reused", outcomeCounter(meters, "reused"),
            "in_flight", outcomeCounter(meters, "in_flight"),
            "rejected", outcomeCounter(meters, "rejected")
        );
    }

    private static Counter outcomeCounter(MeterRegistry meters, String outcome) {
        return Counter.builder("salmonbus.arrival.refresh.dispatch")
            .description("갱신 시점이 지난 도착정보 조회의 비동기 갱신 처리 결과")
            .tag("outcome", outcome)
            .register(meters);
    }

    /**
     * 요청 스레드 밖에서 보내는 실행기를 만든다. {@code maximumStations}는 답을 기억할 정류장 수 상한이다.
     */
    public static ArrivalRefreshDispatcher async(
        ArrivalRefreshGateway gateway,
        Executor executor,
        Clock clock,
        final int maximumStations,
        MeterRegistry meters
    ) {
        if (maximumStations < 1) {
            throw new IllegalArgumentException("기억할 정류장 수는 1 이상이어야 한다");
        }
        return new ArrivalRefreshDispatcher(gateway, executor, clock, maximumStations, true, meters);
    }

    /**
     * 요청 스레드에서 바로 보내고 Worker 답을 그대로 돌려주는 실행기를 만든다.
     */
    public static ArrivalRefreshDispatcher synchronous(ArrivalRefreshGateway gateway, Clock clock, MeterRegistry meters) {
        return new ArrivalRefreshDispatcher(gateway, Runnable::run, clock, 1, false, meters);
    }

    /**
     * 정류장 갱신을 요청하고 응답에 실을 갱신 상태를 돌려준다. 비동기면 Worker 응답을 기다리지 않는다.
     *
     * @param snapshot 응답에 쓸 지금 수신본. 기억한 답이 이 수신본의 갱신에 대한 답인지 가르고, 요청하지 않았을 때의 값을 만든다
     */
    public ArrivalRefresh request(String stationId, ArrivalSnapshot snapshot) {
        if (!async) {
            final long startedAt = System.nanoTime();
            ArrivalRefresh reply = gateway.request(stationId);
            record("immediate", reply, startedAt);
            return reply;
        }
        Instant now = clock.instant();
        AtomicReference<Entry> sending = new AtomicReference<>();
        Entry entry = stations.compute(stationId, (key, current) -> {
            if (current != null && (current.inFlight() || current.answersAt(now))) {
                return current;
            }
            Entry next = Entry.sending(current);
            sending.set(next);
            return next;
        });
        if (sending.get() == null) {
            count(outcomeOf(entry));
            return replyOf(entry, snapshot);
        }
        trimIfFull(now);
        CompletableFuture<ArrivalRefresh> sent = new CompletableFuture<>();
        try {
            executor.execute(() -> send(stationId, entry, sent));
        } catch (RejectedExecutionException full) {
            restore(stationId, entry);
            count("rejected");
            LOGGER.warn("도착정보 갱신 요청 실행기에 자리가 없어 이번 조회에서는 보내지 않았습니다.");
            return replyOf(entry, snapshot);
        }
        count("submitted");
        if (sent.isDone() && !sent.isCompletedExceptionally()) {
            return sent.join();
        }
        return replyOf(entry, snapshot);
    }

    private void send(String stationId, Entry sending, CompletableFuture<ArrivalRefresh> sent) {
        final long startedAt = System.nanoTime();
        try {
            ArrivalRefresh reply = gateway.request(stationId);
            record("async", reply, startedAt);
            if (reply.status() == Status.UNAVAILABLE) {
                LOGGER.warn("도착정보 비동기 갱신 요청을 Worker가 처리하지 못했습니다. reason={}", reply.reason());
            }
            stations.put(stationId, Entry.answered(reply, clock.instant()));
            sent.complete(reply);
        } catch (RuntimeException failure) {
            LOGGER.warn("도착정보 비동기 갱신 요청에 실패했습니다. exceptionType={}", failure.getClass().getSimpleName());
            sent.completeExceptionally(failure);
        } finally {
            if (!sent.isDone() || sent.isCompletedExceptionally()) {
                restore(stationId, sending);
            }
        }
    }

    private void restore(String stationId, Entry sending) {
        Entry previous = sending.previous();
        if (previous == null) {
            stations.remove(stationId, sending);
            return;
        }
        stations.replace(stationId, sending, previous);
    }

    private void record(String mode, ArrivalRefresh reply, final long startedAt) {
        String status = "NONE";
        if (reply != null && reply.status() != null) {
            status = reply.status().name();
        }
        Timer.builder("salmonbus.arrival.refresh.request")
            .description("API가 Worker에 보낸 도착정보 갱신 요청 시간")
            .tag("mode", mode)
            .tag("status", status)
            .serviceLevelObjectives(LATENCY_BUCKETS)
            .register(meters)
            .record(Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private void trimIfFull(Instant now) {
        if (stations.size() <= maximumStations) {
            return;
        }
        stations.entrySet().removeIf(entry -> !entry.getValue().inFlight() && !entry.getValue().answersAt(now));
    }

    private static ArrivalRefresh replyOf(Entry entry, ArrivalSnapshot snapshot) {
        Entry answer = entry.latestAnswer();
        if (answer != null && !answer.answeredAt().isBefore(snapshot.receivedAt())) {
            return answer.reply();
        }
        return new ArrivalRefresh(Status.IDLE, null, snapshot.refreshAfterAt());
    }

    private static String outcomeOf(Entry entry) {
        if (entry.inFlight()) {
            return "in_flight";
        }
        return "reused";
    }

    private void count(String outcome) {
        outcomes.get(outcome).increment();
    }

    /**
     * 정류장의 마지막 Worker 답 또는 진행 중 요청이다. 진행 중이면 {@code previous}가 보내기 전에 기억한 답(없으면 null)이고,
     * 답이면 {@code reply}와 받은 시각이 있다.
     */
    private record Entry(ArrivalRefresh reply, Instant answeredAt, Entry previous, boolean inFlight) {

        static Entry sending(Entry current) {
            Entry previous = null;
            if (current != null) {
                previous = current.latestAnswer();
            }
            return new Entry(null, null, previous, true);
        }

        static Entry answered(ArrivalRefresh reply, Instant answeredAt) {
            return new Entry(reply, answeredAt, null, false);
        }

        Entry latestAnswer() {
            if (inFlight) {
                return previous;
            }
            return this;
        }

        boolean answersAt(Instant now) {
            return !inFlight && reply.suggestedRefreshAt() != null && now.isBefore(reply.suggestedRefreshAt());
        }
    }
}
