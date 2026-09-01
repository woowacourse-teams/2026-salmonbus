package com.gustler.backend.collector;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.web.util.UriUtils;

@ConfigurationProperties("gbis")
public record GbisProperties(
    String baseUrl,
    String serviceKey,
    int dailyLimit
) {

    private static final String BROKEN_PERCENT =
        "gbis.service-key 의 퍼센트 인코딩이 깨졌다. 공공데이터포털에서 받은 값을 그대로 넣어라";

    public GbisProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("gbis.base-url must not be blank");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                "gbis.service-key must not be blank. Set the GBIS_SERVICE_KEY environment variable");
        }
        serviceKey = decodedOnce(serviceKey);
        if (dailyLimit <= 0) {
            throw new IllegalStateException("gbis.daily-limit must be positive");
        }
    }

    /**
     * 어느 형태로 받아도 원래 값 하나로 맞춘다.
     *
     * <p>공공데이터포털은 2025-08-21 전에 발급한 인증키를 퍼센트로 바꾼 판본과 원래 판본 두 벌로 준다.
     * 어느 쪽을 환경변수에 넣었는지 알 수 없어서 일단 되돌린다. 원래 판본을 넣었으면 아무 일도 안 한다.
     *
     * <p>깨진 값은 여기서 막는다. 호출할 때 터지면 예외가 수집기까지 올라가 스택 트레이스로 남고,
     * {@code UriUtils} 의 예외 메시지에는 못 읽은 자리부터 끝까지가 들어 있어 인증키 뒷부분이 로그에 샌다.
     * 그래서 원인 예외를 안 달고 새로 던진다.
     */
    private static String decodedOnce(
        String serviceKey
    ) {
        try {
            return UriUtils.decode(serviceKey, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(BROKEN_PERCENT);
        }
    }

    @Override
    public String toString() {
        return "GbisProperties[baseUrl=%s, serviceKey=***, dailyLimit=%d]".formatted(baseUrl, dailyLimit);
    }
}
