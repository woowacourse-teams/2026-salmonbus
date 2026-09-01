package com.gustler.backend.collector;

public interface RouteRegistry {

    /** 노선 행을 확보하고 그 id 를 준다. 이미 있으면 이름만 상류가 준 값으로 맞춘다. */
    long register(
        UpstreamRoute route
    );
}
