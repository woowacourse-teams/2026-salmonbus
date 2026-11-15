package com.gustler.backend.forecasting.infrastructure.jdbc;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePassEvent;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Function;

/** 한 목표의 결과와 근거 식별자를 같은 트랜잭션으로 저장하기 위한 출력 계약이다. */
record PassLabelOutcome(
    String state,
    Double label,
    String reason,
    AlightingAwarePassEvent event,
    AlightingAwarePassEvent previous,
    AlightingAwareObservation capacityObservation,
    String parameterHash,
    Integer capacity
) {
    static PassLabelOutcome unsupported(String reason) {
        return unsupported(reason, null);
    }

    static PassLabelOutcome unsupported(String reason, AlightingAwarePassEvent event) {
        return new PassLabelOutcome("UNSUPPORTED", null, reason, event, null, null, null, null);
    }

    Long preObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::arrival);
    }

    Long postObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::departure);
    }

    Long closureObservationId() {
        return eventObservationId(event, AlightingAwarePassEvent::confirmation);
    }

    Long previousPostObservationId() {
        return eventObservationId(previous, AlightingAwarePassEvent::departure);
    }

    Long previousClosureObservationId() {
        return eventObservationId(previous, AlightingAwarePassEvent::confirmation);
    }

    private Long eventObservationId(
        AlightingAwarePassEvent pass,
        Function<AlightingAwarePassEvent, AlightingAwareObservation> observation
    ) {
        if (pass == null) {
            return null;
        }
        return observation.apply(pass).observationId();
    }

    Long capacityObservationId() {
        if (capacityObservation == null) {
            return null;
        }
        return capacityObservation.observationId();
    }

    OffsetDateTime eventAt() {
        if (event == null) {
            return null;
        }
        if (event.arrival().confirmedProfile()) {
            return offset(event.departure().receivedAt());
        }
        return offset(event.firstDeparture().receivedAt());
    }

    OffsetDateTime knownAt() {
        if (event == null) {
            return null;
        }
        if (previous != null && previous.confirmedAt().isAfter(event.confirmedAt())) {
            return offset(previous.confirmedAt());
        }
        return offset(event.confirmedAt());
    }

    String capacityPolicy() {
        if (capacity == null) {
            return null;
        }
        return "vehicle-capacity-snapshot-v1";
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
