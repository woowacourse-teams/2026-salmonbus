package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileModelBundleLoaderTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", ""})
    void 잘못된_manifest는_적재_거부_예외로_전달한다(String content) throws Exception {
        // given
        Files.writeString(directory.resolve("manifest.json"), content);
        FileModelBundleLoader loader = new FileModelBundleLoader();

        // then
        assertThatThrownBy(() -> loader.filesUnder(directory.toString()).load())
            .isInstanceOf(BundleRejectedException.class);
    }
}
