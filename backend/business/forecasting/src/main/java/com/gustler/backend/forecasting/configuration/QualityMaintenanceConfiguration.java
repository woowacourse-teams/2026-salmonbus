package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.application.quality.RouteDataQualityChanges;
import com.gustler.backend.forecasting.application.quality.TripQualityInvestigationService;
import com.gustler.backend.forecasting.application.quality.TripQualityMaintenanceService;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcDemandStatisticsRebuildRequestStore;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteDataQualityAccess;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteDataQualityRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcTripQualityInvestigationRepository;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcTripQualityMaintenanceStore;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcTripQualityStore;
import org.springframework.context.annotation.Import;

/** 수동 정비에 필요한 기능만 등록한다. 스케줄과 모델 적재를 시작하지 않는다. */
@Import({TripQualityInvestigationService.class, TripQualityMaintenanceService.class, RouteDataQualityChanges.class,
    JdbcRouteDataQualityAccess.class, JdbcRouteDataQualityRepository.class, JdbcTripQualityInvestigationRepository.class,
    JdbcTripQualityStore.class, JdbcTripQualityMaintenanceStore.class, JdbcDemandStatisticsRebuildRequestStore.class})
public class QualityMaintenanceConfiguration { }
