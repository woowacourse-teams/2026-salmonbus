package com.gustler.backend.collector;

/**
 * GBIS 를 한 번 부르고 받은 것. 아직 무슨 API 였는지는 모른다.
 *
 * <p>응답이 아예 안 온 경우와 포털이 GBIS 앞에서 막은 경우는 어느 API 를 불렀든 같은 모양이라
 * 여기서 갈라둔다. 그 뒤의 해석은 부른 쪽이 한다.
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

    /** 공공데이터포털이 GBIS 에 닿기 전에 막았다. 한도 초과와 키 거부가 여기로 온다. */
    record PortalRejected(
        PortalReasonCode reason,
        String reasonCode,
        String errorCode,
        String message
    ) implements GbisRawResponse {
    }
}
