package com.gustler.backend.observations.api;

/** 관측의 정류장 참조 불일치로 저장 트랜잭션이 롤백된 뒤 전달한다. */
public record ObservationRouteMismatchDetected(String sourceRouteId, long routeVersionId) {}
