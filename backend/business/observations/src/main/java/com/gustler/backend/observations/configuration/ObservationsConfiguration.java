package com.gustler.backend.observations.configuration;

import com.gustler.backend.observations.application.ObservationBatchLedger;
import com.gustler.backend.observations.application.ObservationCollector;
import com.gustler.backend.observations.application.ObservationLoader;
import com.gustler.backend.observations.infrastructure.gbis.GbisObservationSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration
@Import({ObservationPersistenceConfiguration.class, GbisObservationSource.class, ObservationCollector.class,
    ObservationBatchLedger.class, ObservationLoader.class})
public class ObservationsConfiguration {
}
