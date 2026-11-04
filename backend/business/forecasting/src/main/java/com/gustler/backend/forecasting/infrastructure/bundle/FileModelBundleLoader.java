package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.infrastructure.bundle.les.LesServingBundle;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 허용한 파일 경계 안에서 모델 번들 입력을 열고 파일 기반 로더에 전달한다.
 */
public final class FileModelBundleLoader implements ModelBundleLoader {
    @Override
    public ModelBundleFiles filesUnder(String directory) {
        Path path = Path.of(directory);
        BundleFiles files = BundleFiles.under(path);
        return () -> {
            JsonNode manifest = JsonMapper.builder().build().readTree(files.readManifest());
            if (manifest.has("schema") && "les-inference-bundle-v1".equals(manifest.get("schema").asString())) {
                try {
                    return LesServingBundle.load(path);
                } catch (IOException | IllegalArgumentException error) {
                    throw new BundleRejectedException("레스 서빙 번들 검증 실패", error);
                }
            }
            return LoadedBundle.from(files).release();
        };
    }

    static ModelBundleFiles filesOf(BundleFiles files) {
        return () -> LoadedBundle.from(files).release();
    }
}
