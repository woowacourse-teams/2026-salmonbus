package com.gustler.backend.routecatalog.api;

/** 새 판본 저장 트랜잭션 안에서 발행한다. 후속 작업은 커밋 이후에 처리한다. */
public record RouteVersionChanged(long routeId, long routeVersionId) {}
