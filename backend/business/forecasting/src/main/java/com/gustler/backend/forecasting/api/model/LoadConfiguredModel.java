package com.gustler.backend.forecasting.api.model;

/**
 * 검증할 모델 입력 명령을 기동 시 모델 적재 흐름으로 전달하는 공개 업무 경계다.
 */
public interface LoadConfiguredModel {
    ModelLoadResult load(LoadConfiguredModelCommand command);
}
