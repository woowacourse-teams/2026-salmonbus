package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import java.nio.file.Path;

public final class FileModelBundleLoader implements ModelBundleLoader {
    @Override
    public ModelBundleFiles filesUnder(String directory) {
        return filesOf(BundleFiles.under(Path.of(directory)));
    }

    static ModelBundleFiles filesOf(BundleFiles files) {
        return () -> LoadedBundle.from(files).release();
    }
}
