package com.gustler.backend.collector;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.web.util.UriUtils;

@ConfigurationProperties("gbis")
public record GbisProperties(
    String baseUrl,
    String serviceKey,
    String serviceKeyB,
    String serviceKeyC,
    String serviceKeyD,
    int dailyLimit
) {

    private static final String BROKEN_PERCENT =
        "%s 의 퍼센트 인코딩이 깨졌다. 공공데이터포털에서 받은 값을 그대로 넣어라";
    private static final String UNKNOWN_SLOT = "설정에 없는 GBIS 키 슬롯이다: ";

    @ConstructorBinding
    public GbisProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("gbis.base-url must not be blank");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException(
                "gbis.service-key must not be blank. Set the GBIS_SERVICE_KEY environment variable");
        }
        serviceKey = decodedOnce("gbis.service-key", serviceKey);
        serviceKeyB = decodedIfPresent("gbis.service-key-b", serviceKeyB);
        serviceKeyC = decodedIfPresent("gbis.service-key-c", serviceKeyC);
        serviceKeyD = decodedIfPresent("gbis.service-key-d", serviceKeyD);
        if (dailyLimit <= 0) {
            throw new IllegalStateException("gbis.daily-limit must be positive");
        }
    }

    public GbisProperties(
        String baseUrl,
        String serviceKey,
        int dailyLimit
    ) {
        this(baseUrl, serviceKey, null, null, null, dailyLimit);
    }

    public List<GbisKey> keys() {
        return Stream.of(
                new GbisKey(GbisKey.PRIMARY, serviceKey),
                new GbisKey("b", serviceKeyB),
                new GbisKey("c", serviceKeyC),
                new GbisKey("d", serviceKeyD))
            .filter(key -> key.serviceKey() != null)
            .toList();
    }

    public String serviceKeyOf(
        String keyAlias
    ) {
        return keys().stream()
            .filter(key -> key.alias().equals(keyAlias))
            .map(GbisKey::serviceKey)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(UNKNOWN_SLOT + keyAlias));
    }

    private static String decodedIfPresent(
        String property,
        String serviceKey
    ) {
        if (serviceKey == null || serviceKey.isBlank()) {
            return null;
        }
        return decodedOnce(property, serviceKey);
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
        String property,
        String serviceKey
    ) {
        try {
            return UriUtils.decode(serviceKey, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(BROKEN_PERCENT.formatted(property));
        }
    }

    @Override
    public String toString() {
        return "GbisProperties[baseUrl=%s, serviceKey=***, dailyLimit=%d]".formatted(baseUrl, dailyLimit);
    }
}
