package com.gustler.backend.forecasting.infrastructure.bundle.les;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble.Kind;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble.Node;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LesTreeParityTest {
    private static Node leaf(double value) {
        return new Node(0, 0, false, 0, 0, value, true);
    }

    private static NumericTreeEnsemble constant(int count, Kind kind, double... baseline) {
        List<List<Node>> trees = java.util.Arrays.stream(baseline).mapToObj(v -> List.of(leaf(0))).toList();
        return new NumericTreeEnsemble(count, kind, baseline, List.of(trees));
    }

    @Test
    void equalityAndMissingTakeTheirOriginalBranches() {
        var tree = new NumericTreeEnsemble(1, Kind.REGRESSION, new double[]{2},
            List.of(List.of(List.of(new Node(0, 3, false, 1, 2, 0, false), leaf(4), leaf(9)))));
        assertThat(tree.predict(new double[]{3})).containsExactly(6);
        assertThat(tree.predict(new double[]{Math.nextUp(3.0)})).containsExactly(11);
        assertThat(tree.predict(new double[]{Double.NaN})).containsExactly(11);
    }

    @Test
    void missingCanGoLeftIndependentlyOfThreshold() {
        var tree = new NumericTreeEnsemble(1, Kind.REGRESSION, new double[]{0},
            List.of(List.of(List.of(new Node(0, 3, true, 1, 2, 0, false), leaf(4), leaf(9)))));
        assertThat(tree.predict(new double[]{Double.NaN})).containsExactly(4);
    }

    @Test
    void binaryProbabilityDoesNotGetScoreClipping() {
        assertThat(constant(1, Kind.BINARY, -1000).predict(new double[]{0})).containsExactly(1, 0);
        assertThat(constant(1, Kind.BINARY, 1000).predict(new double[]{0})).containsExactly(0, 1);
    }

    @Test
    void baseIsNotCappedBeforeGateComposition() {
        var model = new LesSeatPredictor(constant(88, Kind.REGRESSION, 100),
            constant(88, Kind.MULTICLASS, 0, -1000, 0), constant(80, Kind.BINARY, 0), 1.3);
        var prediction = model.predict(new double[88], new double[80]);
        assertThat(prediction.afterAlightingSeats()).isEqualTo(50);
        assertThat(prediction.departureFullProbability()).isEqualTo(0.5);
    }

    @Test
    void inputsAreConvertedToFloat32BeforeSeatTreeComparison() {
        var base = new NumericTreeEnsemble(88, Kind.REGRESSION, new double[]{0},
            List.of(List.of(List.of(new Node(0, 1, true, 1, 2, 0, false), leaf(4), leaf(9)))));
        var predictor = new LesSeatPredictor(base, constant(88, Kind.MULTICLASS, -1000, -1000, 0),
            constant(80, Kind.BINARY, 0), 1.3);
        double[] inputs = new double[88];
        inputs[0] = Math.nextUp(1.0);
        assertThat(predictor.predict(inputs, new double[80]).base()).isEqualTo(4);
    }

    @Test
    void wrongWidthInfinityAndCyclesAreRejected() {
        var model = constant(1, Kind.REGRESSION, 0);
        assertThatThrownBy(() -> model.predict(new double[2])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.predict(new double[]{Double.POSITIVE_INFINITY})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumericTreeEnsemble(1, Kind.REGRESSION, new double[]{0},
            List.of(List.of(List.of(new Node(0, 1, true, 0, 0, 0, false)))))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void jsonClassOrderIsValidated() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"multiclass","classes":[2,1,0]}
            """;
        assertThatThrownBy(() -> LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("클래스");
    }

    @Test
    void floatingPointClassLabelsRetainTheirIntegerMeaning() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"binary","classes":[0.0,1.0],"feature_count":1,
             "leaf_values_include_learning_rate":true,"baseline":[0],
             "node_fields":["feature","threshold","missing_left","left","right","value","leaf"],
             "trees":[[[[0,0,false,0,0,0,true]]]]}
            """;
        assertThat(LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)).predict(new double[]{0}))
            .containsExactly(0.5, 0.5);
    }

    @Test
    void jsonLeavesAreAlreadyScaled() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"regression","classes":[],"feature_count":1,
             "leaf_values_include_learning_rate":true,"baseline":[10],
             "node_fields":["feature","threshold","missing_left","left","right","value","leaf"],
             "trees":[[[[0,0,false,0,0,0.25,true]]],[[[0,0,false,0,0,-0.1,true]]]]}
            """;
        assertThat(LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)).predict(new double[]{0}))
            .containsExactly(10.15);
    }
}
