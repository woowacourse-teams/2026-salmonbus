package com.gustler.backend.forecasting.configuration;

import com.gustler.backend.forecasting.infrastructure.statistics.EvaluationArchiveObjectStore;
import com.gustler.backend.forecasting.api.evaluation.EvaluationArchivePolicy;
import com.gustler.backend.forecasting.api.evaluation.AdvanceEvaluationArchive;
import com.gustler.backend.forecasting.application.evaluation.AdvanceEvaluationArchiveService;
import com.gustler.backend.forecasting.application.evaluation.ArchiveCompletedEvaluationsService;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveQueue;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveRetentionStore;
import com.gustler.backend.forecasting.application.evaluation.EvaluationArchiveStore;
import com.gustler.backend.forecasting.application.evaluation.PurgeArchivedEvaluationsService;
import java.time.Clock;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration(proxyBeanMethods = false)
public class EvaluationArchiveConfiguration {
    @Bean
    @Lazy
    EvaluationArchiveObjectStore evaluationArchiveObjectStore(
        @Value("${forecast.archive.work-directory:/var/lib/salmonbus/evaluation-archive}") Path directory,
        @Value("${forecast.archive.max-file-bytes:4194304}") long maxBytes) {
        return new EvaluationArchiveObjectStore(directory, maxBytes);
    }

    @Bean
    @ConditionalOnProperty(prefix = "forecast.archive", name = "enabled", havingValue = "true")
    EvaluationArchivePolicy evaluationArchivePolicy(
        @Value("${forecast.archive.delete-enabled:false}") boolean deleteEnabled,
        @Value("${forecast.archive.page-size:500}") int pageSize) {
        return new EvaluationArchivePolicy(deleteEnabled, pageSize);
    }

    @Bean
    @ConditionalOnProperty(prefix = "forecast.archive", name = "enabled", havingValue = "true")
    AdvanceEvaluationArchive advanceEvaluationArchive(
        EvaluationArchiveQueue queue,
        EvaluationArchiveStore store,
        EvaluationArchiveRetentionStore retention,
        EvaluationArchiveObjectStore objects,
        EvaluationArchivePolicy policy,
        Clock clock
    ) {
        return new AdvanceEvaluationArchiveService(queue, store,
            new ArchiveCompletedEvaluationsService(store, objects),
            new PurgeArchivedEvaluationsService(retention, objects), policy, clock);
    }
}
