package com.gustler.backend.forecasting.infrastructure.bundle;

import static com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareIdentifiers.canonical;

import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareServingBundle;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
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
            JsonNode manifest = readManifest(files);
            if (manifest.has("schema") && "alighting-inference-bundle-v1".equals(canonical(manifest.get("schema").asString()))) {
                try {
                    return AlightingAwareServingBundle.load(path);
                } catch (IOException | IllegalArgumentException error) {
                    throw new BundleRejectedException("하차 반영 서빙 번들 검증 실패", error);
                }
            }
            return LoadedBundle.from(files).release();
        };
    }

    private static JsonNode readManifest(BundleFiles files) {
        try {
            JsonNode manifest = JsonMapper.builder().build().readTree(files.readManifest());
            if (!manifest.isObject()) {
                throw new BundleRejectedException("모델 manifest는 JSON 객체여야 한다");
            }
            return manifest;
        } catch (JacksonException error) {
            throw new BundleRejectedException("모델 manifest JSON 파싱 실패", error);
        }
    }

    static ModelBundleFiles filesOf(BundleFiles files) {
        return () -> LoadedBundle.from(files).release();
    }
}
