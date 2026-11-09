package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 지정한 로컬 원본 사례만 읽는 검증 실행기. 일반 테스트에서는 실행하지 않는다. */
public final class LesParityCheck {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double TOLERANCE = 1e-9;

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {
        if (args.length != 2) {
            throw new IllegalArgumentException("bundle 경로와 결과 파일 경로가 필요하다");
        }
        final long start = System.nanoTime();
        Path bundle = Path.of(args[0]);
        Path output = Path.of(args[1]);
        if (Files.exists(output)) {
            throw new IllegalArgumentException("기존 검증 결과를 덮어쓰지 않는다");
        }
        LesInferenceBundle loaded = LesInferenceBundle.load(bundle);
        Map<String, Object> report = compareCases(bundle, loaded.predictor());
        appendReportMetadata(report, bundle, start);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println(JSON.writeValueAsString(report));
    }

    private static Map<String, Object> compareCases(Path bundle, LesSeatPredictor predictor) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        for (String name : new String[] { "golden.json", "boundaries.json" }) {
            JsonNode cases = JSON.readTree(Files.readAllBytes(bundle.resolve(name)));
            report.put(name, compareCaseFile(name, cases, predictor));
        }
        return report;
    }

    private static Map<String, Object> compareCaseFile(String name, JsonNode cases, LesSeatPredictor predictor) {
        double[] maximum = new double[6];
        final int rows = cases.get("seat_inputs").size();
        if (rows == 0) {
            throw new IllegalArgumentException("비교 사례가 없다");
        }
        for (int row = 0; row < rows; row++) {
            LesSeatPredictor.Prediction prediction = predictor.predict(
                vector(cases.get("seat_inputs").get(row)), vector(cases.get("probability_inputs").get(row)));
            compareOutputs(name, row, actualOutputs(prediction), expectedOutputs(cases, row), maximum);
        }
        return Map.of("rows", rows, "max_absolute_difference", maximum);
    }

    private static double[] actualOutputs(LesSeatPredictor.Prediction prediction) {
        return new double[] { prediction.base(), prediction.gateZero(), prediction.gateLow(), prediction.gateOther(),
            prediction.afterAlightingSeats(), prediction.departureFullProbability() };
    }

    private static double[] expectedOutputs(JsonNode cases, final int row) {
        JsonNode gate = cases.get("expected_gate_probabilities").get(row);
        return new double[] { cases.get("expected_base").get(row).doubleValue(), gate.get(0).doubleValue(),
            gate.get(1).doubleValue(), gate.get(2).doubleValue(), cases.get("expected_seats").get(row).doubleValue(),
            cases.get("expected_departure_full").get(row).doubleValue() };
    }

    private static void compareOutputs(
        String name,
        final int row,
        double[] actual,
        double[] expected,
        double[] maximum
    ) {
        for (int column = 0; column < maximum.length; column++) {
            final double difference = Math.abs(actual[column] - expected[column]);
            if (!Double.isFinite(difference) || difference > TOLERANCE) {
                throw new IllegalStateException(
                    name + " row=" + row + " output=" + column + " difference=" + difference);
            }
            maximum[column] = Math.max(maximum[column], difference);
        }
    }

    private static void appendReportMetadata(Map<String, Object> report, Path bundle, final long start)
        throws IOException, NoSuchAlgorithmException {
        report.put("output_order", new String[] { "base", "gate_zero", "gate_low", "gate_other", "seats", "full" });
        report.put("tolerance", TOLERANCE);
        report.put("bundle_manifest_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(bundle.resolve("manifest.json")))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("elapsed_seconds", (System.nanoTime() - start) / 1e9);
        report.put("serving_ready", false);
        report.put("passed", true);
    }

    private static double[] vector(JsonNode row) {
        if (row == null || !row.isArray()) {
            throw new IllegalArgumentException("입력 배열이 없다");
        }
        double[] values = new double[row.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = featureValue(row.get(index));
        }
        return values;
    }

    private static double featureValue(JsonNode value) {
        if (value.isNull()) {
            return Double.NaN;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("입력 값이 숫자나 결측값이 아니다");
        }
        return value.doubleValue();
    }
}
