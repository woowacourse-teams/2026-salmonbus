package com.gustler.backend.processor.seatdistribution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import com.gustler.backend.processor.FullSeatStreak;
import com.gustler.backend.processor.ObservedSeats;
import com.gustler.backend.processor.ObservedVehicle;
import com.gustler.backend.processor.PrecedingVehicle;
import com.gustler.backend.processor.RouteStop;
import com.gustler.backend.processor.RouteStops;
import com.gustler.backend.processor.SeatForecastDesignMatrix;
import com.gustler.backend.processor.SeatForecastInput;
import com.gustler.backend.processor.SeatSlope;
import com.gustler.backend.processor.StopDemandCell;
import com.gustler.backend.processor.StopDemandStatistics;
import com.gustler.backend.processor.TimeSlot;
import com.gustler.backend.processor.VehicleStopTarget;
import com.gustler.backend.processor.VehicleTrajectory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cross-language fixture for the exact 31-column design-matrix semantics. */
class ExternalV41FeatureParityTest {

    @Test
    void exactDesignMatrixMatchesTheExporterFixture() {
        long routeVersion = 1L;
        Instant observedAt = Instant.parse("2026-08-19T08:30:00+09:00");
        ObservedVehicle observed = new ObservedVehicle(
            "deidentified-fixture", routeVersion, 3, observedAt, 12, 3);
        VehicleTrajectory trajectory = new VehicleTrajectory(
            1L,
            observed,
            new ObservedSeats.Known(12),
            new SeatSlope.Known(-3),
            new PrecedingVehicle.Known("deidentified-ahead", 11, observedAt.minusSeconds(120)),
            new FullSeatStreak.SeenToEnd(0),
            44);
        List<RouteStop> routeStopRows = new ArrayList<>();
        for (int stop = 1; stop <= 85; stop++) {
            routeStopRows.add(new RouteStop(routeVersion, stop, "safe-stop-" + stop, true));
        }
        RouteStops stops = new RouteStops(routeVersion, "204000057", routeStopRows);
        VehicleStopTarget target = new VehicleStopTarget(observed, routeStopRows.get(4));
        StopDemandStatistics statistics = new StopDemandStatistics(
            routeVersion,
            TimeSlot.MORNING,
            1,
            List.of(
                new StopDemandCell(1, 0.2, -0.1, 10, 2),
                new StopDemandCell(5, 0.6, 0.2, 10, 2),
                new StopDemandCell(9, 0.4, -0.05, 10, 2)));
        double[] actual = SeatForecastDesignMatrix.of(new SeatForecastInput(
            target, trajectory, statistics, stops, TimeSlot.MORNING, null)).toArray();

        double fillMean = (0.2 + 0.6 + 0.4) / 3.0;
        double fillStandardDeviation = Math.sqrt(
            (Math.pow(0.2 - fillMean, 2) + Math.pow(0.6 - fillMean, 2)
                + Math.pow(0.4 - fillMean, 2)) / 3.0);
        double netMean = (-0.1 + 0.2 - 0.05) / 3.0;
        double netStandardDeviation = Math.sqrt(
            (Math.pow(-0.1 - netMean, 2) + Math.pow(0.2 - netMean, 2)
                + Math.pow(-0.05 - netMean, 2)) / 3.0);
        double[] expected = {
            1.0, 1.0, 0.0, 0.0,
            12.0 / 44.0, 0.0, 12.0 / 20.0,
            0.0, 0.0, 1.0, 0.0,
            44.0 / 68.0, -3.0, 0.0, 0.0,
            0.0, 11.0 / 44.0, 0.0,
            0.0, 5.0 / 85.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
            (0.6 - fillMean) / (fillStandardDeviation + 1e-9),
            (0.2 - netMean) / (netStandardDeviation + 1e-9),
            0.0
        };
        assertArrayEquals(expected, actual, 1e-12);
        System.out.println("V4_1_JAVA_FEATURE_PARITY=PASS");
    }
}
