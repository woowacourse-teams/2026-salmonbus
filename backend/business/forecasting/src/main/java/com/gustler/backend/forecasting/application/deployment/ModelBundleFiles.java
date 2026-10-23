package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;

/**
 * 모델 검증에 필요한 파일 입력을 여는 경계다.
 * 파일 검증과 모델 활성화는 별도 단계다.
 */
public interface ModelBundleFiles {
    ModelRelease load();
}
