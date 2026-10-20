package com.gustler.backend.routecatalog.domain;

/** 외부 자료를 카탈로그가 사용하는 노선 값으로 읽는 포트다. */
public interface RouteSource {
    /**
     * 한 노선의 원천 정보를 읽는 데 예약해야 할 호출 횟수를 반환한다. 실제 전송 전에 할당량 계산에 사용한다.
     */
    int requiredCallsPerRead();

    /**
     * 지정 원천 노선을 읽어 카탈로그 입력으로 변환한다. 정상 입력 또는 읽기 실패 결과를 반환한다.
     */
    RouteSourceResult read(String sourceRouteId);
}
