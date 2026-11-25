package com.gustler.backend.routecatalog.api;

/** 수집에 필요한 노선 판본이 없다. 별도 실행기에서 준비하고 이후 수집 회차에서 사용한다. */
public record RouteCatalogPreparationRequested(String sourceRouteId) {
}
