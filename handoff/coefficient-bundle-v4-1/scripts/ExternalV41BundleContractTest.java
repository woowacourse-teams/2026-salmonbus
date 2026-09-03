package com.gustler.backend.processor.seatdistribution;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gustler.backend.processor.SeatForecastDesignMatrix;
import com.gustler.backend.processor.SeatForecastResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External fixture for the exact bundle consumer at the pinned dev commit.
 *
 * <p>The exporter must not import or call this class. The wrapper copies it into a temporary
 * checkout of the pinned commit, so the calculations below use the consumer's classes rather
 * than the exporter's scoring implementation.
 */
class ExternalV41BundleContractTest {

    private static final double[] RELATIVE_BIN_EDGES =
        {0.0, 0.03, 0.07, 0.12, 0.2, 0.32, 0.48, 0.7, 1.0};

    private static final List<String> ROUTES = List.of("1650", "3330");
    private static final List<Integer> HORIZONS =
        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
    private static final Set<Integer> ZERO_FEATURE_AXES =
        Set.of(3, 18, 20, 21, 22, 23, 24, 25, 26, 27);

    @Test
    void verifiesFinalBundleOrPrintsIndependentGoldenValues() throws Exception {
        String directory = System.getenv("V4_1_BUNDLE_DIRECTORY");
        Assumptions.assumeTrue(directory != null && !directory.isBlank());

        BundleFiles files = BundleFiles.under(Path.of(directory));
        if (Boolean.parseBoolean(System.getenv("V4_1_PROBE_ONLY"))) {
            probe(files);
            return;
        }

        if (Boolean.parseBoolean(System.getenv("V4_1_LOADER_ONLY"))) {
            LoadedBundle loaded = LoadedBundle.from(files);
            loaded.receiptWith(UUID.randomUUID());
            System.out.println("V4_1_JAVA_LOADER_ONLY=PASS");
            return;
        }

        requireExternalReleaseProfile(files);
        LoadedBundle loaded = LoadedBundle.from(files);
        loaded.receiptWith(UUID.randomUUID());
        BundleManifest.GoldenVector golden = loaded.coefficients().manifest().goldenVector();
        SeatForecastResult actual = loaded.predictor().predict(inputOf(golden));
        System.out.println("V4_1_JAVA_VERIFY=PASS");
        System.out.println("V4_1_JAVA_EXPECTED_FULL_CHANCE="
            + Double.toString(actual.distribution().fullChance()));
        System.out.println("V4_1_JAVA_EXPECTED_SEATS="
            + Double.toString(actual.distribution().expectedSeats()));
    }

    /** The wrapper uses this only to verify that the injected fixture reaches the strict path. */
    @Test
    void fixtureCanTraversePinnedLoader(@TempDir Path directory) throws Exception {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("V4_1_SELF_TEST")));
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        probe(files);
        LoadedBundle.from(files);
        System.out.println("V4_1_JAVA_SELF_TEST=PASS");
    }

    /**
     * Read a provisional manifest, score its golden input with Java, and print the exact values
     * the release assembler must insert. This deliberately does not trust expectedFullChance or
     * expectedSeats supplied by the exporter.
     */
    private static void probe(
        BundleFiles files
    ) throws Exception {
        byte[] manifestBytes = files.readManifest();
        byte[] weightsBytes = files.readWeights();
        BundleManifest manifest = BundleManifestReader.read(manifestBytes);

        requireProbeScope(manifest);
        assertEquals(manifest.weightsDigest(), Sha256.of(weightsBytes),
            "provisional manifest weightsDigest");

        SafetensorsFile weights = SafetensorsFile.of(weightsBytes);
        Map<BundleTensor, Tensor> tensors = checkedProbeTensors(manifest, weights);
        if (!Boolean.parseBoolean(System.getenv("V4_1_SELF_TEST"))) {
            requireReleaseProfile(manifest, tensors);
        }
        CoefficientBundle coefficients = new CoefficientBundle(manifest, tensors);
        SeatDistributionPredictor predictor =
            new SeatDistributionPredictor(coefficients, RELATIVE_BIN_EDGES);
        new LoadedBundle(coefficients, predictor).receiptWith(UUID.randomUUID());

        BundleManifest.GoldenVector supplied = manifest.goldenVector();
        SeatForecastResult actual = predictor.predict(inputOf(supplied));
        BundleManifest.GoldenVector resolved = new BundleManifest.GoldenVector(
            supplied.featureVector(),
            supplied.modelRoute(),
            supplied.stopsAhead(),
            supplied.currentSeats(),
            supplied.capacity(),
            actual.distribution().fullChance(),
            actual.distribution().expectedSeats());

        String goldenDigest = Sha256.of(BundleLoader.goldenVectorText(resolved));
        String identityDigest = identityDigestOf(manifest, goldenDigest);

        if (Boolean.parseBoolean(System.getenv("V4_1_SELF_TEST"))) {
            assertEquals(manifest.goldenVectorDigest(), goldenDigest);
            assertEquals(manifest.identityDigest(), identityDigest);
        }

        System.out.println("V4_1_JAVA_PROBE=PASS");
        System.out.println("V4_1_JAVA_EXPECTED_FULL_CHANCE="
            + Double.toString(resolved.expectedFullChance()));
        System.out.println("V4_1_JAVA_EXPECTED_SEATS="
            + Double.toString(resolved.expectedSeats()));
        System.out.println("V4_1_JAVA_GOLDEN_VECTOR_DIGEST=" + goldenDigest);
        System.out.println("V4_1_JAVA_IDENTITY_DIGEST=" + identityDigest);
    }

    private static SeatDistributionInput inputOf(
        BundleManifest.GoldenVector golden
    ) {
        return new SeatDistributionInput(
            golden.featureVector().stream().mapToDouble(Double::doubleValue).toArray(),
            golden.modelRoute(),
            golden.stopsAhead(),
            golden.currentSeats(),
            golden.capacity(),
            null);
    }

    /** Probe mode checks everything needed to make scoring meaningful, but not the two final digests. */
    private static void requireProbeScope(
        BundleManifest manifest
    ) {
        assertEquals("a18-live-bundle-v1", manifest.bundleSchemaVersion());
        assertEquals("seat-distribution-a18-v1", manifest.modelVersion());
        assertEquals(ROUTES, manifest.routes());
        assertEquals(HORIZONS, manifest.horizonStops());
        assertEquals(SeatForecastDesignMatrix.COLUMN_NAMES, manifest.featureNames());
        assertFalse(manifest.releaseId().isBlank());
        assertFalse(manifest.featureContractVersion().isBlank());
        assertFalse(manifest.routeReferenceVersion().isBlank());
        assertEquals(64, manifest.routeReferenceDigest().length());
        assertEquals(SeatForecastDesignMatrix.COLUMN_COUNT,
            manifest.goldenVector().featureVector().size());
    }

    private static Map<BundleTensor, Tensor> checkedProbeTensors(
        BundleManifest manifest,
        SafetensorsFile weights
    ) {
        Set<String> expectedNames = Arrays.stream(BundleTensor.values())
            .map(BundleTensor::tensorName)
            .collect(Collectors.toSet());
        assertEquals(expectedNames, weights.names(), "weights tensor names");
        assertEquals(expectedNames, manifest.tensorDeclarations().keySet(),
            "manifest tensor declaration names");

        Map<BundleTensor, Tensor> tensors = new EnumMap<>(BundleTensor.class);
        for (BundleTensor wanted : BundleTensor.values()) {
            Tensor tensor = weights.get(wanted.tensorName());
            BundleManifest.TensorDeclaration declaration =
                manifest.tensorDeclarations().get(wanted.tensorName());
            assertNotNull(declaration, wanted.tensorName());

            int[] expectedShape = wanted.shapeOf(
                manifest.routes().size(),
                manifest.horizonStops().size(),
                manifest.featureCount());
            assertArrayEquals(expectedShape, declaration.shape(),
                wanted.tensorName() + " manifest shape");
            assertArrayEquals(expectedShape, tensor.shape(),
                wanted.tensorName() + " weights shape");
            assertEquals(wanted.dataType(), declaration.dataType(),
                wanted.tensorName() + " manifest dtype");
            assertEquals(wanted.dataType(), tensor.dataType(),
                wanted.tensorName() + " weights dtype");

            for (double value : tensor.values()) {
                if (wanted.dataType() == TensorDataType.FLOAT64) {
                    assertTrue(Double.isFinite(value), wanted.tensorName() + " finite values");
                } else {
                    assertTrue(value == 0.0 || value == 1.0,
                        wanted.tensorName() + " binary flags");
                }
            }
            tensors.put(wanted, tensor);
        }
        return tensors;
    }

    private static void requireExternalReleaseProfile(
        BundleFiles files
    ) {
        BundleManifest manifest = BundleManifestReader.read(files.readManifest());
        SafetensorsFile weights = SafetensorsFile.of(files.readWeights());
        requireReleaseProfile(manifest, checkedProbeTensors(manifest, weights));
    }

    /** Checks the stricter release profile that the current loader does not enforce itself. */
    private static void requireReleaseProfile(
        BundleManifest manifest,
        Map<BundleTensor, Tensor> tensors
    ) {
        assertEquals(
            Map.of("largestSeatCount", 68.0, "lowSeatBand", 20.0),
            manifest.normalizationConstants());
        assertEquals("observation_batch.response_received_at", manifest.timeSlotSource());
        assertEquals("maximum-seats-ever-observed", manifest.capacityPolicy());
        assertFalse(manifest.cellStatisticsPolicy().isBlank());
        assertTrue(manifest.sourceCommit().matches("[0-9a-f]{40}"),
            "sourceCommit must be a full lowercase Git SHA");

        for (BundleTensor tensorName : List.of(
            BundleTensor.FULL_CHANCE,
            BundleTensor.RESIDUAL_SIGN,
            BundleTensor.RESIDUAL_BIN)) {
            Tensor tensor = tensors.get(tensorName);
            for (int index = 0; index < tensor.values().length; index++) {
                if (ZERO_FEATURE_AXES.contains(index % SeatForecastDesignMatrix.COLUMN_COUNT)) {
                    assertPositiveZero(tensor.values()[index],
                        tensorName.tensorName() + " zero-only feature coefficient at flat index " + index);
                }
            }
        }

        Tensor fitted = tensors.get(BundleTensor.RESIDUAL_BIN_FITTED);
        Tensor bins = tensors.get(BundleTensor.RESIDUAL_BIN);
        for (int route = 0; route < ROUTES.size(); route++) {
            for (int horizon = 0; horizon < HORIZONS.size(); horizon++) {
                for (int direction = 0; direction < 2; direction++) {
                    for (int bin = 0; bin < 9; bin++) {
                        if (fitted.valueAt(route, horizon, direction, bin) != 0.0) {
                            continue;
                        }
                        for (double coefficient : bins.rowAt(route, horizon, direction, bin)) {
                            assertPositiveZero(coefficient,
                                "unfitted bin coefficients must be +0.0");
                        }
                    }
                }
            }
        }
    }

    private static void assertPositiveZero(
        final double value,
        String message
    ) {
        assertEquals(
            Double.doubleToRawLongBits(0.0),
            Double.doubleToRawLongBits(value),
            message);
    }

    /** Same 12-line UTF-8 text as BundleLoader.checkWeightsDigest, with a resolved golden digest. */
    private static String identityDigestOf(
        BundleManifest manifest,
        String goldenDigest
    ) {
        String normalization = manifest.normalizationConstants().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining(","));
        return Sha256.of(String.join("\n",
            manifest.featureContractVersion(),
            manifest.sourceCommit(),
            manifest.modelVersion(),
            manifest.routeReferenceVersion(),
            manifest.routeReferenceDigest(),
            manifest.weightsDigest(),
            String.join(",", manifest.featureNames()),
            normalization,
            manifest.timeSlotSource(),
            manifest.capacityPolicy(),
            manifest.cellStatisticsPolicy(),
            goldenDigest));
    }
}
