package com.gustler.backend.stations.domain;

import java.time.Duration;
import java.util.List;

/**
 * 좌표 반경 500m 안의 정류장을 원천에서 받는 포트다.
 */
public interface StationAroundSource {

    Result around(double x, double y, String keyAlias);

    sealed interface Result {
    }

    /**
     * 정상 응답. remaining은 그 API의 오늘 남은 호출 수이며 모르면 null이다.
     */
    record Found(List<BusStation> stations, Integer remaining) implements Result {

        public Found {
            stations = List.copyOf(stations);
        }
    }

    /**
     * 결과 코드 4. 반경 안에 정류장이 없다.
     */
    record NoStations(Integer remaining) implements Result {
    }

    /**
     * 헤더 없는 빈 응답이고 남은 값이 0이다. 순간 상한을 넘겨 잠시 막힌 상태다.
     */
    record Throttled() implements Result {
    }

    /**
     * 헤더 없는 빈 응답이고 남은 값이 0이 아니다. 정류장 없는 좌표를 동시에 부를 때 생긴다. 다시 부르면 풀린다.
     */
    record Ambiguous(Integer remaining) implements Result {
    }

    /**
     * 하루 한도 초과 또는 키 거부. 그날은 더 부르지 않는다.
     */
    record Exhausted(String reason) implements Result {
    }

    /**
     * 그 밖의 실패.
     */
    record Failed(String reason, Duration retryAfter) implements Result {
    }
}
