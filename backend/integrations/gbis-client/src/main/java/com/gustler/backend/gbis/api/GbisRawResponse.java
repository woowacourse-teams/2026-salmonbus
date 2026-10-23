package com.gustler.backend.gbis.api;

/**
 * GBIS 를 한 번 호출한 결과다. 아직 무슨 API 였는지는 알 수 없다.
 *
 * <p>응답이 오지 않은 경우와 포털이 GBIS 앞에서 거부한 경우는 어느 API 에서나 같은 형태이므로
 * 여기서 구분한다. 이후 해석은 호출한 쪽이 담당한다.
 */
public sealed interface GbisRawResponse {
    /** 본문을 받았다. GBIS 가 정상으로 답했는지는 아직 모른다. */
    record Received(
        String body
    ) implements GbisRawResponse {
    }

    /** 응답이 오지 않았거나 본문이 비어 있었다. */
    record NotReceived(
        String message
    ) implements GbisRawResponse {
    }

    /** 공공데이터포털이 GBIS 에 도달하기 전에 요청을 거부했다. 한도 초과와 키 거부가 여기에 해당한다. */
    record PortalRejected(
        PortalReasonCode reason,
        String reasonCode,
        String errorCode,
        String message
    ) implements GbisRawResponse {
    }
}
