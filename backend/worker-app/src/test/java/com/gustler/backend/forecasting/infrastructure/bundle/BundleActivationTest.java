package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.model.ActivateModelCommand;
import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelActivationResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Status;
import com.gustler.backend.forecasting.application.model.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.model.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.model.ModelActivationService;
import com.gustler.backend.forecasting.application.model.ModelStartupService;
import com.gustler.backend.forecasting.application.model.TransactionalModelActivation;
import com.gustler.backend.forecasting.domain.model.ModelActivationConflictException;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcModelDeploymentRepository;
import com.gustler.backend.support.IntegrationTest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
@Transactional
class BundleActivationTest {
    @TempDir Path directory;
    @Autowired JdbcModelDeploymentRepository deployments;
    @Autowired JdbcClient jdbcClient;
    @Autowired TransactionalModelActivation transactionalActivation;
    private LoadedModelRegistry models;
    private final FileModelBundleLoader loader = new FileModelBundleLoader();

    @BeforeEach
    void 계수를_준비하지_않은_상태에서_시작한다() {
        models = new LoadedModelRegistry();
    }

    @Test
    void 검증한_모델을_활성화하고_전체_식별_정보를_저장한다() {
        var files = DummyBundle.valid().writeTo(directory);
        var expected = LoadedBundle.from(files).identity();

        activate(files);

        assertThat(deployments.findActive().orElseThrow().identity()).isEqualTo(expected);
        assertThat(deployments.findActiveSlot().deployment()).isEqualTo(deployments.findActive());
    }

    @Test
    void 검사에_실패한_모델은_배포_행을_남기지_않는다() {
        var files = DummyBundle.valid().put("routes", List.of("3330", "1650")).writeTo(directory);
        assertThatThrownBy(() -> activate(files)).isInstanceOf(BundleRejectedException.class);
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 검사에_실패해도_이미_활성화한_모델은_유지한다() {
        activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();
        var broken = DummyBundle.valid().put("modelVersion", "seat-distribution-a19-v1")
            .writeTo(subdirectory("broken"));

        assertThatThrownBy(() -> activate(broken)).isInstanceOf(BundleRejectedException.class);

        assertThat(resolver().resolveActive().orElseThrow().model()).isSameAs(before.model());
    }

    @Test
    void 같은_digest의_다른_릴리스가_활성화에_실패해도_기존_모델은_유지한다() {
        activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();
        var next = DummyBundle.valid().put("releaseId", "dummy-release-0002")
            .writeTo(subdirectory("next"));
        assertThat(LoadedBundle.from(next).bundleDigest()).isEqualTo(before.bundleDigest());
        var failing = mock(TransactionalModelActivation.class);
        when(failing.activate(any(), anyLong(), any())).thenThrow(new ModelActivationConflictException("경쟁 요청"));
        var service = new ModelActivationService(loader, models, failing);

        assertThatThrownBy(() -> service.activate(command(next))).isInstanceOf(ModelActivationConflictException.class);

        assertThat(models.size()).isEqualTo(2);
        assertThat(resolver().resolveActive().orElseThrow().model()).isSameAs(before.model());
        assertThat(before.model()).isNotNull();
    }

    @Test
    void 다른_모델의_기동_승격이_꺼져_있으면_기존_배포와_계수를_유지한다() {
        activate(DummyBundle.valid().writeTo(directory));
        var before = resolver().resolveActive().orElseThrow();
        var next = DummyBundle.valid().put("releaseId", "dummy-release-0002").writeTo(subdirectory("next"));

        var result = startup().load(new LoadConfiguredModelCommand(next.manifest().getParent().toString(), false));

        assertThat(result.status()).isEqualTo(Status.IDENTITY_MISMATCH);
        assertThat(resolver().resolveActive().orElseThrow().model()).isSameAs(before.model());
        assertThat(deploymentCount()).isOne();
    }

    @Test
    void 같은_모델로_재기동하면_배포나_버전을_추가하지_않는다() {
        activate(DummyBundle.valid().writeTo(directory));
        var before = deployments.findActiveSlot();
        models = new LoadedModelRegistry();

        var result = startup().load(new LoadConfiguredModelCommand(directory.toString(), false));

        assertThat(result.status()).isEqualTo(Status.READY);
        assertThat(result.activeVersion()).isEqualTo(before.version());
        assertThat(deploymentCount()).isOne();
        assertThat(resolver().resolveActive()).isPresent();
    }

    @Test
    void 활성_모델이_없으면_설정한_모델을_처음_활성화한다() {
        DummyBundle.valid().writeTo(directory);
        assertThat(startup().load(new LoadConfiguredModelCommand(directory.toString(), false)).status())
            .isEqualTo(Status.ACTIVATED);
        assertThat(resolver().resolveActive()).isPresent();
    }

    @Test
    void 기동_설정이_없으면_DB나_파일을_변경하지_않는다() {
        assertThat(startup().load(new LoadConfiguredModelCommand(null, false)).status()).isEqualTo(Status.NOT_CONFIGURED);
        assertThat(deploymentCount()).isZero();
        assertThat(models.size()).isZero();
    }

    @Test
    void golden_계산을_재현하지_못하면_배포_행을_남기지_않는다() {
        DummyBundle given = DummyBundle.valid();
        Map<String, Object> golden = new LinkedHashMap<>(given.goldenVectorFrom());
        golden.put("expectedSeats", ((double) golden.get("expectedSeats")) + 1.0);
        var files = given.put("goldenVector", golden).writeTo(directory);

        assertThatThrownBy(() -> activate(files)).isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining(BundleCheck.GOLDEN_VECTOR.name());

        assertThat(deploymentCount()).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "2026-08-30T14:59:56.123456499Z, 2026-08-30T14:59:56.123456Z",
        "2026-08-30T14:59:56.123456500Z, 2026-08-30T14:59:56.123457Z",
        "2026-08-30T14:59:56.999999500Z, 2026-08-30T14:59:57Z"
    })
    void 학습_시각은_DB_정밀도로_대조하고_원본_파일은_유지한다(String sourceTime, String storedTime) {
        var files = DummyBundle.valid().put("dataThrough", sourceTime).writeTo(directory);

        activate(files);

        var databaseIdentity = deployments.findActive().orElseThrow().identity();
        assertThat(databaseIdentity.dataUntil()).isEqualTo(java.time.Instant.parse(storedTime));
        assertThat(LoadedBundle.from(files).identity()).isEqualTo(databaseIdentity);
        assertThat(LoadedBundle.from(files).coefficients().manifest().dataThrough()).isEqualTo(sourceTime);
        assertThat(resolver().resolveActive()).isPresent();
        java.time.Instant directRoundTrip = jdbcClient.sql("SELECT CAST(:instant AS timestamptz)")
            .param("instant", java.sql.Timestamp.from(java.time.Instant.parse(sourceTime)))
            .query((rs, row) -> rs.getTimestamp(1).toInstant()).single();
        assertThat(databaseIdentity.dataUntil()).isEqualTo(directRoundTrip);
    }

    @Test
    void 기동_중_다른_활성화가_먼저_적용되면_충돌_결과를_반환하고_기존_모델을_유지한다() {
        activate(DummyBundle.valid().writeTo(directory));
        var before = resolver().resolveActive().orElseThrow();
        var next = DummyBundle.valid().put("releaseId", "dummy-release-0002").writeTo(subdirectory("next"));
        var failing = mock(TransactionalModelActivation.class);
        when(failing.activate(any(), anyLong(), any())).thenThrow(new ModelActivationConflictException("경쟁 요청"));
        var service = new ModelStartupService(loader, models, deployments, failing);

        var result = service.load(new LoadConfiguredModelCommand(next.manifest().getParent().toString(), true));

        assertThat(result.status()).isEqualTo(Status.ACTIVATION_CONFLICT);
        assertThat(resolver().resolveActive().orElseThrow().model()).isSameAs(before.model());
        assertThat(deploymentCount()).isOne();
    }

    @Test
    void 검증한_계수로_좌석_71칸의_분포를_계산한다() {
        var files = DummyBundle.valid().writeTo(directory);
        activate(files);
        SeatForecastResult result = LoadedBundle.from(files).predictor().predict(new SeatDistributionInput(
            featureVector(), ModelRoute.of("204000057"), 4, 20, 44, null));
        assertThat(result.distribution().chanceBySeats()).hasSize(71);
        assertThat(result.distribution().chanceBySeats().stream().mapToDouble(Double::doubleValue).sum())
            .isEqualTo(1.0, within(1e-12));
    }

    @Test
    void 활성_배포만_있고_해당_모델이_준비되지_않으면_계산을_시작하지_않는다() {
        var release = LoadedBundle.from(DummyBundle.valid().writeTo(directory)).release();
        transactionalActivation.activate(UUID.randomUUID(), deployments.findActiveSlot().version(), release.identity());
        assertThat(resolver().resolveActive()).isEmpty();
    }

    private ModelActivationResult activate(BundleFiles files) {
        return new ModelActivationService(loader, models, transactionalActivation).activate(command(files));
    }

    private ActivateModelCommand command(BundleFiles files) {
        return new ActivateModelCommand(UUID.randomUUID(), deployments.findActiveSlot().version(), files.manifest().getParent().toString());
    }

    private ModelStartupService startup() {
        return new ModelStartupService(loader, models, deployments, transactionalActivation);
    }

    private ActiveForecastRuntimeResolver resolver() {
        return new ActiveForecastRuntimeResolver(deployments, models);
    }

    private Path subdirectory(String name) {
        Path result = directory.resolve(name);
        result.toFile().mkdirs();
        return result;
    }

    private int deploymentCount() {
        return jdbcClient.sql("SELECT count(*) FROM model_deployment").query(Integer.class).single();
    }

    private static double[] featureVector() {
        double[] features = new double[DummyBundle.FEATURE_COUNT];
        features[0] = 1.0;
        for (int index = 1; index < features.length; index++) {
            features[index] = index % 3 == 0 ? 0.5 : 0.0;
        }
        return features;
    }
}
