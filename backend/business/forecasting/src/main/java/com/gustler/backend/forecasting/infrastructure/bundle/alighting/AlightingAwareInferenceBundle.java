package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatPredictor;
import com.gustler.backend.forecasting.domain.model.alighting.NumericTreeEnsemble;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 원본 파일·수치 대조·운영 검증 증거를 검사하는 하차 반영 불변 묶음. */
public final class AlightingAwareInferenceBundle {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SERVING_ROUTES = List.of(
        "3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500");
    private static final String SEAT_ORDER = "f4e11ff4c97b5fa88ef22ec37169c2698da0020efb2a746ea35012108dbd7d18";
    private static final String FULL_ORDER = "c00e89b56ec334f88aec52842102d2745097ac18222c06194f2affa9df4e2095";
    private final AlightingAwareSeatPredictor predictor;
    private final Map<String, JsonNode> statistics;
    private final JsonNode manifest;
    private final JsonNode statisticsManifest;
    private final String digest;

    private AlightingAwareInferenceBundle(
        AlightingAwareSeatPredictor predictor,
        Map<String, JsonNode> statistics,
        JsonNode manifest,
        JsonNode statisticsManifest,
        String digest
    ) {
        this.predictor = predictor;
        this.statistics = Map.copyOf(statistics);
        this.manifest = manifest.deepCopy();
        this.statisticsManifest = statisticsManifest.deepCopy();
        this.digest = digest;
    }

    public AlightingAwareSeatPredictor predictor() {
        return predictor;
    }

    /** 호출자가 고정 통계를 변경하지 못하도록 JSON 복사본을 제공한다. */
    public JsonNode statistics(String route) {
        JsonNode node = statistics.get(route);
        if (node == null) {
            throw new IllegalArgumentException("통계가 없는 노선이다");
        }
        return node.deepCopy();
    }

    public static AlightingAwareInferenceBundle load(Path directory) throws IOException {
        return load(directory, false);
    }

    /** 고정 통계 준비용. 비활성 번들도 읽되 파일·입력 계약 검증은 그대로 수행한다. */
    public static AlightingAwareInferenceBundle loadForStatistics(Path directory) throws IOException {
        JsonNode manifest = JSON.readTree(readFile(directory.resolve("manifest.json")));
        return load(directory, bool(manifest, "serving_ready"));
    }

    public static AlightingAwareInferenceBundle loadForServing(Path directory) throws IOException {
        return load(directory, true);
    }

    public String digest() {
        return digest;
    }

    public List<String> routes() {
        return List.copyOf(texts(statisticsManifest.get("routes")));
    }

    public JsonNode manifest() {
        return manifest.deepCopy();
    }

    public Set<LocalDate> holidays() {
        return texts(statisticsManifest.get("holiday_dates")).stream().map(LocalDate::parse)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static AlightingAwareInferenceBundle load(Path directory, final boolean serving) throws IOException {
        require(!Files.isSymbolicLink(directory), "번들 디렉터리는 symlink가 아니어야 한다");
        byte[] manifestBytes = readFile(directory.resolve("manifest.json"));
        JsonNode manifest = JSON.readTree(manifestBytes);
        require(text(manifest, "schema").equals("alighting-inference-bundle-v1"), "번들 스키마가 다르다");
        require(text(manifest, "model").equals("alighting-gate-base-and-pool"), "모델 종류가 다르다");
        require(bool(manifest, "serving_ready") == serving, "서빙 검증을 완료한 묶음만 운영에 적재할 수 있다");
        require(texts(manifest.get("output_targets")).equals(List.of("after-alighting-estimated", "departure-zero")),
            "출력 의미가 다르다");
        Map<String, JsonNode> files = readDeclaredFiles(directory, manifest);
        validateFeatureContract(files);
        JsonNode statsManifest = required(files, "statistics-manifest.json");
        require(text(statsManifest, "schema").equals("alighting-statistics-v1"), "통계 스키마가 다르다");
        List<String> routes = texts(statsManifest.get("routes"));
        require(hasValidRoutes(routes), "통계 노선 목록이 비었거나 잘못됐다");
        JsonNode routeReference = required(files, "route-reference.json");
        List<String> referencedRoutes = new ArrayList<>();
        for (JsonNode route : array(routeReference.get("routes"))) {
            referencedRoutes.add(text(route, "modelRoute"));
        }
        for (String route : routes) {
            require(referencedRoutes.stream().filter(route::equals).count() == 1, "노선 참조가 없거나 중복됐다");
        }
        Map<String, JsonNode> statistics = readStatistics(files, routes);
        AlightingAwareSeatPredictor predictor = readPredictor(files);
        if (serving) {
            verifyServing(manifest, files);
            verifyGolden(predictor, required(files, "golden.json"));
            verifyGolden(predictor, required(files, "boundaries.json"));
        }
        return new AlightingAwareInferenceBundle(predictor, statistics, manifest, statsManifest, digest(manifestBytes));
    }

    private static Map<String, JsonNode> readDeclaredFiles(Path directory, JsonNode manifest) throws IOException {
        JsonNode declarations = manifest.get("files");
        require(declarations != null && declarations.isObject(), "파일 목록이 없다");
        Map<String, JsonNode> files = new HashMap<>();
        for (String name : declarations.propertyNames()) {
            require(hasSafeFileName(name), "잘못된 파일 이름이다");
            byte[] content = readFile(directory.resolve(name));
            require(digest(content).equals(text(declarations, name)), "파일 해시가 다르다: " + name);
            files.put(name, JSON.readTree(content));
        }
        return files;
    }

    private static void validateFeatureContract(Map<String, JsonNode> files) {
        JsonNode contract = required(files, "feature-contract.json");
        require(text(contract, "schema").equals("alighting-feature-contract-v1"), "입력 계약이 다르다");
        require(orderDigest(contract, "seat_features").equals(SEAT_ORDER), "좌석 입력 순서가 다르다");
        require(orderDigest(contract, "probability_features").equals(FULL_ORDER), "확률 입력 순서가 다르다");
        require(!bool(contract, "prior_shift"), "원본에 없는 확률 보정이다");
        require(text(contract, "seat_dtype").equals("float32"), "좌석 정밀도가 다르다");
        require(text(contract, "probability_dtype").equals("float64 from float32 X_time + int64 h"), "확률 정밀도가 다르다");
        require(text(contract, "seat_target").equals("after-alighting-estimated")
            && text(contract, "probability_target").equals("departure-zero"), "입력 계약의 출력 의미가 다르다");
    }

    private static boolean hasValidRoutes(List<String> routes) {
        return !routes.isEmpty() && routes.stream().distinct().count() == routes.size()
            && routes.stream().allMatch(route -> route.matches("[A-Za-z0-9][A-Za-z0-9-]{0,31}"));
    }

    private static Map<String, JsonNode> readStatistics(Map<String, JsonNode> files, List<String> routes) {
        Map<String, JsonNode> statistics = new HashMap<>();
        for (String route : routes) {
            JsonNode stats = required(files, "statistics-" + route + ".json");
            require(text(stats, "route").equals(route), "통계 노선이 다르다");
            require(number(stats.get("min_per_stop")) > 0, "정류장 이동시간이 잘못됐다");
            require(hasRequiredStatistics(stats), "통계 자료가 빠졌다");
            statistics.put(route, stats);
        }
        return statistics;
    }

    private static AlightingAwareSeatPredictor readPredictor(Map<String, JsonNode> files) {
        JsonNode constants = required(files, "constants.json");
        require(text(constants, "target").equals("after"), "좌석 목표가 다르다");
        require(integers(constants.get("classes")).equals(List.of(0, 1, 2)), "gate 클래스 순서가 다르다");
        return new AlightingAwareSeatPredictor(model(required(files, "base.json")),
            model(required(files, "gate.json")), model(required(files, "pool.json")), number(constants.get("middle")));
    }

    private static byte[] readFile(Path file) throws IOException {
        require(!Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
            "일반 파일만 읽을 수 있다: " + file);
        require(Files.size(file) <= 32L * 1024 * 1024, "번들 파일은 32MiB 이하여야 한다");
        return Files.readAllBytes(file);
    }

    private static void verifyServing(JsonNode manifest, Map<String, JsonNode> files) {
        JsonNode runtime = manifest.get("runtime");
        require(runtime != null && runtime.isObject(), "서빙 계약이 없다");
        require(text(runtime, "feature_contract_version").equals("alighting-online-v1"), "서빙 입력 판이 다르다");
        require(text(runtime, "release_id").matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"), "출시 식별자가 잘못됐다");
        List<String> routes = texts(required(files, "statistics-manifest.json").get("routes"));
        final boolean confirmed = AlightingAwareConfirmedServingVerification.isConfirmed(files);
        verifyServingRoutes(runtime, files, routes, confirmed);
        verifyStatisticsCutoff(runtime, files, routes);
        require(text(runtime, "scope").equals("weekday-rush-6-10-17-21-horizon-1-12"), "지원 시각/거리가 다르다");
        String sourceDigest = text(runtime, "source_manifest_sha256");
        require(sourceDigest.matches("[a-f0-9]{64}"), "원본 묶음 식별자가 없다");
        JsonNode original = required(files, "verification-source-manifest.json");
        require(text(manifest.get("files"), "verification-source-manifest.json").equals(sourceDigest),
            "검증한 원본 manifest와 다르다");
        require(!bool(original, "serving_ready"), "검증 원본은 비활성 묶음이어야 한다");
        for (String name : original.get("files").propertyNames()) {
            require(text(original.get("files"), name).equals(text(manifest.get("files"), name)),
                "검증 이후 원본 파일이 바뀌었다: " + name);
        }
        for (String check : List.of("feature_parity", "historical_replay", "settlement", "runtime_validation")) {
            JsonNode receipt = required(files, "verification-" + check + ".json");
            require(hasMatchingVerification(receipt, check, sourceDigest, manifest),
                "서빙 검증 증거가 다르다: " + check);
            JsonNode evidence = required(files, "evidence-" + check + ".json");
            if (confirmed) {
                AlightingAwareConfirmedServingVerification.verify(check, receipt, evidence, sourceDigest, routes);
            }
        }
    }

    private static void verifyServingRoutes(
        JsonNode runtime,
        Map<String, JsonNode> files,
        List<String> routes,
        final boolean confirmed
    ) {
        if (confirmed) {
            require(texts(runtime.get("routes")).equals(routes), "운영 노선과 검증 대상 노선이 다르다");
            AlightingAwareConfirmedServingVerification.verifyContract(runtime, files, routes);
            return;
        }
        require(texts(runtime.get("routes")).equals(SERVING_ROUTES), "검증된 8개 노선만 서빙할 수 있다");
        require(routes.equals(SERVING_ROUTES), "검증된 8개 노선의 통계만 서빙할 수 있다");
    }

    private static boolean hasMatchingVerification(
        JsonNode receipt,
        String check,
        String sourceDigest,
        JsonNode manifest
    ) {
        return text(receipt, "schema").equals("alighting-serving-verification-v1")
            && text(receipt, "check").equals(check) && text(receipt, "status").equals("PASS")
            && text(receipt, "source_manifest_sha256").equals(sourceDigest)
            && text(receipt, "feature_contract_version").equals("alighting-online-v1")
            && integer(receipt.get("verified_cases")) > 0
            && text(receipt, "evidence_file").equals("evidence-" + check + ".json")
            && text(receipt, "evidence_sha256").equals(text(manifest.get("files"), "evidence-" + check + ".json"));
    }

    private static void verifyStatisticsCutoff(
        JsonNode runtime,
        Map<String, JsonNode> files,
        List<String> routes
    ) {
        Instant through = Instant.parse(text(runtime, "data_through"));
        for (String route : routes) {
            JsonNode stats = required(files, "statistics-" + route + ".json");
            for (String cutoff : List.of("events_known_through", "observations_through")) {
                Instant used = OffsetDateTime.parse(text(stats, cutoff).replace(' ', 'T')).toInstant();
                require(!through.isBefore(used), "서빙 시작 기준보다 미래 통계를 사용했다: " + route);
            }
        }
    }

    private static void verifyGolden(AlightingAwareSeatPredictor predictor, JsonNode cases) {
        JsonNode inputs = array(cases.get("seat_inputs"));
        require(!inputs.isEmpty(), "대조 입력이 없다");
        for (int i = 0; i < inputs.size(); i++) {
            AlightingAwareSeatPredictor.Prediction actual = predictor.predict(vector(inputs.get(i)),
                vector(cases.get("probability_inputs").get(i)));
            double[] expected = { number(cases.get("expected_base").get(i)),
                number(cases.get("expected_gate_probabilities").get(i).get(0)),
                number(cases.get("expected_gate_probabilities").get(i).get(1)),
                number(cases.get("expected_gate_probabilities").get(i).get(2)),
                number(cases.get("expected_seats").get(i)), number(cases.get("expected_departure_full").get(i)) };
            double[] result = { actual.base(), actual.gateZero(), actual.gateLow(), actual.gateOther(),
                actual.afterAlightingSeats(), actual.departureFullProbability() };
            for (int j = 0; j < result.length; j++) {
                require(Math.abs(expected[j] - result[j]) <= 1e-9, "대조 예측이 다르다: " + i + "/" + j);
            }
        }
    }

    private static double[] vector(JsonNode input) {
        JsonNode values = array(input);
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = nullableNumber(values.get(i));
        }
        return result;
    }

    private static double nullableNumber(JsonNode value) {
        if (value.isNull()) {
            return Double.NaN;
        }
        return number(value);
    }

    private static List<Integer> expectedClasses(NumericTreeEnsemble.Kind kind) {
        return switch (kind) {
        case MULTICLASS -> List.of(0, 1, 2);
        case BINARY -> List.of(0, 1);
        case REGRESSION -> List.of();
        };
    }

    static NumericTreeEnsemble model(JsonNode root) {
        require(text(root, "schema").equals("alighting-numeric-hgb-v1"), "트리 스키마가 다르다");
        NumericTreeEnsemble.Kind kind = switch (text(root, "kind")) {
        case "regression" -> NumericTreeEnsemble.Kind.REGRESSION;
        case "multiclass" -> NumericTreeEnsemble.Kind.MULTICLASS;
        case "binary" -> NumericTreeEnsemble.Kind.BINARY;
        default -> throw new IllegalArgumentException("알 수 없는 트리 종류다");
        };
        List<Integer> classes = integers(root.get("classes"));
        require(classes.equals(expectedClasses(kind)), "트리 클래스 순서가 다르다");
        require(bool(root, "leaf_values_include_learning_rate"), "잎의 학습률 의미가 다르다");
        require(texts(root.get("node_fields"))
            .equals(List.of("feature", "threshold", "missing_left", "left", "right", "value", "leaf")), "노드 열 순서가 다르다");
        List<List<List<NumericTreeEnsemble.Node>>> iterations = new ArrayList<>();
        for (JsonNode step : array(root.get("trees"))) {
            List<List<NumericTreeEnsemble.Node>> trees = new ArrayList<>();
            for (JsonNode tree : array(step)) {
                List<NumericTreeEnsemble.Node> nodes = new ArrayList<>();
                for (JsonNode n : array(tree)) {
                    require(n.isArray() && n.size() == 7, "노드 크기가 잘못됐다");
                    nodes.add(new NumericTreeEnsemble.Node(integer(n.get(0)), number(n.get(1)), booleanValue(n.get(2)),
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

    private static boolean hasSafeFileName(String name) {
        return !name.contains("/") && !name.contains("\\") && !name.equals("..");
    }

    private static boolean hasRequiredStatistics(JsonNode statistics) {
        return statistics.has("tables") && statistics.has("alighting") && statistics.has("route_metadata");
    }

    private static boolean isIntegerLabel(final double label) {
        return label >= Integer.MIN_VALUE && label <= Integer.MAX_VALUE && label == Math.rint(label);
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
            final double label = number(value);
            require(isIntegerLabel(label),
                "클래스 값이 정수가 아니다");
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
        return List.of("schema", "model", "input_profile", "feature_contract_version", "label_version")
            .contains(field) ? canonical(value.asString()) : value.asString();
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

    private static void require(final boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
