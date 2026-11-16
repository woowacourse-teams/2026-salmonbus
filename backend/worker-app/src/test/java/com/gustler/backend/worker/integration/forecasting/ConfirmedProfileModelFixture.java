package com.gustler.backend.worker.integration.forecasting;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.ExtendedAlightingPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * 예보가 처리하는 확정 프로필 모델을 만든다. 통계는 진짜 객체이고 트리는 잎 하나짜리라 예측값이 상수다.
 * 출퇴근 모델과 시간 확장 모델을 모두 넣어 시험을 도는 시각과 무관하게 예측이 나온다.
 *
 * <p>정류장은 {@code s1}부터 {@code s<stopCount>}까지이고, {@code turn} 이하가 상행, 그 뒤가 하행이며 모두 승차할 수 있다.
 * DB의 {@code route_stop}도 같은 규칙으로 넣어야 모델의 노선 지원 판정과 되감기 판본 호환 검사를 통과한다.
 */
final class ConfirmedProfileModelFixture {
    private static final List<String> TABLES = List.of("lam", "lam_base", "net_tab", "full_tab", "dep_tab",
        "stop_net_base");
    private static final int TIME_BINS = 48;
    private static final int SEAT_FEATURES = 88;
    private static final int PROBABILITY_FEATURES = 80;
    private static final int EXTENDED_FEATURES = 6;

    private ConfirmedProfileModelFixture() {
    }

    static AlightingAwareSeatForecastModel model(String route, final int stopCount, final int turn) {
        return model(List.of(route), stopCount, turn);
    }

    /** 노선마다 정류장 구성과 계수가 같은 모델을 만든다. */
    static AlightingAwareSeatForecastModel model(List<String> routes, final int stopCount, final int turn) {
        AlightingAwareSeatPredictor predictor = new AlightingAwareSeatPredictor(
            ensemble(SEAT_FEATURES, NumericTreeEnsemble.Kind.REGRESSION, new double[] {10}),
            ensemble(SEAT_FEATURES, NumericTreeEnsemble.Kind.MULTICLASS, new double[] {0, 0, 0}),
            ensemble(PROBABILITY_FEATURES, NumericTreeEnsemble.Kind.BINARY, new double[] {0}), 1.3);
        Map<String, AlightingAwareRouteStatistics> statistics = new HashMap<>();
        Map<String, Map<Integer, String>> evidence = new HashMap<>();
        Map<String, ExtendedAlightingPredictor> extended = new HashMap<>();
        for (String route : routes) {
            statistics.put(route, statistics(route, stopCount, turn));
            evidence.put(route, evidence(stopCount, turn));
            extended.put(route, new ExtendedAlightingPredictor(
                ensemble(EXTENDED_FEATURES, NumericTreeEnsemble.Kind.REGRESSION, new double[] {8}),
                ensemble(EXTENDED_FEATURES, NumericTreeEnsemble.Kind.BINARY, new double[] {0})));
        }
        return new AlightingAwareSeatForecastModel(predictor, statistics, evidence, "1".repeat(64), extended);
    }

    private static AlightingAwareRouteStatistics statistics(String route, final int stopCount, final int turn) {
        List<AlightingAwareRouteStatistics.Stop> stops = new ArrayList<>();
        for (int order = 1; order <= stopCount; order++) {
            stops.add(new AlightingAwareRouteStatistics.Stop(order, "s" + order, "정류장" + order, order > turn, true));
        }
        Map<String, Map<String, Double>> tables = new HashMap<>();
        for (String table : TABLES) {
            tables.put(table, cells(stopCount));
        }
        Map<String, List<Double>> bands = new HashMap<>();
        for (int order = 1; order <= stopCount; order++) {
            bands.put(order + "|morning", List.of(12.0, 0.4, 2.0));
            bands.put(order + "|evening", List.of(9.0, 0.6, 3.0));
        }
        return new AlightingAwareRouteStatistics(route, stops, 2, tables, bands, Set.of(), true);
    }

    private static Map<String, Double> cells(final int stopCount) {
        Map<String, Double> cells = new HashMap<>();
        for (int order = 1; order <= stopCount; order++) {
            for (String day : List.of("work", "off")) {
                cells.put(order + "|" + day, (order % 5) * 0.3);
                for (int bin = 0; bin < TIME_BINS; bin++) {
                    cells.put(order + "|" + day + "|" + bin, ((order + bin) % 7) * 0.5 - 1);
                }
            }
        }
        return cells;
    }

    /**
     * 정류장마다 정답 계산이 읽는 근거다. 시간 확장 칸 계수와 출퇴근 두 띠의 정류장 계수를 모두 넣어, 통과가 어느 시각에 닫혀도
     * 정답을 계산할 수 있다.
     */
    private static Map<Integer, String> evidence(final int stopCount, final int turn) {
        String parameters = "\"p\":0.3,\"lam\":[1.2,1.3,1.4,1.5,1.6,1.7,1.8,1.9],\"kappa\":1,\"r\":2";
        String cells = String.join(",", AlightingTimeCell.extendedKeys().stream()
            .map(key -> "\"%s\":{%s}".formatted(key, parameters))
            .toList());
        String realStops = IntStream.rangeClosed(1, stopCount).boxed().toList().toString();
        Map<Integer, String> evidence = new HashMap<>();
        for (int order = 1; order <= stopCount; order++) {
            evidence.put(order, """
                {"turn":%d,"realStopOrders":%s,"holidays":[],"extendedSettlement":{%s},
                 "alighting":{"stops":[{"stop":%d,"band":"morning",%s},{"stop":%d,"band":"evening",%s}]}}
                """.formatted(turn, realStops, cells, order, parameters, order, parameters));
        }
        return evidence;
    }

    private static NumericTreeEnsemble ensemble(final int features, NumericTreeEnsemble.Kind kind, double[] baseline) {
        List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
        for (int tree = 0; tree < baseline.length; tree++) {
            trees.add(List.of(new NumericTreeEnsemble.Node(0, 0, false, 0, 0, 0.1 * (tree + 1), true)));
        }
        return new NumericTreeEnsemble(features, kind, baseline, List.of(trees));
    }
}
