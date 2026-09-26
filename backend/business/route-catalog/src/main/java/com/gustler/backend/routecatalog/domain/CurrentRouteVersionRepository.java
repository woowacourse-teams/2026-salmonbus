package com.gustler.backend.routecatalog.domain;

import java.util.OptionalLong;

public interface CurrentRouteVersionRepository {

    /** 현재 사용하는 노선 버전의 ID. 생성된 버전이 없으면 빈 결과를 반환한다. */
    OptionalLong findIdOf(
        String sourceRouteId
    );
}
