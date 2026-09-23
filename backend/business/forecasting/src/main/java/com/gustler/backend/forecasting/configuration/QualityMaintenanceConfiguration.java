package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.application.quality.TripQualityMaintenanceService;
import com.gustler.backend.forecasting.infrastructure.quality.JdbcRouteDataQualityAccess;
import com.gustler.backend.forecasting.infrastructure.quality.JdbcTripQualityMaintenanceStore;
import com.gustler.backend.forecasting.infrastructure.quality.JdbcTripQualityStore;
import com.gustler.backend.forecasting.infrastructure.observations.CollectionQualityInputRetention;
import org.springframework.context.annotation.Import;

/** 수동 정비에 필요한 기능만 등록한다. 스케줄과 모델 적재를 시작하지 않는다. */
@Import({TripQualityInvestigationService.class, TripQualityMaintenanceService.class, JdbcRouteDataQualityAccess.class,
    JdbcTripQualityStore.class, JdbcTripQualityMaintenanceStore.class, CollectionQualityInputRetention.class})
public class QualityMaintenanceConfiguration { }
