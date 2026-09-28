package com.gustler.backend.forecasting.application.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.api.evaluation.SameDayInitializationPolicy;
import com.gustler.backend.forecasting.domain.evaluation.SeoulDay;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.transaction.TransactionSystemException;

class InitializeSameDayOutcomesServiceTest {
    private final SameDayFullOutcomesInitializer initializer = mock(SameDayFullOutcomesInitializer.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-09-28T14:59:50Z");
    private InitializeSameDayOutcomesService service;

    @BeforeEach
    void 준비한다() {
        when(clock.instant()).thenReturn(now);
        when(initializer.activeRouteIds()).thenReturn(List.of(2L, 1L));
        service = new InitializeSameDayOutcomesService(initializer, new SameDayInitializationPolicy(
            Duration.ofSeconds(60), Duration.ofMillis(500), Duration.ofMillis(100)), clock);
    }

    @Test
    void 한_회차에는_노선_하나만_초기화하고_다음_노선으로_차례를_넘긴다() {
        service.initializeNext();
        verify(initializer).initialize(eq(1L), eq(SeoulDay.containing(now)), any());
        verify(initializer, never()).initialize(eq(2L), any(), any());
        service.initializeNext();
        verify(initializer).initialize(eq(2L), eq(SeoulDay.containing(now)), any());
        service.initializeNext();
        verify(initializer, times(2)).initialize(anyLong(), any(), any());
    }

    @Test
    void 초기화_실패_후_재시도_간격_전에는_같은_노선을_다시_실행하지_않는다() {
        when(initializer.activeRouteIds()).thenReturn(List.of(1L));
        // 날짜가 바뀌지 않는 시각에서 재시도 간격만 검증한다.
        Instant start = now.minusSeconds(120);
        when(clock.instant()).thenReturn(start);
        when(initializer.initialize(anyLong(), any(), any())).thenThrow(new IllegalStateException("fixture"));
        assertThatThrownBy(service::initializeNext).isInstanceOf(IllegalStateException.class);
        when(clock.instant()).thenReturn(start.plusSeconds(59));
        service.initializeNext();
        verify(initializer, times(1)).initialize(anyLong(), any(), any());
        when(clock.instant()).thenReturn(start.plusSeconds(60));
        assertThatThrownBy(service::initializeNext).isInstanceOf(IllegalStateException.class);
        verify(initializer, times(2)).initialize(anyLong(), any(), any());
    }

    @Test
    void 잠금_시간초과도_다음_노선의_초기화를_막지_않는다() {
        when(initializer.initialize(eq(1L), any(), any())).thenThrow(new CannotAcquireLockException("fixture"));
        assertThatThrownBy(service::initializeNext).isInstanceOf(CannotAcquireLockException.class);
        service.initializeNext();
        verify(initializer).initialize(eq(2L), eq(SeoulDay.containing(now)), any());
    }

    @Test
    void KST_날짜가_바뀌면_새_날짜의_초기화를_시도한다() {
        when(initializer.activeRouteIds()).thenReturn(List.of(1L));
        service.initializeNext();
        when(clock.instant()).thenReturn(now.plusSeconds(10));
        service.initializeNext();
        verify(initializer).initialize(eq(1L), eq(SeoulDay.containing(now.plusSeconds(10))), any());
        verify(initializer, times(2)).initialize(anyLong(), any(), any());
    }

    @Test
    void 초기화_커밋이_실패하면_성공으로_기록하지_않는다() {
        Logger logger = (Logger) LoggerFactory.getLogger(InitializeSameDayOutcomesService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            when(initializer.initialize(eq(1L), any(), any())).thenAnswer(call -> {
                SameDayInitializationAttempt attempt = call.getArgument(2);
                attempt.awaitingCommit();
                throw new TransactionSystemException("commit failure");
            });
            when(initializer.initialize(eq(2L), any(), any())).thenReturn(true);
            assertThatThrownBy(service::initializeNext).isInstanceOf(TransactionSystemException.class);
            assertThat(events.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).contains("status=FAILED", "failedStage=COMMIT").doesNotContain("status=COMPLETED"));
            events.list.clear();
            service.initializeNext();
            assertThat(events.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).contains("status=COMPLETED", "routeId=2", "outcomeDate=2026-09-28");
            });
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void 실제_초기화와_완료된_집계의_건너뜀을_구분해_기록한다() {
        Logger logger = (Logger) LoggerFactory.getLogger(InitializeSameDayOutcomesService.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            when(initializer.initialize(eq(1L), any(), any())).thenAnswer(call -> {
                SameDayInitializationAttempt attempt = call.getArgument(2);
                attempt.measure(SameDayInitializationAttempt.Stage.SOURCE, () -> 1);
                attempt.awaitingCommit();
                return true;
            });
            when(initializer.initialize(eq(2L), any(), any())).thenAnswer(call -> {
                SameDayInitializationAttempt attempt = call.getArgument(2);
                attempt.awaitingCommit();
                return false;
            });
            service.initializeNext();
            service.initializeNext();
            assertThat(events.list).hasSize(2);
            assertThat(events.list.get(0).getFormattedMessage())
                .contains("status=COMPLETED", "sourceAttempted=true", "startedAt=2026-09-28T23:59:50+09:00");
            assertThat(events.list.get(1).getFormattedMessage())
                .contains("status=SKIPPED", "sourceAttempted=false", "sourceQueryMs=null");
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
