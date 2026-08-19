package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.List;

/**
 * GBIS 버스위치정보 한 번 호출의 결과.
 *
 * <p>실패가 예외가 아니라 값인 이유는, 쿼터 초과나 상류 장애가 우리한테 일상이기 때문이다.
 * 사유가 타입에 남아야 수집 시도 기록(SAL-85)이 그대로 쓰고 CallBudget(SAL-87)이 골라낼 수 있다.
 */
public sealed interface GbisLocationResult {

    /** 운행 중인 차량을 받았다. queryTime 은 GBIS 가 알려준 관측 시각이다. */
    record Success(String queryTime, List<BusLocation> buses) implements GbisLocationResult {
    }

    /** resultCode 4. 운행 차량이 0대다. 장애가 아니다. */
    record NoVehicles(String queryTime) implements GbisLocationResult {
    }

    /** 게이트웨이 22. 일 호출 한도를 넘겼다. 오늘은 더 못 부른다. */
    record QuotaExceeded() implements GbisLocationResult {
    }

    /** 게이트웨이 23. 초당 허용량을 넘겼다. 잠시 뒤 다시 부르면 된다. */
    record RateLimited() implements GbisLocationResult {
    }

    /** 그 밖의 게이트웨이 거부. 키 오류(30)처럼 사람이 고쳐야 풀린다. */
    record GatewayRejected(String reasonCode, String message) implements GbisLocationResult {
    }

    /** resultCode 1. GBIS 쪽 시스템 에러다. */
    record UpstreamError(String message) implements GbisLocationResult {
    }

    /** 타임아웃·연결 실패. 응답 자체를 못 받았다. */
    record NetworkError(String message) implements GbisLocationResult {
    }

    /** resultCode 2. 필수 파라미터가 빠졌다. 우리 코드의 버그라 호출부가 예외를 던진다. */
    record InvalidRequest(String message) implements GbisLocationResult {
    }
}
