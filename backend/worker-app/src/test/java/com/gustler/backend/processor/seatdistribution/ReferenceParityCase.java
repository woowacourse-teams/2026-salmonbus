package com.gustler.backend.processor.seatdistribution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 서빙 정본이 뽑아 둔 대조 사례 하나.
 *
 * <p>값을 만든 것은 서빙 저장소 {@code server/model/distribution.py} 의 {@code distribution_pmf} 다.
 * 그 파일을 고치지 않고 그대로 불러 뽑았다. 손으로 옮긴 파이썬으로 대조하면 같은 오해가 양쪽에
 * 들어가 아무것도 못 잰다.
 *
 * <p>처음에는 오프라인 채점기로 대조했는데 그쪽에 결함이 둘 있어서 서빙 것으로 바꿨다.
 *
 * <p><b>이 파일은 2026-08-23 계보라 v4-1 이 안 들어가 있다.</b> 계수 계약 · 셀 통계 · 도착 lead 는
 * 이것을 근거로 삼지 않는다. 잔차에서 좌석 확률로 가는 변환 수식 대조에만 쓴다.
 *
 * <p>값을 다시 뽑는 방법은
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
