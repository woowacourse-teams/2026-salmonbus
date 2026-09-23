package com.gustler.backend.forecasting.infrastructure.observations;

import com.gustler.backend.forecasting.application.quality.QualityInputRetention;
import com.gustler.backend.observations.api.CollectionInputs;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CollectionQualityInputRetention implements QualityInputRetention {
    private final CollectionInputs inputs;
    private final Clock clock;
    public CollectionQualityInputRetention(final CollectionInputs inputs, final Clock clock) {
        this.inputs = inputs;
        this.clock = clock;
    }

    @Override
    public void confirmObservations(final List<Long> observationIds) {
        final Set<Long> confirmed = new HashSet<>();
        for (final long id : observationIds.stream().distinct().sorted().toList()) {
            final var input = inputs.lockForObservation(id);
            if (confirmed.add(input.batchId()) && !input.inputConfirmed()) {
                inputs.confirmInput(input.batchId(), input.attemptNumber(), clock.instant());
            }
        }
    }
}
