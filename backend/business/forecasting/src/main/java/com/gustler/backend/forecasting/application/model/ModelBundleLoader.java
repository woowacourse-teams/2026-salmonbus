package com.gustler.backend.forecasting.application.model;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;

/** 파일 형식과 검증 구현은 모델 적재 어댑터가 담당한다. */
public interface ModelBundleLoader {
    ModelRelease load(String directory);
}
