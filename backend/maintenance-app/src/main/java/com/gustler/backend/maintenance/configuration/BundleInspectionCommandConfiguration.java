package com.gustler.backend.maintenance.configuration;

import com.gustler.backend.forecasting.configuration.BundleInspectionConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 모델 활성화나 DB 변경 없이 번들 구조 검사를 수행할 구성만 등록한다.
 */
@Import(BundleInspectionConfiguration.class)
public class BundleInspectionCommandConfiguration {
}
