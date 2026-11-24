package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import com.gustler.backend.forecasting.api.forecast.ForecastBatchEvents;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.ObservationBatchSeen;
import com.gustler.backend.forecasting.application.forecast.ForecastBatch;
import com.gustler.backend.forecasting.application.forecast.ForecastBatchProcessor;
import com.gustler.backend.forecasting.application.forecast.ForecastEvents;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchRead;
import com.gustler.backend.forecasting.application.forecast.RouteContext;
import com.gustler.backend.forecasting.application.forecast.RouteStateRegistry;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionOperations;

class ForecastBatchNotificationTest {

    @Test
    void 저장한_배치의_정산_알림을_한번만_전달한다() {
        // given
        Instant now = Instant.parse("2026-11-24T01:00:00Z");
        RuntimeSnapshot runtime = ForecastTestSupport.runtime(now);
        RouteStops stops = new RouteStops(
                7,
                "204000057",
                List.of(new RouteStop(
                        7,
                        1,
                        "stop",
                        true,
                        RouteDirection.UP
                )),
                "3330"
        );
        RouteContext context = new RouteContext(runtime.model().statistics(stops), stops, runtime);
        ForecastEvents events = mock(ForecastEvents.class);
        ForecastRecordStore records = mock(ForecastRecordStore.class);
        given(records.save(any(), any())).willReturn(true);
        ForecastBatchProcessor processor = new ForecastBatchProcessor(
                mock(ForecastStage.class),
                mock(RouteStateReplayer.class),
                records,
                mock(StopPassStore.class),
                TransactionOperations.withoutTransaction(),
                events,
                new RouteStateRegistry(),
                false
        );
        ForecastBatch batch = new ForecastBatch(
                1,
                7,
                now,
                now.plusSeconds(2),
                "LIVE",
                "SUCCESS_EMPTY",
                0,
                List.of()
        );

        // when
        processor.process(context, new ObservationBatchRead(
                batch,
                31,
                now,
                "GBIS",
                Map.of()
        ));

        // then
        ArgumentCaptor<ForecastBatchEvents> captured = ArgumentCaptor.forClass(ForecastBatchEvents.class);
        InOrder order = inOrder(records, events);
        order.verify(records).save(any(), any());
        order.verify(events).committed(captured.capture());
        assertThat(captured.getValue().events()).extracting(Object::getClass)
            .containsExactly(ObservationBatchSeen.class, ForecastPublished.class);
    }
}
