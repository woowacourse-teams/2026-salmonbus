package com.gustler.backend.forecasting.domain.model.les;

import java.util.List;

/** 레스가 학습한 수치 분기 트리. 잎 값에는 학습률이 이미 반영되어 있다. */
public final class NumericTreeEnsemble {
    public enum Kind { REGRESSION, MULTICLASS, BINARY }

    public record Node(int feature, double threshold, boolean missingLeft, int left, int right,
                       double value, boolean leaf) {
    }

    private final int featureCount;
    private final Kind kind;
    private final double[] baseline;
    private final List<List<List<Node>>> iterations;

    public NumericTreeEnsemble(int featureCount, Kind kind, double[] baseline,
                               List<List<List<Node>>> iterations) {
        if (featureCount <= 0 || kind == null || baseline.length != (kind == Kind.MULTICLASS ? 3 : 1)
            || iterations.isEmpty()) {
            throw new IllegalArgumentException("트리 모델 차원이 잘못됐다");
        }
        for (double value : baseline) {
            requireFinite(value);
        }
        this.featureCount = featureCount;
        this.kind = kind;
        this.baseline = baseline.clone();
        this.iterations = iterations.stream().map(step -> {
            if (step.size() != baseline.length) {
                throw new IllegalArgumentException("반복별 트리 수가 클래스 수와 다르다");
            }
            return step.stream().map(this::checkedTree).toList();
        }).toList();
    }

    private List<Node> checkedTree(List<Node> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("트리가 비어 있다");
        }
        int[] parents = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            requireFinite(node.value());
            requireFinite(node.threshold());
            if (!node.leaf()) {
                if (node.feature() < 0 || node.feature() >= featureCount
                    || node.left() <= i || node.right() <= i
                    || node.left() >= nodes.size() || node.right() >= nodes.size()) {
                    throw new IllegalArgumentException("트리 분기 번호가 잘못됐다");
                }
                parents[node.left()]++;
                parents[node.right()]++;
            }
        }
        for (int i = 1; i < parents.length; i++) {
            if (parents[i] != 1) {
                throw new IllegalArgumentException("연결되지 않거나 중복 연결된 트리 노드다");
            }
        }
        return List.copyOf(nodes);
    }

    public int featureCount() {
        return featureCount;
    }

    public Kind kind() {
        return kind;
    }

    public double[] predict(double[] features) {
        if (features.length != featureCount) {
            throw new IllegalArgumentException("입력 열 수가 모델과 다르다");
        }
        for (double value : features) {
            if (Double.isInfinite(value)) {
                throw new IllegalArgumentException("입력은 무한대가 될 수 없다");
            }
        }
        double[] raw = baseline.clone();
        for (List<List<Node>> step : iterations) {
            for (int c = 0; c < step.size(); c++) {
                List<Node> tree = step.get(c);
                Node node = tree.getFirst();
                while (!node.leaf()) {
                    double x = features[node.feature()];
                    boolean left = Double.isNaN(x) ? node.missingLeft() : x <= node.threshold();
                    node = tree.get(left ? node.left() : node.right());
                }
                raw[c] += node.value();
            }
        }
        for (double value : raw) {
            requireFinite(value);
        }
        if (kind == Kind.BINARY) {
            // Stable inverse logit, with no operational prior-shift or score-only clipping.
            double positive = raw[0] >= 0 ? 1.0 / (1.0 + Math.exp(-raw[0]))
                : Math.exp(raw[0]) / (1.0 + Math.exp(raw[0]));
            return new double[]{1.0 - positive, positive};
        }
        if (kind == Kind.MULTICLASS) {
            double maximum = Math.max(raw[0], Math.max(raw[1], raw[2]));
            double total = 0;
            for (int i = 0; i < raw.length; i++) {
                raw[i] = Math.exp(raw[i] - maximum);
                total += raw[i];
            }
            for (int i = 0; i < raw.length; i++) {
                raw[i] /= total;
            }
        }
        return raw;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("모델 값이 유한하지 않다");
        }
    }
}
