package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.application.deployment.ModelBundleFiles;
import com.gustler.backend.forecasting.application.deployment.ModelBundleLoader;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.AlightingAwareServingBundle;
import com.gustler.backend.forecasting.infrastructure.bundle.alighting.TransferredServingBundle;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 허용한 파일 경계 안에서 모델 번들 파일을 열고 파일 기반 로더에 전달한다.
 */
public final class FileModelBundleLoader implements ModelBundleLoader {

    private final boolean allowSyntheticFixture;

    public FileModelBundleLoader() {
        this(false);
    }

    /**
     * @param allowSyntheticFixture 실제 관측 없이 만든 합성 번들을 허용할지 정한다.
     *     합성 번들은 재생과 정산 검증을 실행하지 않았으므로 로컬 개발 환경에서만 적재한다
     */
    public FileModelBundleLoader(final boolean allowSyntheticFixture) {
        this.allowSyntheticFixture = allowSyntheticFixture;
    }

    @Override
    public ModelBundleFiles filesUnder(String directory) {
        Path path = Path.of(directory);
        BundleFiles files = BundleFiles.under(path);
        return () -> loadBundle(path, files.readManifest());
    }

    private ModelRelease loadBundle(Path directory, byte[] manifestBytes) {
        JsonNode manifest = readManifest(manifestBytes);
        String schema = manifest.path("schema").asString("");
        try {
            return switch (schema) {
                case "alighting-inference-bundle-v1" -> AlightingAwareServingBundle.load(
                    directory,
                    allowSyntheticFixture
                );
                case TransferredServingBundle.SCHEMA -> TransferredServingBundle.load(
                    directory,
                    manifestBytes,
                    manifest
                );
                default -> throw new BundleRejectedException("지원이 종료된 좌석분포 모델 번들이다");
            };
        } catch (IOException | IllegalArgumentException error) {
            throw new BundleRejectedException("하차 반영 모델의 운영 번들을 검증하지 못했다", error);
        }
    }

    private static JsonNode readManifest(byte[] bytes) {
        try {
            JsonNode manifest = JsonMapper.builder().build().readTree(bytes);
            if (!manifest.isObject()) {
                throw new BundleRejectedException("모델 manifest는 JSON 객체여야 한다");
            }
            return manifest;
        } catch (JacksonException error) {
            throw new BundleRejectedException("모델 manifest의 JSON을 읽을 수 없다", error);
        }
    }
}
