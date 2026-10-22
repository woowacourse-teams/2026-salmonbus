package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleInspector;
import org.springframework.context.annotation.Import;

/** DB 연결이나 모델 활성화 기능을 등록하지 않는 파일 검수 전용 구성. */
@Import(FileModelBundleInspector.class)
public class BundleInspectionConfiguration {
}
