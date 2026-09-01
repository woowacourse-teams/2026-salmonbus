package com.gustler.backend.collector;

import java.util.OptionalLong;

public interface CurrentRouteVersionRepository {

    /** 그 노선이 지금 쓰는 판본. 아직 연 적이 없으면 비어 있다. */
    OptionalLong findIdOf(
        String upstreamRouteId
    );
}
