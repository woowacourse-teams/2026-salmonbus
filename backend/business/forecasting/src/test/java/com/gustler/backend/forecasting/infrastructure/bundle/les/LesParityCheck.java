package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 지정한 로컬 원본 사례만 읽는 검증 실행기. 일반 테스트에서는 실행하지 않는다. */
public final class LesParityCheck {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("bundle 경로와 결과 파일 경로가 필요하다");
        }
        long start = System.nanoTime();
        Path bundle = Path.of(args[0]);
        Path output = Path.of(args[1]);
        if (Files.exists(output)) {
            throw new IllegalArgumentException("기존 검증 결과를 덮어쓰지 않는다");
        }
        var loaded = LesInferenceBundle.load(bundle);
        Map<String, Object> report = new LinkedHashMap<>();
        for (String name : new String[]{"golden.json", "boundaries.json"}) {
            JsonNode cases = JSON.readTree(Files.readAllBytes(bundle.resolve(name)));
            double[] maximum = new double[6];
            int rows = cases.get("seat_inputs").size();
            if (rows == 0) {
                throw new IllegalArgumentException("비교 사례가 없다");
            }
            for (int i = 0; i < rows; i++) {
                LesSeatPredictor.Prediction p = loaded.predictor().predict(
                    vector(cases.get("seat_inputs").get(i)), vector(cases.get("probability_inputs").get(i)));
                double[] actual = {p.base(), p.gateZero(), p.gateLow(), p.gateOther(),
                                   p.afterAlightingSeats(), p.departureFullProbability()};
                JsonNode gate = cases.get("expected_gate_probabilities").get(i);
                double[] expected = {cases.get("expected_base").get(i).doubleValue(), gate.get(0).doubleValue(),
                    gate.get(1).doubleValue(), gate.get(2).doubleValue(), cases.get("expected_seats").get(i).doubleValue(),
                    cases.get("expected_departure_full").get(i).doubleValue()};
                for (int j = 0; j < maximum.length; j++) {
                    double difference = Math.abs(actual[j] - expected[j]);
                    if (!Double.isFinite(difference) || difference > 1e-9) {
                        throw new IllegalStateException(name + " row=" + i + " output=" + j + " difference=" + difference);
                    }
                    maximum[j] = Math.max(maximum[j], difference);
                }
            }
            report.put(name, Map.of("rows", rows, "max_absolute_difference", maximum));
        }
        report.put("output_order", new String[]{"base", "gate_zero", "gate_low", "gate_other", "seats", "full"});
        report.put("tolerance", 1e-9);
        report.put("bundle_manifest_sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(bundle.resolve("manifest.json")))));
        report.put("java_version", System.getProperty("java.version"));
        report.put("elapsed_seconds", (System.nanoTime() - start) / 1e9);
        report.put("serving_ready", false);
        report.put("passed", true);
        Files.writeString(output, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println(JSON.writeValueAsString(report));
    }

    private static double[] vector(JsonNode row) {
        if (row == null || !row.isArray()) {
            throw new IllegalArgumentException("입력 배열이 없다");
        }
        double[] values = new double[row.size()];
        for (int i = 0; i < values.length; i++) {
            JsonNode value = row.get(i);
            if (!value.isNull() && !value.isNumber()) {
                throw new IllegalArgumentException("입력 값이 숫자나 결측값이 아니다");
            }
            values[i] = value.isNull() ? Double.NaN : value.doubleValue();
        }
        return values;
    }
}
