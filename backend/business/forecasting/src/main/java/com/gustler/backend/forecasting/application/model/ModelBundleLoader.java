package com.gustler.backend.forecasting.application.model;

/** 파일 형식과 검증 구현은 모델 적재 어댑터가 담당한다. */
public interface ModelBundleLoader {
    ModelBundleFiles filesUnder(String directory);
}
