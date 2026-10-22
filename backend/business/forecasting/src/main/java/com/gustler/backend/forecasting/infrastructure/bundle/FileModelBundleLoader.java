package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import java.nio.file.Path;

/**
 * 허용한 파일 경계 안에서 모델 번들 입력을 열고 파일 기반 로더에 전달한다.
 */
public final class FileModelBundleLoader implements ModelBundleLoader {
    @Override
    public ModelBundleFiles filesUnder(String directory) {
        return filesOf(BundleFiles.under(Path.of(directory)));
    }

    static ModelBundleFiles filesOf(BundleFiles files) {
        return () -> LoadedBundle.from(files).release();
    }
}
