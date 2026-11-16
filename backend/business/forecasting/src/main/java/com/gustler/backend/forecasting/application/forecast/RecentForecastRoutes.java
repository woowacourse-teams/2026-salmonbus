package com.gustler.backend.forecasting.application.forecast;

import java.time.Instant;
import java.util.List;

/**
 * 최근 예보 실행이 있는 노선을 찾는다. 채점 단계가 기동 직후 노선마다 한 번 되감을 대상을 고르는 데 쓴다.
 */
public interface RecentForecastRoutes {
    /**
     * 수신 시각이 {@code fromInclusive} 이후인 예보 실행이 있는 노선을 돌려준다.
     *
     * @param fromInclusive 수신 시각 하한(포함)
     * @return 노선 번호. 오름차순이고 중복이 없다
     */
    List<Long> routesPublishedSince(Instant fromInclusive);
}
