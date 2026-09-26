package com.gustler.backend.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionSystemException;

@ExtendWith(MockitoExtension.class)
class StopDemandStatisticsJobLoggingTest {
    private static final Instant COMPUTED_AT = Instant.parse("2026-09-25T00:00:00Z");

    @Mock private RouteVersionRepository routes;
    @Mock private StopDemandStatisticsWriter writer;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(StopDemandStatisticsJob.class);
    private StopDemandStatisticsJob job;

    @BeforeEach
    void 로그_수집을_시작한다() {
        logs.start();
        logger.addAppender(logs);
        job = new StopDemandStatisticsJob(routes, writer,
            Clock.fixed(COMPUTED_AT, ZoneId.of("Asia/Seoul")));
    }

    @AfterEach
    void 로그_수집을_종료한다() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void writer가_반환한_뒤에만_KST_기준시각과_완료를_기록한다() {
        when(routes.findActiveVersionIds()).thenReturn(List.of(1L));
        doAnswer(invocation -> {
            assertThat(messages()).hasSize(1);
            assertThat(messages().getFirst())
                .contains("status=STARTED", "computedAt=2026-09-25T09:00:00+09:00");
            return null;
        }).when(writer).recompute(1L, COMPUTED_AT);

        job.recomputeStopDemand();

        assertThat(messages()).hasSize(2);
        assertThat(messages().get(1)).matches(
            "event=stop_demand_statistics status=COMPLETED routeVersionId=1 "
                + "computedAt=2026-09-25T09:00:00\\+09:00 durationMs=\\d+");
    }

    @Test
    void writer의_커밋_예외는_실패만_기록하고_그대로_전파한다() {
        when(routes.findActiveVersionIds()).thenReturn(List.of(1L));
        var failure = new TransactionSystemException("commit failed");
        doThrow(failure).when(writer).recompute(1L, COMPUTED_AT);

        assertThatThrownBy(job::recomputeStopDemand).isSameAs(failure);

        assertThat(messages()).hasSize(2);
        assertThat(messages().get(1)).contains("status=FAILED", "routeVersionId=1",
            "computedAt=2026-09-25T09:00:00+09:00", "durationMs=",
            "exceptionType=TransactionSystemException").doesNotContain("COMPLETED");
        assertThat(logs.list.get(1).getThrowableProxy()).isNull();
    }

    @Test
    void 활성_노선이_없으면_집계_로그를_남기지_않는다() {
        when(routes.findActiveVersionIds()).thenReturn(List.of());

        job.recomputeStopDemand();

        assertThat(messages()).isEmpty();
        verifyNoInteractions(writer);
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
