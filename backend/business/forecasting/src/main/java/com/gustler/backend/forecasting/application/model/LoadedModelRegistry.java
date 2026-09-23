package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.ModelRelease;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 전체 식별 정보로 모델을 보관한다. 활성화 실패와 진행 중 계산은 서로 영향을 주지 않는다. */
public final class LoadedModelRegistry {
    private final Map<ModelIdentity, ModelRelease> models = new ConcurrentHashMap<>();

    public Optional<ModelRelease> find(ModelIdentity identity) {
        return Optional.ofNullable(models.get(identity));
    }

    public void register(ModelRelease release) {
        models.putIfAbsent(release.identity(), release);
    }

    public int size() { return models.size(); }
}
