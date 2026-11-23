package com.gustler.backend.api.board.controller;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxConnections 서버가 동시에 열어 두는 연결의 상한. 넘는 요청은 204 로 거절한다. 1 이상이다
 * @param heartbeatInterval ping 이벤트를 보내는 주기. CloudFront 는 오리진이 30초 동안 아무것도 보내지 않으면 연결을 끊으므로
 *     그보다 짧게 둔다
 * @param timeout 연결 하나의 수명. 지나면 서버가 스트림을 정상 종료하고 화면이 다시 연결한다. 0 이하는 서블릿이 "제한 없음"으로
 *     읽으므로 받지 않는다
 * @param detectInterval 변경 감지기가 확인 하나를 끝낸 뒤 다음 확인까지 쉬는 시간. 보는 화면이 있는 동안 이 간격으로 DB 를 읽는다
 * @param readTimeout 변경 감지기의 조회 문장 하나가 잠금 대기를 포함해 쓸 수 있는 시간. 1ms 보다 짧으면 PostgreSQL 이
 *     "제한 없음"인 0 으로 읽으므로 받지 않는다
 */
@ConfigurationProperties("watch.stream")
record WatchStreamSettings(
    @DefaultValue("200") int maxConnections,
    @DefaultValue("15s") Duration heartbeatInterval,
    @DefaultValue("30m") Duration timeout,
    @DefaultValue("1s") Duration detectInterval,
    @DefaultValue("1s") Duration readTimeout
) {
    WatchStreamSettings {
        if (maxConnections < 1) {
            throw new IllegalArgumentException("watch.stream.max-connections는 1 이상이어야 한다");
        }
        requirePositive(heartbeatInterval, "watch.stream.heartbeat-interval");
        requirePositive(timeout, "watch.stream.timeout");
        requirePositive(detectInterval, "watch.stream.detect-interval");
        if (readTimeout == null || readTimeout.toMillis() < 1) {
            throw new IllegalArgumentException("watch.stream.read-timeout은 1ms 이상이어야 한다");
        }
    }

    private static void requirePositive(Duration value, String key) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + "은 0보다 커야 한다");
        }
    }
}
