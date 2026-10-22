package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.api.model.LoadConfiguredModelCommand;
import com.gustler.backend.forecasting.api.model.ModelLoadException;
import com.gustler.backend.forecasting.api.model.ModelLoadResult;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Activated;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.IdentityMismatch;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.NotConfigured;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Promoted;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Rejected;
import com.gustler.backend.forecasting.api.model.ModelLoadResult.Reloaded;
import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.BundleActivation;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelStartupService;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.StagedModelDeployment;
import com.gustler.backend.forecasting.domain.model.ForecastFeatureContract;
import com.gustler.backend.forecasting.domain.model.ModelRoute;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import com.gustler.backend.forecasting.domain.model.SeatDistributionPredictor;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcModelDeploymentRepository;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * 더미 계수 묶음으로 적재부터 좌석 분포 계산까지 전체 흐름을 검증한다. 이 티켓의 완료 조건이다.
 *
 * <p>실제 계수 파일이 오면 테스트를 수정하지 않고 파일만 교체한다.
 */
@ForecastingIntegrationTest
@Transactional
class BundleActivationTest {
    private static final int FOUR_STOPS_AHEAD = 4;

    @TempDir
    Path directory;

    @Autowired
    private JdbcModelDeploymentRepository deployments;

    @Autowired
    private JdbcClient jdbcClient;

    private LoadedModelRegistry bundles;

    @BeforeEach
    void 계수를_안_올린_상태에서_시작한다() {
        bundles = new LoadedModelRegistry();
    }

    @Test
    void 더미_계수_묶음을_적재하면_도는_배포가_선다() {
        // when
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));

        // then
        assertThat(deployments.findActive()).isPresent();
    }

    @Test
    void 도는_배포의_출시_식별자가_설명_파일의_것과_같다() {
        // given
        LoadedBundle expected = LoadedBundle.from(DummyBundle.valid().writeTo(directory));

        // when
        activation().activate(filesOf(BundleFiles.under(directory)));

        // then
        assertThat(deployments.findActive().orElseThrow().releaseId())
            .isEqualTo(expected.releaseId());
    }

    @Test
    void 검사에_걸린_묶음은_배포_행을_안_남긴다() {
        // given
        // 노선 순서가 뒤집힌 묶음이다
        DummyBundle given = DummyBundle.valid().put("routes", List.of("3330", "1650"));

        // when
        assertThatThrownBy(() -> activation().activate(filesOf(given.writeTo(directory))))
            .isInstanceOf(BundleRejectedException.class);

        // then
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 검사에_걸린_묶음은_이미_돌던_계수를_안_밀어낸다() {
        // given
        // 정상 묶음을 먼저 활성화한다
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when
        // 검증에 실패하는 묶음의 활성화를 시도한다
        assertThatThrownBy(() -> activation().activate(filesOf(brokenBundle())))
            .isInstanceOf(BundleRejectedException.class);

        // then
        // 기존에 사용하던 계수를 유지한다
        assertThat(resolver().resolveActive().orElseThrow().bundleDigest())
            .isEqualTo(before.bundleDigest());
    }

    /**
     * 동키가 지적한 문제다. A 가 활성화된 상태에서 B 승격이 실패하면 예전 구조에서는 메모리에 B 가 남아
     * DB 는 A 를 가리키는 식별 정보 불일치로 예보 전체가 중단되었다.
     *
     * <p>현재는 활성 배포가 가리키는 요약값으로 계수를 조회하므로 실패한 B 가 메모리에 남아 있어도
     * A 를 그대로 선택한다.
     */
    @Test
    void 승격에_실패한_묶음이_돌던_묶음을_못_밀어낸다() {
        // given
        // A 가 활성화된 상태다
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when
        // B 의 승격 직전에 다른 적재가 먼저 ACTIVE 를 차지한다
        assertThatThrownBy(() -> failingActivation().activate(filesOf(otherBundle())))
            .isInstanceOf(ModelLoadException.class)
            .hasMessageStartingWith("STAGED 에서 ACTIVE 로 못 올렸다. 그 사이 다른 적재가 먼저 올라갔다: ");

        // then
        // DB 도 메모리도 A 를 가리킨다
        assertThat(resolver().resolveActive().orElseThrow().releaseId())
            .isEqualTo(before.releaseId());
    }

    /**
     * 계수 파일 요약값에는 출시 식별자가 포함되지 않는다. 계수는 한 글자도 바뀌지 않고 출시 식별자만
     * 다시 부여한 파일은 요약값이 같다.
     *
     * <p>기동 적재에서 요약값만 검사하면 이 파일을 같은 것으로 보고 메모리에 적재한다.
     * 메모리는 요약값을 키로 사용하므로 기존 계수가 대체되어 활성 배포가 그대로여도 예보 전체가 중단된다.
     */
    @Test
    void 출시_식별자만_다른_계수_파일로_다시_띄워도_돌던_계수가_그대로_돈다() {
        // given
        // A 가 활성화된 상태다
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when
        // 계수는 그대로고 출시 식별자만 다른 파일로 기동한다
        startUp(relabelledBundle(), false);

        // then
        assertThat(resolver().resolveActive().orElseThrow().releaseId())
            .isEqualTo(before.releaseId());
    }

    @Test
    void 계수_파일이_실은_대조_사례를_재현_못_하면_배포_행을_안_남긴다() {
        // given
        // 기대 잔여석만 한 석 어긋낸 묶음이다
        DummyBundle given = DummyBundle.valid();
        Map<String, Object> golden = new LinkedHashMap<>(given.goldenVectorFrom());
        golden.put("expectedSeats", ((double) golden.get("expectedSeats")) + 1.0);

        // when
        assertThatThrownBy(() -> activation().activate(
            filesOf(given.put("goldenVector", golden).writeTo(directory))))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining(BundleCheck.GOLDEN_VECTOR.name());

        // then
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 승격_뒤에_입력값_31개가_좌석_71칸_확률로_바뀐다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot snapshot = resolver().resolveActive().orElseThrow();

        // when
        SeatForecastResult actual = predictorOf(snapshot).predict(new SeatDistributionInput(
            featureVector(), ModelRoute.of("204000057"), FOUR_STOPS_AHEAD, 20, 44, null));

        // then
        assertThat(actual.distribution().chanceBySeats()).hasSize(71);
    }

    @Test
    void 승격_뒤에_나온_좌석_확률을_모두_더하면_1이다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot snapshot = resolver().resolveActive().orElseThrow();

        // when
        SeatForecastResult actual = predictorOf(snapshot).predict(new SeatDistributionInput(
            featureVector(), ModelRoute.of("234000050"), 1, 44, 44, null));

        // then
        assertThat(actual.distribution()
            .chanceBySeats()
            .stream()
            .mapToDouble(Double::doubleValue)
            .sum()).isEqualTo(1.0, within(1e-12));
    }

    @Test
    void 계수를_안_올린_채로_배포_행만_있으면_아무것도_안_낸다() {
        // given
        // 배포 행만 생성하고 계수는 적재하지 않는다
        LoadedBundle bundle = LoadedBundle.from(DummyBundle.valid().writeTo(directory));
        deployments.promoteToActive(deployments.stage(new StagedModelDeployment(
            UUID.randomUUID(),
            bundle.releaseId(),
            "seat-distribution-a18",
            bundle.coefficients().manifest().modelVersion(),
            bundle.bundleDigest(),
            "seat-distribution-0-70",
            bundle.featureContractVersion(),
            bundle.scope().digest(),
            Instant.parse("2026-08-30T14:59:56Z"))));

        // when
        Optional<RuntimeSnapshot> actual = resolver().resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 기동_설정이_없으면_DB나_파일을_변경하지_않는다() {
        // when
        ModelLoadResult actual = startup().load(new LoadConfiguredModelCommand(null, false));

        // then
        assertThat(actual).isEqualTo(new NotConfigured());
        assertThat(deploymentCount()).isZero();
        assertThat(bundles.size()).isZero();
    }

    @Test
    void 활성_모델이_없으면_설정한_모델을_처음_활성화한다() {
        // given
        DummyBundle.valid().writeTo(directory);

        // when
        ModelLoadResult actual = startup().load(new LoadConfiguredModelCommand(directory.toString(), false));

        // then
        assertThat(actual).isEqualTo(new Activated(deployments.findActive().orElseThrow().id()));
        assertThat(resolver().resolveActive()).isPresent();
    }

    @Test
    void 같은_모델로_재기동하면_배포를_추가하지_않는다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        bundles = new LoadedModelRegistry();

        // when
        ModelLoadResult actual = startup().load(new LoadConfiguredModelCommand(directory.toString(), false));

        // then
        assertThat(actual).isEqualTo(new Reloaded("dummy-release-0001"));
        assertThat(deploymentCount()).isOne();
        assertThat(resolver().resolveActive()).isPresent();
    }

    @Test
    void 다른_모델의_기동_승격이_꺼져_있으면_기존_배포와_계수를_유지한다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when
        ModelLoadResult actual = startUp(relabelledBundle(), false);

        // then
        assertThat(actual).isEqualTo(new IdentityMismatch("dummy-release-0001", "dummy-release-0002"));
        assertThat(resolver().resolveActive().orElseThrow().releaseId()).isEqualTo(before.releaseId());
        assertThat(deploymentCount()).isOne();
    }

    @Test
    void 기동_승격이_꺼져_있으면_신원이_다른_계수를_메모리에_올리지_않는다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        BundleFiles other = otherBundle();

        // when
        startUp(other, false);

        // then
        assertThat(bundles.find(LoadedBundle.from(other).bundleDigest())).isEmpty();
        assertThat(bundles.size()).isOne();
    }

    @Test
    void 기동_승격을_켜면_신원이_다른_계수로_갈아_끼운다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        final long earlier = deployments.findActive().orElseThrow().id();

        // when
        ModelLoadResult actual = startUp(relabelledBundle(), true);

        // then
        final long later = deployments.findActive().orElseThrow().id();
        assertThat(actual).isEqualTo(new Promoted("dummy-release-0001", "dummy-release-0002", later));
        assertThat(stateOf(earlier)).isEqualTo("RETIRED");
        assertThat(resolver().resolveActive().orElseThrow().releaseId()).isEqualTo("dummy-release-0002");
    }

    @Test
    void 검사에_걸린_계수_파일은_기동_적재를_거절로_끝낸다() {
        // given
        BundleFiles files = DummyBundle.valid().put("routes", List.of("3330", "1650")).writeTo(directory);
        String reason = catchThrowableOfType(BundleRejectedException.class, () -> LoadedBundle.from(files))
            .getMessage();

        // when
        ModelLoadResult actual = startUp(files, false);

        // then
        assertThat(actual).isEqualTo(new Rejected(reason));
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 잘못된_학습_시각은_최초_기동에서_거절된다() {
        // given
        BundleFiles files = DummyBundle.valid().put("dataThrough", "2026-08-30 14:59:56")
            .writeTo(directory);

        // when
        ModelLoadResult actual = startUp(files, false);

        // then
        assertThat(actual).isInstanceOf(Rejected.class);
        assertThat(actual.toString()).contains(BundleCheck.DATA_THROUGH.name());
        assertThat(deploymentCount()).isZero();
        assertThat(resolver().resolveActive()).isEmpty();
    }

    @Test
    void 잘못된_학습_시각은_재기동에서도_메모리에_올리지_않는다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        ActiveModelDeployment original = deployments.findActive().orElseThrow();
        bundles = new LoadedModelRegistry();
        BundleFiles unreadable = DummyBundle.valid().put("dataThrough", "2026-08-30 14:59:56")
            .writeTo(directoryUnder("unreadable"));

        // when
        ModelLoadResult actual = startUp(unreadable, false);

        // then
        assertThat(actual).isInstanceOf(Rejected.class);
        assertThat(actual.toString()).contains(BundleCheck.DATA_THROUGH.name());
        assertThat(deploymentCount()).isEqualTo(1);
        assertThat(deployments.findActive()).contains(original);
        assertThat(bundles.find(original.bundleDigest())).isEmpty();
        assertThat(resolver().resolveActive()).isEmpty();
    }

    @Test
    void 길이를_초과한_번들은_배포_행과_기존_계수를_변경하지_않는다() {
        // given
        activation().activate(filesOf(DummyBundle.valid().writeTo(directory)));
        ActiveModelDeployment original = deployments.findActive().orElseThrow();
        ModelRelease originalBundle = bundles.find(original.bundleDigest()).orElseThrow();
        BundleFiles invalid = DummyBundle.valid().put("featureContractVersion", "v".repeat(41))
            .writeTo(directoryUnder("too-long"));

        // when
        ModelLoadResult actual = startUp(invalid, true);

        // then
        assertThat(actual).isInstanceOf(Rejected.class);
        assertThat(actual.toString()).contains(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH.name());
        assertThat(deploymentCount()).isEqualTo(1);
        assertThat(deployments.findActive()).contains(original);
        assertThat(bundles.find(original.bundleDigest())).contains(originalBundle);
        assertThat(resolver().resolveActive()).isPresent();
    }

    @Test
    void 새_입력_번들은_수동_승격하고_이전_번들로_돌아갈_수_있다() {
        // when
        BundleFiles legacy = DummyBundle.valid().writeTo(directoryUnder("legacy"));

        // then
        assertThat(startUp(legacy, false)).isInstanceOf(Activated.class);
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();
        BundleFiles conditional = DummyBundle.conditional().put("releaseId", "conditional-release")
            .writeTo(directoryUnder("conditional"));
        assertThat(startUp(conditional, false)).isInstanceOf(IdentityMismatch.class);
        assertThat(resolver().resolveActive().orElseThrow().bundleDigest()).isEqualTo(before.bundleDigest());
        assertThat(startUp(conditional, true)).isInstanceOf(Promoted.class);
        assertThat(resolver().resolveActive().orElseThrow().featureContractVersion())
            .isEqualTo(ForecastFeatureContract.CONDITIONAL_VERSION);
        assertThat(startUp(legacy, true)).isInstanceOf(Promoted.class);
        assertThat(resolver().resolveActive().orElseThrow().bundleDigest()).isEqualTo(before.bundleDigest());
    }

    @Test
    void 정류장_파일이_변조된_새_번들은_기존_활성_배포를_바꾸지_않는다() throws Exception {
        // when
        startUp(DummyBundle.valid().writeTo(directoryUnder("legacy")), false);
        ActiveModelDeployment before = deployments.findActive().orElseThrow();
        BundleFiles conditional = DummyBundle.conditional().writeTo(directoryUnder("conditional"));
        Files.writeString(conditional.manifest().resolveSibling("route-reference.json"), "{}");

        // then
        assertThat(startUp(conditional, true)).isInstanceOf(Rejected.class);
        assertThat(deployments.findActive()).contains(before);
        assertThat(deploymentCount()).isEqualTo(1);
    }

    /** 예보 경로는 모델 포트를 호출하지만 이 테스트는 열 31개를 직접 입력하려고 예측기를 조회한다. */
    private SeatDistributionPredictor predictorOf(
        RuntimeSnapshot snapshot
    ) {
        return bundles.find(snapshot.bundleDigest()).orElseThrow().predictor();
    }

    private BundleActivation activation() {
        return new BundleActivation(deployments, bundles);
    }

    /** 승격만 실패하는 적재. 적재와 STAGED 단계는 정상 수행하지만 마지막 승격은 실패한다. */
    private BundleActivation failingActivation() {
        return new BundleActivation(new ModelDeploymentRepository() {
            @Override
            public Optional<ActiveModelDeployment> findActive() {
                return deployments.findActive();
            }

            @Override
            public long stage(
                StagedModelDeployment staged
            ) {
                return deployments.stage(staged);
            }

            @Override
            public boolean promoteToActive(
                final long deploymentId
            ) {
                return false;
            }
        }, bundles);
    }

    private ModelStartupService startup() {
        return new ModelStartupService(new FileModelBundleLoader(), deployments, bundles, activation());
    }

    private ModelLoadResult startUp(
        BundleFiles files,
        final boolean promoteOnStart
    ) {
        return startup().load(
            new LoadConfiguredModelCommand(files.manifest().getParent().toString(), promoteOnStart));
    }

    private static ModelBundleFiles filesOf(
        BundleFiles files
    ) {
        return FileModelBundleLoader.filesOf(files);
    }

    private BundleFiles brokenBundle() {
        return DummyBundle.valid()
            .put("modelVersion", "seat-distribution-a19-v1")
            .writeTo(directoryUnder("broken"));
    }

    /** 계수는 그대로고 출시 식별자만 다시 부여한 파일. 계수 파일 요약값은 이전 파일과 같다. */
    private BundleFiles relabelledBundle() {
        return DummyBundle.valid()
            .put("releaseId", "dummy-release-0002")
            .writeTo(directoryUnder("relabelled"));
    }

    private BundleFiles otherBundle() {
        return DummyBundle.valid()
            .put("sourceCommit", "0000000000000000000000000000000000000000")
            .writeTo(directoryUnder("other"));
    }

    private Path directoryUnder(
        String name
    ) {
        Path under = directory.resolve(name);
        under.toFile().mkdirs();
        return under;
    }

    private ActiveForecastRuntimeResolver resolver() {
        return new ActiveForecastRuntimeResolver(deployments, bundles);
    }

    private static double[] featureVector() {
        double[] features = new double[DummyBundle.FEATURE_COUNT];
        features[0] = 1.0;
        for (int index = 1; index < features.length; index++) {
            features[index] = fixtureCoefficient(index);
        }
        return features;
    }

    private int deploymentCount() {
        return jdbcClient.sql("SELECT count(*) FROM model_deployment").query(Integer.class).single();
    }

    private String stateOf(
        final long deploymentId
    ) {
        return jdbcClient.sql("SELECT state FROM model_deployment WHERE id = ?")
            .param(deploymentId)
            .query(String.class)
            .single();
    }

    private static double fixtureCoefficient(final int index) {
        if (index % 3 == 0) {
            return 0.5;
        }
        return 0.0;
    }
}
