package com.gustler.backend.processor.seatdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import com.gustler.backend.processor.StagedModelDeployment;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 도는 배포와 올라온 계수의 신원이 안 섞이는지 본다.
 *
 * <p>동키가 PR #47 에 남긴 우려가 이 자리다. 둘을 따로 읽으면 승격 순간에 ACTIVE 행은 B 인데
 * 계산은 A 계수로 하고 DB 에는 B 예보라고 적는 상태가 된다.
 */
class ActiveForecastRuntimeResolverTest {

    @TempDir
    Path directory;

    @Test
    void 도는_배포가_없으면_아무것도_안_낸다() {
        // given
        LoadedBundleHolder bundles = holderOf(loaded());

        // when
        Optional<RuntimeSnapshot> actual = resolver(null, bundles).resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 계수가_안_올라와_있으면_아무것도_안_낸다() {
        // given
        LoadedBundle bundle = loaded();

        // when
        Optional<RuntimeSnapshot> actual =
            resolver(deploymentOf(bundle), new LoadedBundleHolder()).resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 배포_행과_계수의_신원이_같으면_한_덩어리로_낸다() {
        // given
        LoadedBundle bundle = loaded();

        // when
        Optional<RuntimeSnapshot> actual =
            resolver(deploymentOf(bundle), holderOf(bundle)).resolveActive();

        // then
        assertThat(actual).map(RuntimeSnapshot::bundleDigest).contains(bundle.bundleDigest());
    }

    @Test
    void 배포_행의_출시_식별자가_올라온_계수와_다르면_아무것도_안_낸다() {
        // given ACTIVE 행은 B 인데 메모리에는 A 계수가 올라와 있는 상태다
        LoadedBundle bundle = loaded();
        ActiveModelDeployment other = new ActiveModelDeployment(
            7L, bundle.featureContractVersion(), "other-release-0002", bundle.bundleDigest());

        // when
        Optional<RuntimeSnapshot> actual = resolver(other, holderOf(bundle)).resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 배포_행의_계수_요약값이_올라온_계수와_다르면_아무것도_안_낸다() {
        // given
        LoadedBundle bundle = loaded();
        ActiveModelDeployment other = new ActiveModelDeployment(
            7L, bundle.featureContractVersion(), bundle.releaseId(), "a".repeat(64));

        // when
        Optional<RuntimeSnapshot> actual = resolver(other, holderOf(bundle)).resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 배포_행의_입력_규칙_버전이_올라온_계수와_다르면_아무것도_안_낸다() {
        // given 계수는 같은데 입력을 만드는 규칙이 다르면 다른 뜻의 입력에 계수를 끼우게 된다
        LoadedBundle bundle = loaded();
        ActiveModelDeployment other = new ActiveModelDeployment(
            7L, "seat-feature-contract-v9", bundle.releaseId(), bundle.bundleDigest());

        // when
        Optional<RuntimeSnapshot> actual = resolver(other, holderOf(bundle)).resolveActive();

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void 승격이_일어나도_먼저_받아_든_것은_같은_계수를_가리킨다() {
        // given
        LoadedBundle first = loaded();
        LoadedBundleHolder bundles = holderOf(first);
        RuntimeSnapshot taken =
            resolver(deploymentOf(first), bundles).resolveActive().orElseThrow();

        // when 도중에 다른 계수로 갈아 끼운다
        bundles.replaceWith(loadedInto(directory.resolve("next")));

        // then 이미 받아 든 것은 안 바뀐다
        assertThat(taken.predictor()).isSameAs(first.predictor());
    }

    private ActiveForecastRuntimeResolver resolver(
        ActiveModelDeployment deployment,
        LoadedBundleHolder bundles
    ) {
        return new ActiveForecastRuntimeResolver(repositoryOf(deployment), bundles);
    }

    private static ModelDeploymentRepository repositoryOf(
        ActiveModelDeployment deployment
    ) {
        return new ModelDeploymentRepository() {

            @Override
            public Optional<ActiveModelDeployment> findActive() {
                return Optional.ofNullable(deployment);
            }

            @Override
            public long stage(
                StagedModelDeployment staged
            ) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean promoteToActive(
                final long deploymentId
            ) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static ActiveModelDeployment deploymentOf(
        LoadedBundle bundle
    ) {
        return new ActiveModelDeployment(
            7L, bundle.featureContractVersion(), bundle.releaseId(), bundle.bundleDigest());
    }

    private static LoadedBundleHolder holderOf(
        LoadedBundle bundle
    ) {
        LoadedBundleHolder bundles = new LoadedBundleHolder();
        bundles.replaceWith(bundle);
        return bundles;
    }

    private LoadedBundle loaded() {
        return loadedInto(directory.resolve("current"));
    }

    private static LoadedBundle loadedInto(
        Path directory
    ) {
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        return LoadedBundle.from(DummyBundle.valid().writeTo(directory));
    }
}
