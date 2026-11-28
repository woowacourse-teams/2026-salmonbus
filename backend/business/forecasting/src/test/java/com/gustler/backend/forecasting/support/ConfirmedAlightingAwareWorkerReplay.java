package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareInferenceBundle;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareServingBundle;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcForecastRecordStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 관측 하루치를 로컬 PostgreSQL에 넣고 worker의 예보와 채점을 그대로 돌려, 독립 계산한 기대값과 견준다. 운영에 연결하지
 * 않는다.
 *
 * <p>인자는 재생 자료 폴더, 비활성 번들 폴더, 아직 없는 출력 폴더, 그리고 선택으로 노선 하나다. 재생 자료 폴더의
 * {@code manifest.json}에 적힌 노선 파일마다 다음을 한다.
 * <ol>
 *   <li>파일의 해시를 확인하고 노선·판본·정류장·그날의 배치와 관측을 DB에 넣는다({@link WorkerReplayLoader}).</li>
 *   <li>그날의 배치를 착수 시각 순서로 예보에 넣는다. 예보가 실행·예측 기록·닫힌 통과를 저장하고, 통과가 닫힐 때 채점이 정답을
 *       저장한다({@link WorkerReplayRunner}).</li>
 *   <li>사례마다 입력·예측·정답과 정답의 근거를 기대값과 견준다({@link WorkerReplayVerification}).</li>
 * </ol>
 * 출력 폴더에 노선마다 {@code <노선>.json}을, 끝에 {@code report.json}을 쓴다. 번들의 노선이 모두 통과해야 보고서가 통과다. 통과하지
 * 못하면 보고서를 쓴 뒤 예외로 끝낸다. 보고서는 번들의 재생·정산 확인 영수증을 만드는 입력이다.
 *
 * <p>확인 범위에는 한계가 있다. 그날 앞의 관측은 재생하지 않으므로 전날부터 이어진 운행의 차량 상태는 그날의 첫 관측에서 다시
 * 시작한다. 그 차이가 입력에 닿는 사례는 입력이 달라 통과하지 못한다. 차량 정원 이력은 자료의 관측으로 흉내 낸 것이고 수집이
 * 걸러 낸 좌석은 반영하지 못한다.
 */
public final class ConfirmedAlightingAwareWorkerReplay {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String CAPACITY_POLICY = "vehicle-capacity-snapshot-v1";
    private static final String RELEASE_ID = "local-confirmed-replay";
    private static final String DATA_THROUGH = "2026-10-22T00:00:00+09:00";

    private final Stores stores;
    private final AlightingAwareInferenceBundle bundle;
    private final AlightingAwareSeatForecastModel model;
    private final RuntimeSnapshot runtime;

    private ConfirmedAlightingAwareWorkerReplay(Stores stores, AlightingAwareInferenceBundle bundle) {
        this.stores = stores;
        this.bundle = bundle;
        this.model = AlightingAwareServingBundle.readModel(bundle);
        this.runtime = activate(stores, bundle, model);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("재생 자료 폴더, 비활성 번들 폴더, 아직 없는 출력 폴더가 필요하다");
        }
        requireAbsent(Path.of(args[2]));
        SpringApplication application = new SpringApplication(ForecastingTestConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setDefaultProperties(Map.of("forecast.enabled", "true", "forecast.staleness", "5m",
            "forecast.batch-limit", "20", "spring.jpa.hibernate.ddl-auto", "validate",
            "spring.datasource.hikari.maximum-pool-size", "3", "spring.datasource.hikari.minimum-idle", "1"));
        try (ConfigurableApplicationContext context = application.run()) {
            Stores stores = new Stores(context.getBean(JdbcClient.class),
                context.getBean(PlatformTransactionManager.class), context.getBean(ModelDeploymentRepository.class),
                context.getBean(JdbcForecastRecordStore.class), context.getBean(StopPassStore.class),
                context.getBean(PassLabelStore.class));
            run(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), onlyRoute(args), stores);
        }
    }

    private static String onlyRoute(String[] args) {
        if (args.length == 4) {
            return args[3];
        }
        return null;
    }

    /**
     * 재생 자료의 노선을 재생하고 보고서를 쓴다.
     *
     * @param fixtures 재생 자료 폴더
     * @param bundleDirectory 비활성 번들 폴더
     * @param output 이 재생이 만들 출력 폴더. 이미 있으면 안 된다
     * @param onlyRoute 이 노선만 재생한다. 비어 있으면 자료의 노선을 모두 재생한다
     * @param stores 로컬 DB의 저장소
     * @return 통과한 보고서
     * @throws IllegalArgumentException 출력 폴더가 이미 있거나, 자료의 정원 정책·정답 판이 지금 것과 다르거나, 노선 파일의
     *     해시가 다르면 던진다
     * @throws IllegalStateException 보고서가 통과하지 못하면 보고서를 쓴 뒤 던진다
     */
    static Map<String, Object> run(
        Path fixtures,
        Path bundleDirectory,
        Path output,
        String onlyRoute,
        Stores stores
    ) throws IOException {
        requireAbsent(output);
        JsonNode manifest = JSON.readTree(Files.readAllBytes(fixtures.resolve("manifest.json")));
        requireCurrentContract(manifest);
        Files.createDirectory(output);
        ConfirmedAlightingAwareWorkerReplay replay = new ConfirmedAlightingAwareWorkerReplay(stores,
            AlightingAwareInferenceBundle.load(bundleDirectory));
        Map<String, Object> report = replay.report(replay.replayRoutes(fixtures, manifest, onlyRoute, output),
            onlyRoute);
        Path file = output.resolve("report.json");
        Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        if (!Boolean.TRUE.equals(report.get("passed"))) {
            throw new IllegalStateException("재생 결과가 기대값과 다르다: " + file);
        }
        return report;
    }

    private static void requireAbsent(Path output) {
        if (Files.exists(output)) {
            throw new IllegalArgumentException("출력 폴더가 이미 있다: " + output);
        }
    }

    private static void requireCurrentContract(JsonNode manifest) {
        if (!CAPACITY_POLICY.equals(manifest.path("capacity_policy").asString())
            || !ScoringReasons.LABEL_VERSION.equals(manifest.path("label_version").asString())) {
            throw new IllegalArgumentException("재생 자료의 정원 정책이나 정답 판이 지금과 다르다. "
                + "차량 정원 스냅샷 기준으로 독립 정답을 다시 준비해야 한다");
        }
    }

    /** 번들을 로컬 DB의 활성 모델 배포로 올린다. 예보가 예측 기록에 적고 채점이 정산 근거를 읽는 배포다. */
    private static RuntimeSnapshot activate(
        Stores stores,
        AlightingAwareInferenceBundle bundle,
        AlightingAwareSeatForecastModel model
    ) {
        LoadedModelRegistry registry = new LoadedModelRegistry();
        BundleActivation activation = new BundleActivation(stores.deployments(), registry);
        ModelRelease release = ModelRelease.alighting(RELEASE_ID, bundle.digest(), DATA_THROUGH,
            new SupportedForecastScope(bundle.routes()), model);
        new TransactionTemplate(stores.transactions())
            .executeWithoutResult(status -> activation.activate(() -> release));
        return new ActiveForecastRuntimeResolver(stores.deployments(), registry).resolveActive().orElseThrow();
    }

    private List<Map<String, Object>> replayRoutes(
        Path fixtures,
        JsonNode manifest,
        String onlyRoute,
        Path output
    ) throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode route : manifest.path("routes")) {
            if (onlyRoute != null && !route.path("route").asString().equals(onlyRoute)) {
                continue;
            }
            JsonNode source = JSON.readTree(verifiedBytes(fixtures, route));
            WorkerReplayFixture fixture = WorkerReplayFixture.of(source);
            Map<String, Object> result = new LinkedHashMap<>(replay(fixture));
            result.put("trace", WorkerReplayTrace.describe(source, route.path("sha256").asString()));
            results.add(result);
            Files.writeString(output.resolve(fixture.route() + ".json"), JSON.writeValueAsString(result));
            System.out.println(JSON.writeValueAsString(result));
        }
        return results;
    }

    private static byte[] verifiedBytes(Path fixtures, JsonNode route) throws IOException {
        byte[] bytes = Files.readAllBytes(fixtures.resolve(route.path("file").asString()));
        if (!sha256(bytes).equals(route.path("sha256").asString())) {
            throw new IllegalArgumentException("재생 자료의 노선 파일이 바뀌었다: " + route.path("file").asString());
        }
        return bytes;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, Object> replay(WorkerReplayFixture fixture) {
        final long routeId = new WorkerReplayLoader(stores.jdbc()).load(fixture);
        List<Long> versions = fixture.versions().stream().map(WorkerReplayFixture.Version::id).sorted().toList();
        WorkerReplayRunner runner = new WorkerReplayRunner(stores, runtime, versions);
        Set<Long> caseBatches = fixture.cases().stream()
            .map(row -> fixture.observation(row.path("source_id").asLong()).batchId())
            .collect(Collectors.toSet());
        runner.run(fixture.batches(), caseBatches);
        return new WorkerReplayVerification(stores.jdbc(), runner, bundle, model, fixture, routeId).result();
    }

    /** 노선 결과를 묶은 보고서다. 재생한 노선 수가 번들의 노선 수(노선 하나만 재생했으면 1)와 같고 모두 통과해야 통과다. */
    private Map<String, Object> report(List<Map<String, Object>> results, String onlyRoute) {
        final boolean passed = results.size() == expectedRoutes(onlyRoute)
            && results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("passed")));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("passed", passed);
        report.put("routes", results);
        report.put("bundle_manifest_sha256", bundle.digest());
        report.put("verification_code_sha", System.getProperty("alighting.replay.code-sha", ""));
        report.put("serving_ready", false);
        report.put("settlement_scope", "after_alighting_only");
        report.put("runtime_capacity_policy", CAPACITY_POLICY);
        report.put("runtime_label_version", ScoringReasons.LABEL_VERSION);
        report.put("runtime_calculation_version", runtime.featureContractVersion());
        report.put("quality_scope", "Accepted local observations with simulated collection capacity history; "
            + "production collection rejection state unavailable");
        report.put("input_scope", "Complete day observations replayed from an empty history at day start; "
            + "midnight prefixes supply vehicle capacity evidence only; not full DB bootstrap");
        return report;
    }

    private int expectedRoutes(String onlyRoute) {
        if (onlyRoute != null) {
            return 1;
        }
        return bundle.routes().size();
    }

    /**
     * 재생이 쓰는 로컬 DB의 저장소와 트랜잭션이다.
     *
     * @param records 예보 실행과 예측 기록의 저장소. 채점이 최근 예보가 있던 노선을 찾는 데도 쓴다
     */
    record Stores(
        JdbcClient jdbc,
        PlatformTransactionManager transactions,
        ModelDeploymentRepository deployments,
        JdbcForecastRecordStore records,
        StopPassStore passes,
        PassLabelStore labels
    ) {
    }
}
