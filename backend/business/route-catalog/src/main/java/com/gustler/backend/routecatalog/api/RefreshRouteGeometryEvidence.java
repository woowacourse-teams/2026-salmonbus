package com.gustler.backend.routecatalog.api;

/** 노선별 형상 수집에 앞서 공통 지도 근거의 갱신을 완료한다. */
public interface RefreshRouteGeometryEvidence {
    void refresh();
}
