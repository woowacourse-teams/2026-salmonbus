package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.gustler.backend.forecasting.api.model.InspectModelBundle.Inspection;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileModelBundleInspectorTest {
    @TempDir
    Path directory;

    @Test
    void 검수와_기동_로더가_같은_거절_규칙을_사용한다() {
        // when
        DummyBundle.valid().put("featureContractVersion", "v".repeat(41)).writeTo(directory);
        BundleRejectedException loading = catchThrowableOfType(BundleRejectedException.class,
            () -> new FileModelBundleLoader().filesUnder(directory.toString()).load());
        BundleRejectedException inspection = catchThrowableOfType(BundleRejectedException.class,
            () -> new FileModelBundleInspector().inspect(directory.toString()));

        // then
        assertThat(loading).isNotNull();
        assertThat(inspection).isNotNull();
        assertThat(inspection.getMessage()).isEqualTo(loading.getMessage())
            .contains(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH.name());
    }

    @Test
    void 검수_결과에_검증한_번들의_신원을_반환한다() throws Exception {
        // when
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        byte[] manifest = Files.readAllBytes(files.manifest());
        byte[] weights = Files.readAllBytes(files.weights());
        ModelRelease loaded = new FileModelBundleLoader().filesUnder(directory.toString()).load();
        Inspection actual = new FileModelBundleInspector().inspect(directory.toString());

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(actual.releaseId()).isEqualTo(loaded.releaseId());
            softly.assertThat(actual.bundleDigest()).isEqualTo(loaded.bundleDigest());
            softly.assertThat(actual.featureContractVersion()).isEqualTo(loaded.featureContractVersion());
            softly.assertThat(actual.dataThrough()).isEqualTo(loaded.dataThrough());
            softly.assertThat(actual.routes()).containsExactlyElementsOf(loaded.scope().modelRoutes());
        }
        assertThat(Files.readAllBytes(files.manifest())).isEqualTo(manifest);
        assertThat(Files.readAllBytes(files.weights())).isEqualTo(weights);
    }
}
