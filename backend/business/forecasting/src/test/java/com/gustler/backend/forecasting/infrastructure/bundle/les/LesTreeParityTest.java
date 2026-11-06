package com.gustler.backend.forecasting.infrastructure.bundle.les;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LesTreeParityTest {
    @Test
    void 경곗값과_결측값은_원본과_같은_분기로_이동한다() {
        NumericTreeEnsemble tree = new NumericTreeEnsemble(1, NumericTreeEnsemble.Kind.REGRESSION, new double[] { 2 },
            List.of(List.of(List.of(new NumericTreeEnsemble.Node(0, 3, false, 1, 2, 0, false), leaf(4), leaf(9)))));
        assertSoftly(softly -> {
            softly.assertThat(tree.predict(new double[] { 3 })).containsExactly(6);
            softly.assertThat(tree.predict(new double[] { Math.nextUp(3.0) })).containsExactly(11);
            softly.assertThat(tree.predict(new double[] { Double.NaN })).containsExactly(11);
        });
    }

    @Test
    void 결측값은_임계값과_독립적으로_왼쪽으로_이동할_수_있다() {
        NumericTreeEnsemble tree = new NumericTreeEnsemble(1, NumericTreeEnsemble.Kind.REGRESSION, new double[] { 0 },
            List.of(List.of(List.of(new NumericTreeEnsemble.Node(0, 3, true, 1, 2, 0, false), leaf(4), leaf(9)))));
        assertThat(tree.predict(new double[] { Double.NaN })).containsExactly(4);
    }

    @Test
    void 이진_확률은_임의로_제한하지_않는다() {
        assertSoftly(softly -> {
            softly.assertThat(constant(1, NumericTreeEnsemble.Kind.BINARY, -1000).predict(new double[] { 0 }))
                .containsExactly(1,
                    0);
            softly.assertThat(constant(1, NumericTreeEnsemble.Kind.BINARY, 1000).predict(new double[] { 0 }))
                .containsExactly(0,
                    1);
        });
    }

    @Test
    void 좌석_기본값은_분류_확률과_합치기_전에_상한을_적용하지_않는다() {
        LesSeatPredictor model = new LesSeatPredictor(constant(88, NumericTreeEnsemble.Kind.REGRESSION, 100),
            constant(88, NumericTreeEnsemble.Kind.MULTICLASS, 0, -1000, 0),
            constant(80, NumericTreeEnsemble.Kind.BINARY, 0), 1.3);
        LesSeatPredictor.Prediction prediction = model.predict(new double[88], new double[80]);
        assertSoftly(softly -> {
            softly.assertThat(prediction.afterAlightingSeats()).isEqualTo(50);
            softly.assertThat(prediction.departureFullProbability()).isEqualTo(0.5);
        });
    }

    @Test
    void 좌석_입력은_트리_비교_전에_float32로_변환한다() {
        NumericTreeEnsemble base = new NumericTreeEnsemble(88, NumericTreeEnsemble.Kind.REGRESSION, new double[] { 0 },
            List.of(List.of(List.of(new NumericTreeEnsemble.Node(0, 1, true, 1, 2, 0, false), leaf(4), leaf(9)))));
        LesSeatPredictor predictor = new LesSeatPredictor(base,
            constant(88, NumericTreeEnsemble.Kind.MULTICLASS, -1000, -1000, 0),
            constant(80, NumericTreeEnsemble.Kind.BINARY, 0), 1.3);
        double[] inputs = new double[88];
        inputs[0] = Math.nextUp(1.0);
        assertThat(predictor.predict(inputs, new double[80]).base()).isEqualTo(4);
    }

    @Test
    void 잘못된_입력_차원과_무한대와_순환_트리를_거부한다() {
        NumericTreeEnsemble model = constant(1, NumericTreeEnsemble.Kind.REGRESSION, 0);
        assertThatThrownBy(() -> model.predict(new double[2])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> model.predict(new double[] { Double.POSITIVE_INFINITY }))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NumericTreeEnsemble(1, NumericTreeEnsemble.Kind.REGRESSION, new double[] { 0 },
            List.of(List.of(List.of(new NumericTreeEnsemble.Node(0, 1, true, 0, 0, 0, false))))))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 저장된_클래스_순서를_검증한다() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"multiclass","classes":[2,1,0]}
            """;
        assertThatThrownBy(() -> LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("클래스");
    }

    @Test
    void 실수로_저장된_클래스는_정수_의미를_유지한다() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"binary","classes":[0.0,1.0],"feature_count":1,
             "leaf_values_include_learning_rate":true,"baseline":[0],
             "node_fields":["feature","threshold","missing_left","left","right","value","leaf"],
             "trees":[[[[0,0,false,0,0,0,true]]]]}
            """;
        assertThat(LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)).predict(new double[] { 0 }))
            .containsExactly(0.5, 0.5);
    }

    @Test
    void 저장된_잎에는_학습률이_이미_반영되어_있다() {
        String json = """
            {"schema":"les-numeric-hgb-v1","kind":"regression","classes":[],"feature_count":1,
             "leaf_values_include_learning_rate":true,"baseline":[10],
             "node_fields":["feature","threshold","missing_left","left","right","value","leaf"],
             "trees":[[[[0,0,false,0,0,0.25,true]]],[[[0,0,false,0,0,-0.1,true]]]]}
            """;
        assertThat(LesInferenceBundle.model(JsonMapper.builder().build().readTree(json)).predict(new double[] { 0 }))
            .containsExactly(10.15);
    }

    private static NumericTreeEnsemble.Node leaf(final double value) {
        return new NumericTreeEnsemble.Node(0, 0, false, 0, 0, value, true);
    }

    private static NumericTreeEnsemble constant(final int count, NumericTreeEnsemble.Kind kind, double... baseline) {
        List<List<NumericTreeEnsemble.Node>> trees = Arrays.stream(baseline).mapToObj(v -> List.of(leaf(0))).toList();
        return new NumericTreeEnsemble(count, kind, baseline, List.of(trees));
    }

}
