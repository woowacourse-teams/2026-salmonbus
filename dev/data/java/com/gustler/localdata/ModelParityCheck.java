package com.gustler.localdata;

import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

public final class ModelParityCheck {
    public static void main(String[] args) {
        try {
            var release = new FileModelBundleLoader().filesUnder(ReferenceBundle.DIRECTORY).load();
            LocalData.require(ReferenceBundle.DIGEST.equals(release.bundleDigest()), "MODEL_PARITY_IDENTITY");
            var json = JsonMapper.builder().build();
            var golden = json.readTree(Files.readString(Path.of("/local/data/model-reference/manifest.json")))
                .get("goldenVector");
            double[] vector = new double[golden.get("featureVector").size()];
            for (int index = 0; index < vector.length; index++) {
                vector[index] = golden.get("featureVector").get(index).doubleValue();
            }
            var distribution = release.predictor().predict(new SeatDistributionInput(vector,
                golden.get("modelRoute").stringValue(), golden.get("stopsAhead").intValue(),
                golden.get("currentSeats").intValue(), golden.get("capacity").intValue(), null)).distribution();
            LocalData.require(Math.abs(distribution.expectedSeats() - golden.get("expectedSeats").doubleValue()) < 1e-9
                && Math.abs(distribution.fullChance() - golden.get("expectedFullChance").doubleValue()) < 1e-9,
                "MODEL_PARITY_GOLDEN");
            final double expectedSeats = distribution.expectedSeats();
            for (String route : RouteDataset.MODEL_ROUTES) {
                for (int horizon = 1; horizon <= 12; horizon++) {
                    for (final int seats : List.of(0, 3, 20, 43)) {
                        double[] inputs = vector.clone();
                        inputs[4] = seats / 44.0;
                        inputs[5] = seats == 0 ? 1 : 0;
                        inputs[6] = Math.min(seats / 20.0, 1);
                        var result = release.predictor().predict(new SeatDistributionInput(
                            inputs, route, horizon, seats, 44, null)).distribution();
                        LocalData.require(Double.isFinite(result.expectedSeats()) && result.expectedSeats() >= 0
                            && result.expectedSeats() <= 44 && Double.isFinite(result.fullChance())
                            && result.fullChance() >= 0 && result.fullChance() <= 1, "MODEL_PARITY_RANGE");
                    }
                }
            }
            System.out.println(json.writeValueAsString(Map.of("status", "passed", "releaseId", release.releaseId(),
                "bundleDigest", release.bundleDigest(), "goldenExpectedSeats", expectedSeats,
                "goldenFullChance", distribution.fullChance(), "goldenTolerance", 1e-9, "rangeCases", 384,
                "sameDayCalibration", "golden input has no same-day adjustment")));
        } catch (Exception error) {
            System.err.println("{\"status\":\"failed\",\"code\":\"MODEL_PARITY\"}");
            System.exit(1);
        }
    }
}
