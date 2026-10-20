package com.gustler.backend.forecasting.api.model;

/**
 * 설정에서 선택한 모델 입력을 적재 흐름으로 전달한다.
 * 적재와 활성 배포의 결과는 별도로 확인한다.
 */
public record LoadConfiguredModelCommand(String directory, boolean promoteOnStart) {
}
