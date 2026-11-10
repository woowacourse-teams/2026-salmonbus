package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.infrastructure.alighting.AlightingAwareHistoryBootstrapService;
import org.springframework.context.annotation.Import;

/** 체크포인트 준비 기능만 공개한다. Worker·모델 활성화·발행 빈은 등록하지 않는다. */
@Import(AlightingAwareHistoryBootstrapService.class)
public class AlightingAwareHistoryBootstrapConfiguration {
}
