package com.gustler.backend.routecatalog.api;

/** 현재 판본에 형상 입력을 발행한 트랜잭션이 커밋된 뒤 전달한다. */
public record RouteGeometryInputAvailable(String routeId, long routeVersionId) {}
