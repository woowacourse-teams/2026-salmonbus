package com.gustler.backend.routecatalog.domain;

/** 외부 자료를 카탈로그가 사용하는 노선 값으로 읽는 포트다. */
public interface RouteSource {

    int requiredCallsPerRead();

    RouteSourceResult read(String sourceRouteId);
}
