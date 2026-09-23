package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.application.model.ModelBundleLoader;
import com.gustler.backend.forecasting.domain.model.ModelRelease;
import java.nio.file.Path;

public final class FileModelBundleLoader implements ModelBundleLoader {
    @Override
    public ModelRelease load(String directory) {
        try {
            return LoadedBundle.from(BundleFiles.under(Path.of(directory))).release();
        } catch (IllegalArgumentException | java.time.DateTimeException invalidMetadata) {
            throw new com.gustler.backend.forecasting.api.model.ModelLoadException(
                "모델 식별 정보를 확인해 주세요: " + invalidMetadata.getMessage(), invalidMetadata);
        }
    }
}
