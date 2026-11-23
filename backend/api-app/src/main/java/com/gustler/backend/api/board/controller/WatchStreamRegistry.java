package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.route.RouteId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

final class WatchStreamRegistry implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WatchStreamRegistry.class);

    /** 서버가 내려가며 연결을 한꺼번에 닫아도 화면들이 같은 순간에 다시 붙지 않게, 재연결 대기를 연결마다 이 범위에서 고른다. */
    private static final long RECONNECT_DELAY_MIN_MILLIS = 3_000;
    private static final long RECONNECT_DELAY_MAX_MILLIS = 10_000;

    private static final State CLOSED = new State.Closed();

    private final WatchStreamSettings settings;
    /** {@code TaskScheduler} 빈으로 등록하면 Spring 이 모든 {@code @Scheduled} 작업을 이 스레드로 옮기므로 빈이 아닌 필드다. */
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final AtomicReference<State> state = new AtomicReference<>(new State.Accepting(List.of()));

    WatchStreamRegistry(WatchStreamSettings settings, MeterRegistry meters) {
        this.settings = settings;
        Gauge.builder("salmonbus.watch.stream.connections", this, WatchStreamRegistry::connections)
            .description("열려 있는 판정보드 신호 스트림 수")
            .register(meters);
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("watch-stream-");
        // 멈출 때 돌고 있는 heartbeat에 인터럽트를 걸지 않는다. 살아 있는 연결에 쓰던 중일 수 있다.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
    }

    void start() {
        scheduler.initialize();
        scheduler.scheduleWithFixedDelay(this::ping, settings.heartbeatInterval());
    }

    Optional<SseEmitter> open(RouteId routeId) {
        Subscriber subscriber = Subscriber.greeted(routeId, settings.timeout());
        // SseEmitter 는 콜백 목록을 잠금 없이 돌므로 콜백은 스트림을 돌려주기 전에 건다.
        subscriber.emitter().onCompletion(() -> remove(subscriber));
        subscriber.emitter().onError(failure -> remove(subscriber));
        return admit(subscriber) ? Optional.of(subscriber.emitter()) : Optional.empty();
    }

    /**
     * 주석이 아니라 이벤트다. EventSource 는 주석을 화면 코드에 넘기지 않는다.
     *
     * <p>스레드 하나가 연결을 차례로 돌며 쓴다. 기본값이면 연결 하나가 수명 동안 받는 양이 4KB 를 넘지 않아 소켓 송신 버퍼에 다
     * 들어가므로, 읽지 않는 상대 하나 때문에 쓰기가 멈춰 다른 연결이 밀리지 않는다.
     */
    void ping() {
        if (state.get() instanceof State.Accepting open) {
            for (Subscriber subscriber : open.subscribers()) {
                send(subscriber, SseEmitter.event().name("ping").data("1"));
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
        for (Subscriber subscriber : open.subscribers()) {
            try {
                subscriber.emitter().complete();
            } catch (RuntimeException failure) {
                LOGGER.debug("event=watch_stream_close status=FAILED routeId={} cause={}",
                    subscriber.routeId().value(), failure.getClass().getSimpleName());
            }
        }
        LOGGER.info("event=watch_stream_close status=COMPLETED connections={}", open.subscribers().size());
    }

    private boolean admit(Subscriber subscriber) {
        while (true) {
            State current = state.get();
            if (!(current instanceof State.Accepting open) || open.subscribers().size() >= settings.maxConnections()) {
                return false;
            }
            if (state.compareAndSet(current, open.with(subscriber))) {
                return true;
            }
        }
    }

    private void remove(Subscriber subscriber) {
        state.updateAndGet(current -> current instanceof State.Accepting open ? open.without(subscriber) : current);
    }

    /**
     * 실패한 연결은 목록에서 빼기만 한다. 끊긴 연결은 톰캣이 오류 알림으로 끝낸다. Spring 7 은 전송에 실패한 스트림을 막지 않아서,
     * 여기서 {@code complete()} 를 부르면 톰캣의 오류 디스패치와 경쟁하고 {@code completeWithError()} 를 부르면
     * {@code ApiExceptionHandler} 가 500 경고를 남긴다.
     */
    private void send(Subscriber subscriber, SseEventBuilder frame) {
        try {
            subscriber.emitter().send(frame);
        } catch (IOException | IllegalStateException failure) {
            // send 가 던지는 것은 이 둘뿐이다. 끊긴 직후에는 IOException, 요청이 끝난 뒤에는 IllegalStateException 이다.
            remove(subscriber);
            LOGGER.debug("event=watch_stream_send status=FAILED routeId={} cause={}",
                subscriber.routeId().value(), failure.getClass().getSimpleName());
        }
    }

    private int connections() {
        return state.get() instanceof State.Accepting open ? open.subscribers().size() : 0;
    }

    private record Subscriber(RouteId routeId, SseEmitter emitter) {
        /**
         * 첫 쓰기({@code retry} 와 {@code : connected} 주석)를 쌓은 스트림을 만든다. Spring 7 은 응답 헤더를 첫 쓰기와 함께 보내므로,
         * 쌓지 않으면 헤더가 첫 ping 까지 나가지 않는다. 목록에 들기 전에 쌓아야 ping 이 첫 쓰기보다 앞서지 않는다.
         */
        static Subscriber greeted(RouteId routeId, Duration timeout) {
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
            } catch (IOException unreachable) {
                // 응답에 붙기 전의 쓰기는 쌓이기만 하고 소켓에 닿지 않는다.
                throw new UncheckedIOException(unreachable);
            }
            return new Subscriber(routeId, emitter);
        }
    }

    private sealed interface State {
        record Accepting(List<Subscriber> subscribers) implements State {
            Accepting with(Subscriber added) {
                return new Accepting(Stream.concat(subscribers.stream(), Stream.of(added)).toList());
            }

            Accepting without(Subscriber removed) {
                return new Accepting(subscribers.stream().filter(subscriber -> subscriber != removed).toList());
            }
        }

        record Closed() implements State {
        }
    }
}
