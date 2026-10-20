package com.gustler.backend.forecasting.domain.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 서빙 정본에서 생성한 대조 사례 하나.
 *
 * <p>서빙 저장소의 {@code server/model/distribution.py} 에 있는 {@code distribution_pmf} 로 생성했다.
 * 해당 파일을 수정하지 않고 그대로 호출했다. 계산식을 파이썬으로 다시 옮겨 대조하면
 * 잘못된 해석이 양쪽에 반복되어 오류를 발견할 수 없다.
 *
 * <p>처음에는 오프라인 채점기와 대조했지만 그쪽에 결함이 둘 있어 서빙 구현으로 변경했다.
 *
 * <p>이 파일은 2026-08-23 기준으로 v4-1 을 반영하지 않았다. 계수 계약 · 셀 통계 · 도착 lead 의
 * 근거로 사용하지 않고 잔차에서 좌석 확률로 변환하는 수식의 대조에만 사용한다.
 *
 * <p>값을 다시 생성하는 방법은
 * {@code ~/dev/.salmonbus-claude/참고/a18-서빙-8월23일/대조값-생성기.py} 에 있다.
 */
record ReferenceParityCase(
    String name,
    double[] featureVector,
    int capacity,
    int upstreamSeats,
    double fullChance,
    double anchorIntercept,
    double anchorSlope,
    double[] sameSeatsCoefficients,
    double[] belowAnchorCoefficients,
    double[][][] binCoefficients,
    boolean[][] binFitted,
    int anchorSeats,
    double[] expectedProbabilities
) {
    private static final String FIXTURE = "/seat-distribution/serving-parity.json";

    static double[] relativeEdges() {
        return doublesOf(root().get("settings").get("relative_bin_edges"));
    }

    static List<ReferenceParityCase> all() {
        List<ReferenceParityCase> cases = new ArrayList<>();
        for (JsonNode node : root().get("cases")) {
            cases.add(from(node));
        }
        return cases;
    }

    static ReferenceParityCase named(
        String name
    ) {
        return all().stream()
            .filter(one -> one.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("그런 대조 사례가 없다: " + name));
    }

    private static ReferenceParityCase from(
        JsonNode node
    ) {
        return new ReferenceParityCase(
            node.get("name").stringValue(),
            doublesOf(node.get("featureVector")),
            node.get("capacity").asInt(),
            node.get("upstreamSeats").asInt(),
            node.get("fullChance").asDouble(),
            node.get("anchor").get("intercept").asDouble(),
            node.get("anchor").get("slope").asDouble(),
            doublesOf(node.get("signZeroCoefficients")),
            doublesOf(node.get("signPositiveCoefficients")),
            new double[][][] {
                matrixOf(node.get("binCoefficients").get("positive")),
                matrixOf(node.get("binCoefficients").get("negative")),
            },
            new boolean[][] {
                flagsOf(node.get("binFitted").get("positive")),
                flagsOf(node.get("binFitted").get("negative")),
            },
            node.get("anchorSeats").asInt(),
            doublesOf(node.get("expectedProbabilities")));
    }

    private static JsonNode root() {
        try (InputStream stream = ReferenceParityCase.class.getResourceAsStream(FIXTURE)) {
            return new ObjectMapper().readTree(stream);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static double[] doublesOf(
        JsonNode node
    ) {
        double[] values = new double[node.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = node.get(index).asDouble();
        }
        return values;
    }

    private static double[][] matrixOf(
        JsonNode node
    ) {
        double[][] values = new double[node.size()][];
        for (int index = 0; index < values.length; index++) {
            values[index] = doublesOf(node.get(index));
        }
        return values;
    }

    private static boolean[] flagsOf(
        JsonNode node
    ) {
        boolean[] values = new boolean[node.size()];
        for (int index = 0; index < values.length; index++) {
            values[index] = node.get(index).asInt() == 1;
        }
        return values;
    }

    ResidualDistribution residuals() {
        return new ResidualDistribution(
            sameSeatsCoefficients, belowAnchorCoefficients, binCoefficients, binFitted, relativeEdges());
    }

    double[] seatChances() {
        return SeatProbabilityTable.of(
            residuals().chancesByResidual(featureVector, anchorSeats, capacity),
            anchorSeats,
            capacity,
            fullChance);
    }
}
