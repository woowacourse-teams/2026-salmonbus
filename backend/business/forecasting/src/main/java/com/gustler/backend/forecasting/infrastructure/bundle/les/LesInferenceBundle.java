package com.gustler.backend.forecasting.infrastructure.bundle.les;

import com.gustler.backend.forecasting.domain.model.les.LesSeatPredictor;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble.Kind;
import com.gustler.backend.forecasting.domain.model.les.NumericTreeEnsemble.Node;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 검증용 레스 묶음. 기존 운영 활성화 경로에는 등록하지 않는다. */
public final class LesInferenceBundle {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> ROUTES = List.of("3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    private static final String SEAT_ORDER = "f4e11ff4c97b5fa88ef22ec37169c2698da0020efb2a746ea35012108dbd7d18";
    private static final String FULL_ORDER = "c00e89b56ec334f88aec52842102d2745097ac18222c06194f2affa9df4e2095";
    private final LesSeatPredictor predictor;
    private final Map<String, JsonNode> statistics;

    private LesInferenceBundle(LesSeatPredictor predictor, Map<String, JsonNode> statistics) {
        this.predictor = predictor;
        this.statistics = Map.copyOf(statistics);
    }

    public LesSeatPredictor predictor() {
        return predictor;
    }

    /** 아직 온라인 특징 계산기가 없으므로 조회용 통계 JSON 복사본만 제공한다. */
    public JsonNode statistics(String route) {
        JsonNode node = statistics.get(route);
        if (node == null) {
            throw new IllegalArgumentException("통계가 없는 노선이다");
        }
        return node.deepCopy();
    }

    public static LesInferenceBundle load(Path directory) throws IOException {
        JsonNode manifest = JSON.readTree(Files.readAllBytes(directory.resolve("manifest.json")));
        require(text(manifest, "schema").equals("les-inference-bundle-v1"), "번들 스키마가 다르다");
        require(text(manifest, "model").equals("les-gate-base-and-pool"), "모델 종류가 다르다");
        require(!bool(manifest, "serving_ready"), "검증용 로더로 운영 묶음을 활성화할 수 없다");
        require(texts(manifest.get("output_targets")).equals(List.of("after-alighting-estimated", "departure-zero")), "출력 의미가 다르다");
        JsonNode declarations = manifest.get("files");
        require(declarations != null && declarations.isObject(), "파일 목록이 없다");
        Map<String, JsonNode> files = new HashMap<>();
        for (String name : declarations.propertyNames()) {
            require(!name.contains("/") && !name.contains("\\") && !name.equals(".."), "잘못된 파일 이름이다");
            byte[] content = Files.readAllBytes(directory.resolve(name));
            require(digest(content).equals(text(declarations, name)), "파일 해시가 다르다: " + name);
            files.put(name, JSON.readTree(content));
        }
        JsonNode contract = required(files, "feature-contract.json");
        require(text(contract, "schema").equals("les-feature-contract-v1"), "입력 계약이 다르다");
        require(orderDigest(contract, "seat_features").equals(SEAT_ORDER), "좌석 입력 순서가 다르다");
        require(orderDigest(contract, "probability_features").equals(FULL_ORDER), "확률 입력 순서가 다르다");
        require(!bool(contract, "prior_shift"), "원본에 없는 확률 보정이다");
        require(text(contract, "seat_dtype").equals("float32"), "좌석 정밀도가 다르다");
        require(text(contract, "probability_dtype").equals("float64 from float32 X_time + int64 h"), "확률 정밀도가 다르다");
        require(text(contract, "seat_target").equals("after-alighting-estimated")
            && text(contract, "probability_target").equals("departure-zero"), "입력 계약의 출력 의미가 다르다");
        JsonNode statsManifest = required(files, "statistics-manifest.json");
        JsonNode routeReference = required(files, "route-reference.json");
        List<String> referencedRoutes = new ArrayList<>();
        for (JsonNode route : array(routeReference.get("routes"))) {
            referencedRoutes.add(text(route, "modelRoute"));
        }
        for (String route : ROUTES) {
            require(referencedRoutes.stream().filter(route::equals).count() == 1, "노선 참조가 없거나 중복됐다");
        }
        require(text(statsManifest, "schema").equals("les-statistics-v1"), "통계 스키마가 다르다");
        require(texts(statsManifest.get("routes")).equals(ROUTES), "통계 노선 순서가 다르다");
        Map<String, JsonNode> statistics = new HashMap<>();
        for (String route : ROUTES) {
            JsonNode stats = required(files, "statistics-" + route + ".json");
            require(text(stats, "route").equals(route), "통계 노선이 다르다");
            require(number(stats.get("min_per_stop")) > 0, "정류장 이동시간이 잘못됐다");
            require(stats.has("tables") && stats.has("alighting") && stats.has("route_metadata"), "통계 자료가 빠졌다");
            statistics.put(route, stats);
        }
        JsonNode constants = required(files, "constants.json");
        require(text(constants, "target").equals("after"), "좌석 목표가 다르다");
        require(integers(constants.get("classes")).equals(List.of(0, 1, 2)), "gate 클래스 순서가 다르다");
        LesSeatPredictor predictor = new LesSeatPredictor(model(required(files, "base.json")),
            model(required(files, "gate.json")), model(required(files, "pool.json")), number(constants.get("middle")));
        return new LesInferenceBundle(predictor, statistics);
    }

    static NumericTreeEnsemble model(JsonNode root) {
        require(text(root, "schema").equals("les-numeric-hgb-v1"), "트리 스키마가 다르다");
        Kind kind = switch (text(root, "kind")) {
            case "regression" -> Kind.REGRESSION;
            case "multiclass" -> Kind.MULTICLASS;
            case "binary" -> Kind.BINARY;
            default -> throw new IllegalArgumentException("알 수 없는 트리 종류다");
        };
        List<Integer> classes = integers(root.get("classes"));
        require(classes.equals(kind == Kind.MULTICLASS ? List.of(0, 1, 2)
            : kind == Kind.BINARY ? List.of(0, 1) : List.of()), "트리 클래스 순서가 다르다");
        require(bool(root, "leaf_values_include_learning_rate"), "잎의 학습률 의미가 다르다");
        require(texts(root.get("node_fields")).equals(List.of("feature", "threshold", "missing_left", "left", "right", "value", "leaf")), "노드 열 순서가 다르다");
        List<List<List<Node>>> iterations = new ArrayList<>();
        for (JsonNode step : array(root.get("trees"))) {
            List<List<Node>> trees = new ArrayList<>();
            for (JsonNode tree : array(step)) {
                List<Node> nodes = new ArrayList<>();
                for (JsonNode n : array(tree)) {
                    require(n.isArray() && n.size() == 7, "노드 크기가 잘못됐다");
                    nodes.add(new Node(integer(n.get(0)), number(n.get(1)), booleanValue(n.get(2)),
                        integer(n.get(3)), integer(n.get(4)), number(n.get(5)), booleanValue(n.get(6))));
                }
                trees.add(nodes);
            }
            iterations.add(trees);
        }
        List<Double> baseline = new ArrayList<>();
        for (JsonNode value : array(root.get("baseline"))) {
            baseline.add(number(value));
        }
        return new NumericTreeEnsemble(integer(root.get("feature_count")), kind,
            baseline.stream().mapToDouble(Double::doubleValue).toArray(), iterations);
    }

    private static JsonNode required(Map<String, JsonNode> files, String name) {
        JsonNode node = files.get(name);
        require(node != null && node.isObject(), "필수 파일이 없다: " + name);
        return node;
    }

    private static String orderDigest(JsonNode root, String name) {
        return digest(String.join("\n", texts(root.get(name))).getBytes(StandardCharsets.UTF_8));
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        for (JsonNode value : array(node)) {
            require(value.isString(), "문자열 배열이 아니다");
            values.add(value.asString());
        }
        return values;
    }

    private static List<Integer> integers(JsonNode node) {
        List<Integer> values = new ArrayList<>();
        for (JsonNode value : array(node)) {
            // scikit-learn retains the float64 label dtype for pool_gb classes 0.0/1.0.
            double label = number(value);
            require(label >= Integer.MIN_VALUE && label <= Integer.MAX_VALUE && label == Math.rint(label), "클래스 값이 정수가 아니다");
            values.add((int) label);
        }
        return values;
    }

    private static JsonNode array(JsonNode node) {
        require(node != null && node.isArray(), "배열이 아니다");
        return node;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isString(), "문자열 필드가 아니다: " + field);
        return value.asString();
    }

    private static int integer(JsonNode value) {
        require(value != null && value.isIntegralNumber() && value.canConvertToInt(), "정수가 아니다");
        return value.intValue();
    }

    private static double number(JsonNode value) {
        require(value != null && value.isNumber() && Double.isFinite(value.doubleValue()), "유한한 숫자가 아니다");
        return value.doubleValue();
    }

    private static boolean bool(JsonNode node, String name) {
        return booleanValue(node.get(name));
    }

    private static boolean booleanValue(JsonNode value) {
        require(value != null && value.isBoolean(), "불리언 값이 아니다");
        return value.booleanValue();
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
