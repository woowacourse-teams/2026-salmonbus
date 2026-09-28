package com.gustler.backend.observations.domain;

import java.time.OffsetDateTime;

public interface ObservationReply {
    ObservationResponse interpret(OffsetDateTime receivedAt);
}
