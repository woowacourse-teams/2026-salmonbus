package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.api.model.InspectModelBundle;
import com.gustler.backend.forecasting.api.model.InspectModelBundle.Inspection;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;

/** 기동 시 사용하는 로더를 그대로 호출하며 배포 저장소와 registry를 갖지 않는다. */
public final class FileModelBundleInspector implements InspectModelBundle {
    private final FileModelBundleLoader loader;

    public FileModelBundleInspector() {
        this(false);
    }

    /**
     * @param allowSyntheticFixture {@link FileModelBundleLoader#FileModelBundleLoader(boolean)}와 같은 뜻이다
     */
    public FileModelBundleInspector(final boolean allowSyntheticFixture) {
        this.loader = new FileModelBundleLoader(allowSyntheticFixture);
    }

    @Override
    public Inspection inspect(String directory) {
        ModelRelease release = loader.filesUnder(directory).load();
        return new Inspection(release.releaseId(), release.bundleDigest(),
            release.featureContractVersion(), release.dataThrough(), release.scope().modelRoutes());
    }
}
