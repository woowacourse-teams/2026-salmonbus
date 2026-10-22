package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.diagnostics.WorkerOperationLog;
import com.gustler.backend.observations.application.ObservationCollector;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.util.List;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@ExtendWith(MockitoExtension.class)
class CollectionSchedulerTest {
    private static final String ROUTE_3330 = "204000057";
    private static final String ROUTE_9100 = "234000016";

    @Mock
    private CollectionProperties properties;

    @Mock
    private ObservationCollector collector;

    @InjectMocks
    private CollectionScheduler scheduler;

    @Test
    void 설정에_적힌_노선을_모두_돈다() {
        // given
        given(properties.routeIds()).willReturn(List.of(ROUTE_3330, ROUTE_9100));

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).should().collectOnce(ROUTE_9100);
    }

    @Test
    void 한_노선이_터져도_다음_노선을_이어서_돈다() {
        // given
        given(properties.routeIds()).willReturn(List.of(ROUTE_3330, ROUTE_9100));
        willThrow(new IllegalStateException("상류가 터졌다")).given(collector).collectOnce(ROUTE_3330);

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).should().collectOnce(ROUTE_9100);
    }

    @Test
    void 수집_실패는_민감한_예외_원문_없이_한_번만_기록한다() {
        // given
        given(properties.routeIds()).willReturn(List.of(ROUTE_3330));
        willThrow(new IllegalStateException("private-upstream-response",
            new IllegalArgumentException("private-request-token")))
            .given(collector).collectOnce(ROUTE_3330);
        Logger operations = (Logger) LoggerFactory.getLogger(WorkerOperationLog.class);
        Logger scheduling = (Logger) LoggerFactory.getLogger(CollectionScheduler.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        operations.addAppender(events);
        scheduling.addAppender(events);

        try {

            // when
            scheduler.collectAllRoutes();

            // then
            List<ILoggingEvent> failures = events.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .toList();
            assertThat(failures).singleElement().satisfies(event -> {
                try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
                    softly.assertThat(event.getFormattedMessage()).contains(
                        "event=worker_operation", "status=FAILED", "operation=collection_route", "route=" + ROUTE_3330);
                    softly.assertThat(event.getFormattedMessage()).doesNotContain(
                        "private-upstream-response", "private-request-token");
                    softly.assertThat(event.getThrowableProxy()).isNull();
                }
            });
        } finally {
            operations.detachAppender(events);
            scheduling.detachAppender(events);
            events.stop();
        }
    }

    @Test
    void 돌_노선이_없으면_아무것도_부르지_않는다() {
        // given
        given(properties.routeIds()).willReturn(List.of());

        // when
        scheduler.collectAllRoutes();

        // then
        then(collector).shouldHaveNoInteractions();
    }
}
