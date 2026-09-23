package com.gustler.backend.routecatalog.domain;

public interface RouteRegistry {

    /** 노선 행을 확보하고 ID를 반환한다. 이미 있으면 이름만 상류에서 받은 값으로 갱신한다. */
    long register(
        UpstreamRoute route
    );
}
