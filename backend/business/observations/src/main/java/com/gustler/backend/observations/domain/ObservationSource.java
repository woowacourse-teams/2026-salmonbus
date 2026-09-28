package com.gustler.backend.observations.domain;

public interface ObservationSource {
    ObservationReply read(String sourceRouteId, String keyAlias);
}
