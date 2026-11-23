package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.board.application.WatchSubscriptions;
import com.gustler.backend.api.route.RouteId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

final class WatchStreamRegistry implements WatchSubscriptions, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WatchStreamRegistry.class);

    /** 서버가 내려가며 연결을 한꺼번에 닫아도 화면들이 같은 순간에 다시 붙지 않게, 재연결 대기를 연결마다 이 범위에서 고른다. */
    private static final long RECONNECT_DELAY_MIN_MILLIS = 3_000;
    private static final long RECONNECT_DELAY_MAX_MILLIS = 10_000;

    private static final State CLOSED = new State.Closed();

    private final WatchStreamSettings settings;
    private final Counter signalsOnConnect;
    private final Counter signalsOnChange;
    /** {@code TaskScheduler} 빈으로 등록하면 Spring 이 모든 {@code @Scheduled} 작업을 이 스레드로 옮기므로 빈이 아닌 필드다. */
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final AtomicReference<State> state = new AtomicReference<>(new State.Accepting(Map.of()));

    WatchStreamRegistry(WatchStreamSettings settings, MeterRegistry meters) {
        this.settings = settings;
        Gauge.builder("salmonbus.watch.stream.connections", this, WatchStreamRegistry::connections)
            .description("열려 있는 판정보드 신호 스트림 수")
            .register(meters);
        this.signalsOnConnect = signals(meters, "connect");
        this.signalsOnChange = signals(meters, "change");
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("watch-stream-");
        // 멈출 때 돌고 있는 heartbeat에 인터럽트를 걸지 않는다. 살아 있는 연결에 쓰던 중일 수 있다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
    }

    private static Counter signals(MeterRegistry meters, String cause) {
        return Counter.builder("salmonbus.watch.stream.signals")
            .description("연결에 보낸 observation 이벤트 수. cause 는 연결 직후에 보낸 것(connect)과 달라진 관측을 알린 것(change)이다")
            .tag("cause", cause)
            .register(meters);
    }

    void start() {
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::ping, settings.heartbeatInterval());
    }

    Optional<SseEmitter> open(RouteId routeId) {
        while (true) {
            State current = state.get();
            if (!(current instanceof State.Accepting open) || open.connections() >= settings.maxConnections()) {
                return Optional.empty();
            }
            Optional<OffsetDateTime> told = open.told(routeId);
            Subscriber subscriber = Subscriber.greeted(routeId, settings.timeout(), told);
            // SseEmitter 는 콜백 목록을 잠금 없이 돌므로 콜백은 스트림을 돌려주기 전에 건다.
            subscriber.emitter().onCompletion(() -> remove(subscriber));
            subscriber.emitter().onError(failure -> remove(subscriber));
            if (state.compareAndSet(current, open.with(subscriber))) {
                if (told.isPresent()) {
                    signalsOnConnect.increment();
                }
                return Optional.of(subscriber.emitter());
            }
        }
    }

    /**
     * 주석이 아니라 이벤트다. EventSource 는 주석을 화면 코드에 넘기지 않는다.
     *
     * <p>스레드 하나가 연결을 차례로 돌며 쓴다. 기본값이면 연결 하나가 수명 동안 받는 양은 ping 3KB 에, 관측이 10초마다 바뀌어도
     * 신호 15KB 를 더해 20KB 를 넘지 않는다. 보내는 쪽과 받는 쪽의 소켓 버퍼를 합친 크기보다 작아서, 읽지 않는 상대 하나 때문에
     * 쓰기가 멈춰 다른 연결이 밀리지 않는다.
     */
    void ping() {
        if (state.get() instanceof State.Accepting open) {
            for (Subscriber subscriber : open.subscribers()) {
                send(subscriber, SseEmitter.event().name("ping").data("1"));
            }
        }
    }

    @Override
    public Set<RouteId> watchedRoutes() {
        return state.get() instanceof State.Accepting open ? open.watchers().keySet() : Set.of();
    }

    @Override
    public void observed(RouteId routeId, OffsetDateTime observedAt) {
        while (true) {
            State current = state.get();
            if (!(current instanceof State.Accepting open)) {
                return;
            }
            Watchers watchers = open.watchers().get(routeId);
            if (watchers == null || watchers.alreadyTold(observedAt)) {
                return;
            }
            if (state.compareAndSet(current, open.observing(routeId, observedAt))) {
                for (Subscriber subscriber : watchers.subscribers()) {
                    // 이벤트 빌더는 연결마다 새로 만든다. 한 빌더를 두 번 쓰면 두 번째 이벤트에 빈 줄이 하나 더 붙는다.
                    if (send(subscriber, observation(observedAt))) {
                        signalsOnChange.increment();
                    }
                }
                return;
            }
        }
    }

    /**
     * {@code ContextClosedEvent} 에서 닫는다. graceful shutdown 은 열린 요청이 끝나기를 기다리는데 스트림은 스스로 끝나지
     * 않는다. 소멸 콜백은 그 기다림이 끝난 뒤에야 불리므로, 소멸 콜백에서만 닫으면 서버가 제한 시간(운영 30초)을 다 쓰고 내려간다.
     *
     * <p>이 이벤트는 두 번 온다. 이 컨텍스트의 것이 graceful shutdown 앞에, 관리 포트 자식 컨텍스트의 것이 그 뒤에 온다. 그 뒤
     * 소멸 콜백으로 한 번 더 불린다. 소멸 콜백은 기동에 실패해 이벤트가 나가지 않았을 때 heartbeat 스레드를 멈추는 길이기도 하다.
     */
    @Override
    @EventListener(ContextClosedEvent.class)
    public void close() {
        if (!(state.getAndSet(CLOSED) instanceof State.Accepting open)) {
            return;
        }
        scheduler.shutdown();
        List<Subscriber> subscribers = open.subscribers();
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException failure) {
                LOGGER.debug("event=watch_stream_close status=FAILED routeId={} cause={}",
                    subscriber.routeId().value(), failure.getClass().getSimpleName());
            }
        }
        LOGGER.info("event=watch_stream_close status=COMPLETED connections={}", subscribers.size());
    }

    private void remove(Subscriber subscriber) {
        state.updateAndGet(current -> current instanceof State.Accepting open ? open.without(subscriber) : current);
    }

    /**
     * 실패한 연결은 목록에서 빼기만 한다. 끊긴 연결은 톰캣이 오류 알림으로 끝낸다. Spring 7 은 전송에 실패한 스트림을 막지 않아서,
     * 여기서 {@code complete()} 를 부르면 톰캣의 오류 디스패치와 경쟁하고 {@code completeWithError()} 를 부르면
     * {@code ApiExceptionHandler} 가 500 경고를 남긴다.
     */
    private boolean send(Subscriber subscriber, SseEventBuilder frame) {
        try {
            subscriber.emitter().send(frame);
            return true;
        } catch (IOException | IllegalStateException failure) {
            // send 가 던지는 것은 이 둘뿐이다. 끊긴 직후에는 IOException, 요청이 끝난 뒤에는 IllegalStateException 이다.
            remove(subscriber);
            LOGGER.debug("event=watch_stream_send status=FAILED routeId={} cause={}",
                subscriber.routeId().value(), failure.getClass().getSimpleName());
            return false;
        }
    }

    private int connections() {
        return state.get() instanceof State.Accepting open ? open.connections() : 0;
    }

    /**
     * {@code /watch} 본문을 쓰는 변환기로 쓴다. 그래야 {@code observedAt} 이 그 본문의 값과 글자까지 같다.
     * {@code OffsetDateTime.toString()} 은 초가 0 이면 초를 빼므로 직접 문자열로 만들면 달라진다.
     */
    private static SseEventBuilder observation(OffsetDateTime observedAt) {
        return SseEmitter.event().name("observation").data(new Signal(observedAt), MediaType.APPLICATION_JSON);
    }

    private record Signal(OffsetDateTime observedAt) {
    }

    private record Subscriber(RouteId routeId, SseEmitter emitter) {
        /**
         * 첫 쓰기({@code retry} 와 {@code : connected} 주석, 알려 둔 관측이 있으면 그 {@code observation})를 쌓은 스트림을 만든다.
         * Spring 7 은 응답 헤더를 첫 쓰기와 함께 보내므로, 쌓지 않으면 헤더가 첫 ping 까지 나가지 않는다. 목록에 들기 전에 쌓아야
         * ping 이나 알림이 첫 쓰기보다 앞서지 않는다.
         */
        static Subscriber greeted(RouteId routeId, Duration timeout, Optional<OffsetDateTime> told) {
            // 수명을 주지 않으면 톰캣의 비동기 기본 제한 30초에 끊긴다.
            SseEmitter emitter = new SseEmitter(timeout.toMillis());
            // 스스로 끝내지 않으면 AsyncRequestTimeoutException 이 ApiExceptionHandler 에 닿아 503 경고를 남긴다.
            emitter.onTimeout(emitter::complete);
            try {
                // 주석 앞의 공백이 있어야 ": connected" 로 나간다.
                emitter.send(SseEmitter.event()
                    .reconnectTime(ThreadLocalRandom.current()
                        .nextLong(RECONNECT_DELAY_MIN_MILLIS, RECONNECT_DELAY_MAX_MILLIS + 1))
                    .comment(" connected"));
                if (told.isPresent()) {
                    emitter.send(observation(told.get()));
                }
            } catch (IOException unreachable) {
                // 응답에 붙기 전의 쓰기는 쌓이기만 하고 소켓에 닿지 않는다.
                throw new UncheckedIOException(unreachable);
            }
            return new Subscriber(routeId, emitter);
        }
    }

    private record Watchers(Optional<OffsetDateTime> told, List<Subscriber> subscribers) {
        Watchers {
            if (subscribers.isEmpty()) {
                throw new IllegalArgumentException("연결이 없는 노선은 두지 않는다");
            }
        }

        boolean alreadyTold(OffsetDateTime candidate) {
            return told.filter(candidate::isEqual).isPresent();
        }

        Watchers with(Subscriber added) {
            return new Watchers(told, Stream.concat(subscribers.stream(), Stream.of(added)).toList());
        }

        Watchers without(Subscriber removed) {
            return new Watchers(told, subscribers.stream().filter(subscriber -> subscriber != removed).toList());
        }
    }

    private sealed interface State {
        record Accepting(Map<RouteId, Watchers> watchers) implements State {
            int connections() {
                return watchers.values().stream().mapToInt(route -> route.subscribers().size()).sum();
            }

            List<Subscriber> subscribers() {
                return watchers.values().stream().flatMap(route -> route.subscribers().stream()).toList();
            }

            Optional<OffsetDateTime> told(RouteId routeId) {
                Watchers route = watchers.get(routeId);
                return route == null ? Optional.empty() : route.told();
            }

            Accepting with(Subscriber added) {
                Map<RouteId, Watchers> next = new HashMap<>(watchers);
                next.merge(added.routeId(), new Watchers(Optional.empty(), List.of(added)),
                    (present, first) -> present.with(added));
                return new Accepting(Map.copyOf(next));
            }

            Accepting without(Subscriber removed) {
                Watchers present = watchers.get(removed.routeId());
                if (present == null || !present.subscribers().contains(removed)) {
                    return this;
                }
                Map<RouteId, Watchers> next = new HashMap<>(watchers);
                if (present.subscribers().size() == 1) {
                    next.remove(removed.routeId());
                } else {
                    next.put(removed.routeId(), present.without(removed));
                }
                return new Accepting(Map.copyOf(next));
            }

            Accepting observing(RouteId routeId, OffsetDateTime observedAt) {
                Map<RouteId, Watchers> next = new HashMap<>(watchers);
                next.computeIfPresent(routeId, (id, route) -> new Watchers(Optional.of(observedAt), route.subscribers()));
                return new Accepting(Map.copyOf(next));
            }
        }

        record Closed() implements State {
        }
    }
}
