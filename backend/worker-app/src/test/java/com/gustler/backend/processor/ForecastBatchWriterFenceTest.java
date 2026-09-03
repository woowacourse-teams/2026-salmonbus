package com.gustler.backend.processor;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ForecastBatchWriterFenceTest {

    @Mock
    private VehicleTrajectoryRepository vehicleTrajectoryRepository;

    @Mock
    private SeatForecastRepository seatForecastRepository;

    @Mock
    private StopDemandStatisticsRepository stopDemandStatisticsRepository;

    @Mock
    private ForecastWriteBarrier forecastWriteBarrier;

    @Mock
    private Clock clock;

    @Test
    void skipsTheWholeForecastWriteCycleWhenTheFenceCannotBeEntered() {
        when(forecastWriteBarrier.enter()).thenReturn(false);
        ForecastBatchWriter writer = new ForecastBatchWriter(
            vehicleTrajectoryRepository,
            seatForecastRepository,
            stopDemandStatisticsRepository,
            forecastWriteBarrier,
            clock);

        writer.writeForecastsOf(null, null, null);

        verify(forecastWriteBarrier).enter();
        verifyNoInteractions(vehicleTrajectoryRepository, seatForecastRepository, stopDemandStatisticsRepository);
        verify(clock, never()).instant();
    }
}
