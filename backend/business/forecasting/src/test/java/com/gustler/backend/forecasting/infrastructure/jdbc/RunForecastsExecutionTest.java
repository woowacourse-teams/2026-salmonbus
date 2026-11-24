package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.api.forecast.ForecastLanes;
import com.gustler.backend.forecasting.application.forecast.ForecastBatchProcessor;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchReader;
import com.gustler.backend.forecasting.application.forecast.PendingBatches;
import com.gustler.backend.forecasting.application.forecast.RouteStateRegistry;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.SettlementContextWriter;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class RunForecastsExecutionTest {

    private static final Instant NOW = Instant.parse("2026-11-24T01:00:00Z");
    private static final long VERSION = 7;

    @Test
    void 실패한_배치는_즉시_재시도하지_않고_다음_신호에서_다시_처리한다() {
        // given
        ObservationBatchReader batches = mock(ObservationBatchReader.class);
        given(batches.readBatch(1)).willThrow(new IllegalStateException("read failed")).willReturn(Optional.empty());
        ForecastLanes lanes = mock(ForecastLanes.class);
        given(lanes.submit(anyString(), any())).willAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return true;
        });
        RunForecastsService service = service(lanes, batches, new SimpleMeterRegistry());

        // when
        service.pickUp();

        // then
        verify(batches).readBatch(1);

        // when
        service.batchStored(1, VERSION);

        // then
        verify(batches, times(2)).readBatch(1);
    }

    @Test
    void 등록_오류를_큐_포화로_세지_않고_다음_등록을_막지_않는다() {
        // given
        ObservationBatchReader batches = mock(ObservationBatchReader.class);
        given(batches.readBatch(1)).willReturn(Optional.empty());
        ForecastLanes lanes = mock(ForecastLanes.class);
        given(lanes.submit(anyString(), any()))
            .willThrow(new IllegalStateException("registration failed"))
            .willAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return true;
            });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RunForecastsService service = service(lanes, batches, meters);

        // when
        service.pickUp();

        // then
        assertThat(meters.find("salmonbus.forecast.rejected").counter()).isNull();

        // when
        service.batchStored(1, VERSION);

        // then
        verify(batches).readBatch(1);
    }

    @Test
    void 신호_등록_오류가_커밋된_수집의_실패로_전파되지_않는다(CapturedOutput output) {
        // given
        ObservationBatchReader batches = mock(ObservationBatchReader.class);
        given(batches.readBatch(1)).willReturn(Optional.empty());
        ForecastLanes lanes = mock(ForecastLanes.class);
        given(lanes.submit(anyString(), any()))
            .willReturn(false)
            .willThrow(new IllegalStateException("private query", new IllegalArgumentException("private vehicle")))
            .willAnswer(invocation -> {
                Runnable task = invocation.getArgument(1);
                task.run();
                return true;
            });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RunForecastsService service = service(lanes, batches, meters);
        service.pickUp();

        // when
        Throwable failure = catchThrowable(() -> service.batchStored(1, VERSION));

        // then
        assertSoftly(softly -> {
            softly.assertThat(failure).isNull();
            softly.assertThat(meters.get("salmonbus.forecast.rejected").counter().count()).isEqualTo(1);
            softly.assertThat(output.getAll()).contains("event=forecast_signal_failed")
                .doesNotContain("private query", "private vehicle");
        });

        // when
        service.batchStored(1, VERSION);

        // then
        verify(batches).readBatch(1);
    }

    private RunForecastsService service(
        ForecastLanes lanes,
        ObservationBatchReader batches,
        SimpleMeterRegistry meters
    ) {
        ForecastBatchProcessor processor = mock(ForecastBatchProcessor.class);
        given(processor.states()).willReturn(new RouteStateRegistry());
        RouteStops stops = new RouteStops(
                VERSION,
                "204000057",
                List.of(new RouteStop(
                        VERSION,
                        1,
                        "stop",
                        true,
                        RouteDirection.UP
                )),
                "3330"
        );
        RuntimeSnapshot runtime = ForecastTestSupport.runtime(NOW);
        return new RunForecastsService(
                lanes,
                processor,
                batches,
                (version, notBefore, limit) -> List.of(new PendingBatches.Pending(1, VERSION, NOW)),
                () -> Optional.of(runtime),
                () -> List.of(VERSION),
                version -> stops,
                mock(SettlementContextWriter.class),
                new ForecastPolicy(Duration.ofMinutes(5), 20),
                Clock.fixed(NOW, ZoneOffset.UTC),
                meters
        );
    }
}
