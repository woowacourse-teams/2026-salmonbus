package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileModelBundleInspectorTest {
    @TempDir
    Path directory;

    @Test
    void 검수와_기동_로더가_같은_거절_규칙을_사용한다() {
        DummyBundle.valid().put("featureContractVersion", "v".repeat(41)).writeTo(directory);
        BundleRejectedException loading = catchThrowableOfType(BundleRejectedException.class,
            () -> new FileModelBundleLoader().filesUnder(directory.toString()).load());
        BundleRejectedException inspection = catchThrowableOfType(BundleRejectedException.class,
            () -> new FileModelBundleInspector().inspect(directory.toString()));
        assertThat(loading).isNotNull();
        assertThat(inspection).isNotNull();
        assertThat(inspection.getMessage()).isEqualTo(loading.getMessage())
            .contains(BundleCheck.DEPLOYMENT_IDENTIFIER_LENGTH.name());
    }

    @Test
    void 검수_결과에_검증한_번들의_신원을_반환한다() throws Exception {
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        byte[] manifest = Files.readAllBytes(files.manifest());
        byte[] weights = Files.readAllBytes(files.weights());
        var loaded = new FileModelBundleLoader().filesUnder(directory.toString()).load();
        var actual = new FileModelBundleInspector().inspect(directory.toString());
        assertThat(actual.releaseId()).isEqualTo(loaded.releaseId());
        assertThat(actual.bundleDigest()).isEqualTo(loaded.bundleDigest());
        assertThat(actual.featureContractVersion()).isEqualTo(loaded.featureContractVersion());
        assertThat(actual.dataThrough()).isEqualTo(loaded.dataThrough());
        assertThat(actual.routes()).containsExactlyElementsOf(loaded.scope().modelRoutes());
        assertThat(Files.readAllBytes(files.manifest())).isEqualTo(manifest);
        assertThat(Files.readAllBytes(files.weights())).isEqualTo(weights);
    }
}
