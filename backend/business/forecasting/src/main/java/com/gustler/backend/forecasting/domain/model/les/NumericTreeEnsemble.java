package com.gustler.backend.forecasting.domain.model.les;

import java.util.List;

/** 레스가 학습한 수치 분기 트리. 잎 값에는 학습률이 이미 반영되어 있다. */
public final class NumericTreeEnsemble {

    public enum Kind {
        REGRESSION,
        MULTICLASS,
        BINARY;

        private int baselineCount() {
            if (this == MULTICLASS) {
                return 3;
            }
            return 1;
        }
    }

    /** 내보낸 트리 노드의 저장 계약이다. 분기와 잎을 같은 필드 순서로 읽는다. */
    public record Node(
        int feature,
        double threshold,
        boolean missingLeft,
        int left,
        int right,
        double value,
        boolean leaf
    ) {

        private boolean hasInvalidBranch(final int index, final int nodeCount, final int featureCount) {
            return feature < 0 || feature >= featureCount
                || left <= index || right <= index || left >= nodeCount || right >= nodeCount;
        }

        private int nextNodeIndex(final double featureValue) {
            if (isLeftBranch(featureValue)) {
                return left;
            }
            return right;
        }

        private boolean isLeftBranch(final double featureValue) {
            if (Double.isNaN(featureValue)) {
                return missingLeft;
            }
            return featureValue <= threshold;
        }
    }

    private final int featureCount;
    private final Kind kind;
    private final double[] baseline;
    private final List<List<List<Node>>> iterations;

    public NumericTreeEnsemble(
        final int featureCount,
        Kind kind,
        double[] baseline,
        List<List<List<Node>>> iterations
    ) {
        validateDimensions(featureCount, kind, baseline, iterations);
        for (final double value : baseline) {
            requireFinite(value);
        }
        this.featureCount = featureCount;
        this.kind = kind;
        this.baseline = baseline.clone();
        this.iterations = iterations.stream()
            .map(this::checkedIteration)
            .toList();
    }

    private static void validateDimensions(
        final int featureCount,
        Kind kind,
        double[] baseline,
        List<List<List<Node>>> iterations
    ) {
        if (hasInvalidDimensions(featureCount, kind, baseline, iterations)) {
            throw new IllegalArgumentException("트리 모델 차원이 잘못됐다");
        }
    }

    private static boolean hasInvalidDimensions(
        final int featureCount,
        Kind kind,
        double[] baseline,
        List<List<List<Node>>> iterations
    ) {
        return featureCount <= 0 || kind == null
            || baseline.length != kind.baselineCount() || iterations.isEmpty();
    }

    private List<List<Node>> checkedIteration(List<List<Node>> iteration) {
        if (iteration.size() != baseline.length) {
            throw new IllegalArgumentException("반복별 트리 수가 클래스 수와 다르다");
        }
        return iteration.stream()
            .map(this::checkedTree)
            .toList();
    }

    private List<Node> checkedTree(List<Node> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("트리가 비어 있다");
        }
        int[] parentCounts = new int[nodes.size()];
        for (int index = 0; index < nodes.size(); index++) {
            Node node = nodes.get(index);
            requireFinite(node.value());
            requireFinite(node.threshold());
            if (node.leaf()) {
                continue;
            }
            validateBranch(node, index, nodes.size());
            parentCounts[node.left()]++;
            parentCounts[node.right()]++;
        }
        validateParents(parentCounts);
        return List.copyOf(nodes);
    }

    private void validateBranch(Node node, final int index, final int nodeCount) {
        if (node.hasInvalidBranch(index, nodeCount, featureCount)) {
            throw new IllegalArgumentException("트리 분기 번호가 잘못됐다");
        }
    }

    private static void validateParents(int[] parentCounts) {
        for (int index = 1; index < parentCounts.length; index++) {
            if (parentCounts[index] != 1) {
                throw new IllegalArgumentException("연결되지 않거나 중복 연결된 트리 노드다");
            }
        }
    }

    public int featureCount() {
        return featureCount;
    }

    public Kind kind() {
        return kind;
    }

    public double[] predict(double[] features) {
        validateFeatures(features);
        double[] raw = calculateRawScores(features);
        for (final double value : raw) {
            requireFinite(value);
        }
        if (kind == Kind.BINARY) {
            final double positive = positiveProbability(raw[0]);
            return new double[] { 1.0 - positive, positive };
        }
        if (kind == Kind.MULTICLASS) {
            normalizeClassProbabilities(raw);
        }
        return raw;
    }

    private void validateFeatures(double[] features) {
        if (features.length != featureCount) {
            throw new IllegalArgumentException("입력 열 수가 모델과 다르다");
        }
        for (final double value : features) {
            if (Double.isInfinite(value)) {
                throw new IllegalArgumentException("입력은 무한대가 될 수 없다");
            }
        }
    }

    /** 학습 모델의 반복·클래스 순서대로 더한다. 합산 순서를 바꾸지 않는다. */
    private double[] calculateRawScores(double[] features) {
        double[] raw = baseline.clone();
        for (List<List<Node>> iteration : iterations) {
            for (int classIndex = 0; classIndex < iteration.size(); classIndex++) {
                raw[classIndex] += leafValue(iteration.get(classIndex), features);
            }
        }
        return raw;
    }

    private static double leafValue(List<Node> tree, double[] features) {
        Node node = tree.getFirst();
        while (!node.leaf()) {
            node = tree.get(node.nextNodeIndex(features[node.feature()]));
        }
        return node.value();
    }

    /** 부호에 따라 지수 계산을 나누며 당일 확률 보정이나 임의의 확률 제한을 적용하지 않는다. */
    private static double positiveProbability(final double rawScore) {
        if (rawScore >= 0) {
            return 1.0 / (1.0 + Math.exp(-rawScore));
        }
        return Math.exp(rawScore) / (1.0 + Math.exp(rawScore));
    }

    private static void normalizeClassProbabilities(double[] raw) {
        final double maximum = Math.max(raw[0], Math.max(raw[1], raw[2]));
        double total = 0;
        for (int index = 0; index < raw.length; index++) {
            raw[index] = Math.exp(raw[index] - maximum);
            total += raw[index];
        }
        for (int index = 0; index < raw.length; index++) {
            raw[index] /= total;
        }
    }

    private static void requireFinite(final double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("모델 값이 유한하지 않다");
        }
    }
}
