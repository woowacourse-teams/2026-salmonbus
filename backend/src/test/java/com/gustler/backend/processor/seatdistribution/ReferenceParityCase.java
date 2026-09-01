package com.gustler.backend.processor.seatdistribution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 파이썬 정본이 뽑아 둔 대조 사례 하나.
 *
 * <p>값을 만든 것은 {@code models.py} 의 {@code _ResidualLayer.pmf} 다. 그 파일을 고치지 않고
 * 그대로 불러 뽑았다. 손으로 옮긴 파이썬으로 대조하면 같은 오해가 양쪽에 들어가 아무것도 못 잰다.
 *
 * <p>값을 다시 뽑는 방법은 {@code ~/dev/.salmonbus-claude/참고/a18-python-정본/대조값-생성기.py} 에 있다.
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

    private static final String FIXTURE = "/seat-distribution/python-parity.json";

    /** 만석이 아닐 질량이 하나도 안 남는 자리. 파이썬 채점기는 0석 확률을 1 로 만든다. */
    static final String NO_SEAT_LEFT_TO_SPREAD = "만석이_아닐_질량이_안_남음";

    /** 크기 묶음 하나가 통째로 잔차 격자 밖으로 밀리는 자리. 파이썬 채점기가 질량을 더 놓는다. */
    static final String BIN_FULLY_UNDER_GRID = "격자_아래로_밀림";

    /** 파이썬 채점기와 서빙 계약이 갈리는 사례. 대조에서 빼고 하나씩 따로 본다. */
    static final List<String> DIVERGING_CASES =
        List.of(NO_SEAT_LEFT_TO_SPREAD, BIN_FULLY_UNDER_GRID);

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
