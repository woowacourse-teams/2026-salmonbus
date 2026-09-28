package com.gustler.backend.forecasting.domain.route;

import java.util.List;

public interface RouteVersionQuery {

    /** 유효 기간이 종료되지 않은 현재 노선 버전 ID를 조회한다. */
    List<Long> findActiveVersionIds();
}
