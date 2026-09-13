package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import com.gustler.backend.processor.SeatForecastResult;
import com.gustler.backend.processor.StagedModelDeployment;
import com.gustler.backend.processor.persistence.jdbc.JdbcModelDeploymentRepository;
import com.gustler.backend.support.IntegrationTest;
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
 * 더미 계수 묶음으로 <b>적재부터 좌석 분포까지</b> 끝까지 돈다. 이 티켓의 완료 조건이다.
 *
 * <p>진짜 계수 파일이 오면 이 테스트를 안 고치고 파일만 갈아 끼운다.
 */
@IntegrationTest
@Transactional
class BundleActivationTest {

    private static final int FOUR_STOPS_AHEAD = 4;

    @TempDir
    Path directory;

    @Autowired
    private JdbcModelDeploymentRepository deployments;

    @Autowired
    private JdbcClient jdbcClient;

    private LoadedBundleHolder bundles;

    @BeforeEach
    void 계수를_안_올린_상태에서_시작한다() {
        bundles = new LoadedBundleHolder();
    }

    @Test
    void 더미_계수_묶음을_적재하면_도는_배포가_선다() {
        // when
        activation().activate(DummyBundle.valid().writeTo(directory));

        // then
        assertThat(deployments.findActive()).isPresent();
    }

    @Test
    void 도는_배포의_출시_식별자가_설명_파일의_것과_같다() {
        // given
        LoadedBundle expected = LoadedBundle.from(DummyBundle.valid().writeTo(directory));

        // when
        activation().activate(BundleFiles.under(directory));

        // then
        assertThat(deployments.findActive().orElseThrow().releaseId())
            .isEqualTo(expected.releaseId());
    }

    @Test
    void 검사에_걸린_묶음은_배포_행을_안_남긴다() {
        // given 노선 순서가 뒤집힌 묶음이다
        DummyBundle given = DummyBundle.valid().put("routes", List.of("3330", "1650"));

        // when
        assertThatThrownBy(() -> activation().activate(given.writeTo(directory)))
            .isInstanceOf(BundleRejectedException.class);

        // then
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 검사에_걸린_묶음은_이미_돌던_계수를_안_밀어낸다() {
        // given 정상 묶음을 먼저 올려 둔다
        activation().activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when 어긋난 묶음을 올리려다 걸린다
        assertThatThrownBy(() -> activation().activate(brokenBundle()))
            .isInstanceOf(BundleRejectedException.class);

        // then 돌던 계수가 그대로다
        assertThat(resolver().resolveActive().orElseThrow().bundleDigest())
            .isEqualTo(before.bundleDigest());
    }

    /**
     * 동키가 짚은 자리다. A 가 돌고 있는데 B 승격이 실패하면, 예전 구조에서는 메모리에 B 가 남아
     * DB 는 A 인데 신원이 안 맞아 예보가 통째로 멈췄다.
     *
     * <p>지금은 도는 배포가 가리키는 요약값으로 계수를 찾는다. 그래서 실패한 B 가 남아 있어도
     * A 가 그대로 골라진다.
     */
    @Test
    void 승격에_실패한_묶음이_돌던_묶음을_못_밀어낸다() {
        // given A 가 돌고 있다
        activation().activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when B 를 올리는데 승격 직전에 다른 적재가 먼저 ACTIVE 를 가져간다
        assertThatThrownBy(() -> failingActivation().activate(otherBundle()))
            .isInstanceOf(BundleRejectedException.class);

        // then DB 도 메모리도 A 를 가리킨다
        assertThat(resolver().resolveActive().orElseThrow().releaseId())
            .isEqualTo(before.releaseId());
    }

    /**
     * 계수 파일 요약값은 출시 식별자를 안 묶는다. 그래서 계수가 한 글자도 안 바뀌고 출시 식별자만
     * 다시 붙은 파일은 요약값이 같다.
     *
     * <p>기동 적재가 요약값만 보면 그 파일을 "같은 것" 으로 보고 메모리에 올린다. 자리는 요약값이
     * 열쇠라 <b>돌던 계수가 그 자리에서 밀려난다.</b> 그러면 도는 배포는 그대로인데 예보가 통째로
     * 안 나간다.
     */
    @Test
    void 출시_식별자만_다른_계수_파일로_다시_띄워도_돌던_계수가_그대로_돈다() {
        // given A 가 돌고 있다
        activation().activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot before = resolver().resolveActive().orElseThrow();

        // when 계수는 그대로고 출시 식별자만 다른 파일로 기동한다
        startupLoaderOf(relabelledBundle()).loadConfiguredBundle();

        // then
        assertThat(resolver().resolveActive().orElseThrow().releaseId())
            .isEqualTo(before.releaseId());
    }

    @Test
    void 계수_파일이_실은_대조_사례를_재현_못_하면_배포_행을_안_남긴다() {
        // given 기대 잔여석만 한 석 어긋낸 묶음이다
        DummyBundle given = DummyBundle.valid();
        Map<String, Object> golden = new LinkedHashMap<>(given.goldenVectorFrom());
        golden.put("expectedSeats", ((double) golden.get("expectedSeats")) + 1.0);

        // when
        assertThatThrownBy(() -> activation().activate(given.put("goldenVector", golden).writeTo(directory)))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining(BundleCheck.GOLDEN_VECTOR.name());

        // then
        assertThat(deploymentCount()).isZero();
    }

    @Test
    void 승격_뒤에_입력값_31개가_좌석_71칸_확률로_바뀐다() {
        // given
        activation().activate(DummyBundle.valid().writeTo(directory));
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
        activation().activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot snapshot = resolver().resolveActive().orElseThrow();

        // when
        SeatForecastResult actual = predictorOf(snapshot).predict(new SeatDistributionInput(
            featureVector(), ModelRoute.of("234000050"), 1, 44, 44, null));

        // then
        assertThat(actual.distribution().chanceBySeats().stream()
            .mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0, within(1e-12));
    }

    @Test
    void 계수를_안_올린_채로_배포_행만_있으면_아무것도_안_낸다() {
        // given 배포 행만 세우고 계수는 안 올린다
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

    /** 예보 경로는 모델 포트로 부르는데, 이 테스트는 열 31개를 곧바로 넣어 보려고 예측기를 꺼낸다. */
    private SeatDistributionPredictor predictorOf(
        RuntimeSnapshot snapshot
    ) {
        return bundles.find(snapshot.bundleDigest()).orElseThrow().predictor();
    }

    private BundleActivation activation() {
        return new BundleActivation(deployments, bundles);
    }

    /** 승격만 실패하는 적재. 적재와 STAGED 는 그대로 되고 마지막 한 걸음에서 못 올린다. */
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

    private BundleFiles brokenBundle() {
        return DummyBundle.valid()
            .put("modelVersion", "seat-distribution-a19-v1")
            .writeTo(directoryUnder("broken"));
    }

    /** 계수는 그대로고 출시 식별자만 다시 붙은 파일. 계수 파일 요약값은 앞엣것과 같다. */
    private BundleFiles relabelledBundle() {
        return DummyBundle.valid()
            .put("releaseId", "dummy-release-0002")
            .writeTo(directoryUnder("relabelled"));
    }

    private BundleStartupLoader startupLoaderOf(
        BundleFiles files
    ) {
        return new BundleStartupLoader(
            new ModelBundleProperties(files.manifest().getParent().toString(), false),
            deployments,
            bundles,
            activation());
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
            features[index] = index % 3 == 0 ? 0.5 : 0.0;
        }
        return features;
    }

    private int deploymentCount() {
        return jdbcClient.sql("SELECT count(*) FROM model_deployment").query(Integer.class).single();
    }
}
