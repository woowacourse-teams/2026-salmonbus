package com.gustler.backend.processor.a18;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.processor.SeatForecastResult;
import com.gustler.backend.processor.StagedModelDeployment;
import com.gustler.backend.processor.persistence.jdbc.JdbcModelDeploymentRepository;
import com.gustler.backend.support.IntegrationTest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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
    void 검사에_걸린_묶음은_이미_올라온_계수를_안_밀어낸다() {
        // given 정상 묶음을 먼저 올린다
        activation().activate(DummyBundle.valid().writeTo(directory));
        LoadedBundle before = bundles.current().orElseThrow();

        // when 어긋난 묶음을 올리려다 걸린다
        Path broken = directory.resolve("broken");
        broken.toFile().mkdirs();
        assertThatThrownBy(() -> activation().activate(
            DummyBundle.valid().put("modelVersion", "seat-distribution-a19-v1").writeTo(broken)))
            .isInstanceOf(BundleRejectedException.class);

        // then
        assertThat(bundles.current()).contains(before);
    }

    @Test
    void 승격_뒤에_입력값_31개가_좌석_71칸_확률로_바뀐다() {
        // given
        activation().activate(DummyBundle.valid().writeTo(directory));
        RuntimeSnapshot snapshot = resolver().resolveActive().orElseThrow();

        // when
        SeatForecastResult actual = snapshot.predictor().predict(new A18PredictionInput(
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
        SeatForecastResult actual = snapshot.predictor().predict(new A18PredictionInput(
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

    private BundleActivation activation() {
        return new BundleActivation(deployments, bundles);
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
