package com.gustler.backend.worker.configuration;

import com.gustler.backend.gbis.api.GbisKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
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
    Map<String, String> keys,
    int dailyLimit,
    Map<String, Integer> limits,
    Map<String, String> namedKeys
) {

    private static final String BROKEN_PERCENT =
        "%s 의 퍼센트 인코딩이 깨졌다. 공공데이터포털에서 받은 값을 그대로 넣어라";

    private static final Pattern ALIAS = Pattern.compile("[a-z0-9]{1,16}");

    @ConstructorBinding
    public GbisProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("gbis.base-url must not be blank");
        }
        serviceKey = decodedIfPresent("gbis.service-key", serviceKey);
        serviceKeyB = decodedIfPresent("gbis.service-key-b", serviceKeyB);
        serviceKeyC = decodedIfPresent("gbis.service-key-c", serviceKeyC);
        serviceKeyD = decodedIfPresent("gbis.service-key-d", serviceKeyD);
        namedKeys = namedKeys == null ? Map.of() : Map.copyOf(namedKeys);
        keys = keysOf(serviceKey, serviceKeyB, serviceKeyC, serviceKeyD, namedKeys, keys);
        serviceKey = keys.get(GbisKey.PRIMARY);
        if (dailyLimit <= 0) {
            throw new IllegalStateException("gbis.daily-limit must be positive");
        }
        limits = limitsOf(keys, limits);
    }

    public GbisProperties(
        String baseUrl,
        String serviceKey,
        String serviceKeyB,
        String serviceKeyC,
        String serviceKeyD,
        Map<String, String> keys,
        int dailyLimit,
        Map<String, Integer> limits
    ) {
        this(baseUrl, serviceKey, serviceKeyB, serviceKeyC, serviceKeyD, keys, dailyLimit, limits, Map.of());
    }

    public GbisProperties(
        String baseUrl,
        String serviceKey,
        String serviceKeyB,
        String serviceKeyC,
        String serviceKeyD,
        Map<String, String> keys,
        int dailyLimit
    ) {
        this(baseUrl, serviceKey, serviceKeyB, serviceKeyC, serviceKeyD, keys, dailyLimit, Map.of());
    }

    public GbisProperties(
        String baseUrl,
        String serviceKey,
        String serviceKeyB,
        String serviceKeyC,
        String serviceKeyD,
        int dailyLimit
    ) {
        this(baseUrl, serviceKey, serviceKeyB, serviceKeyC, serviceKeyD, Map.of(), dailyLimit, Map.of());
    }

    public GbisProperties(
        String baseUrl,
        String serviceKey,
        int dailyLimit
    ) {
        this(baseUrl, serviceKey, null, null, null, Map.of(), dailyLimit, Map.of());
    }

    public int dailyLimitOf(
        String alias
    ) {
        return limits.getOrDefault(alias, dailyLimit);
    }

    public List<GbisKey> serviceKeys() {
        return keys.entrySet().stream()
            .map(key -> new GbisKey(key.getKey(), key.getValue()))
            .toList();
    }

    private static Map<String, String> keysOf(
        String serviceKey,
        String serviceKeyB,
        String serviceKeyC,
        String serviceKeyD,
        Map<String, String> named,
        Map<String, String> declared
    ) {
        Map<String, String> merged = new TreeMap<>();
        putIfPresent(merged, GbisKey.PRIMARY, serviceKey);
        putIfPresent(merged, "b", serviceKeyB);
        putIfPresent(merged, "c", serviceKeyC);
        putIfPresent(merged, "d", serviceKeyD);
        new TreeMap<>(named).forEach((alias, value) -> {
            String variable = "GBIS_SERVICE_KEY_" + alias.toUpperCase(Locale.ROOT);
            if (!ALIAS.matcher(alias).matches()) {
                throw new IllegalStateException(variable + " 의 별칭은 영문과 숫자 1~16자여야 한다");
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(variable + " 의 값이 비었다");
            }
            if (merged.containsKey(alias)) {
                throw new IllegalStateException(variable + " 와 다른 GBIS_SERVICE_KEY 설정이 같은 별칭 "
                    + alias + " 를 함께 정한다. 한쪽만 남겨라");
            }
            merged.put(alias, decodedOnce(variable, value));
        });
        Map<String, String> aliased = declared == null ? Map.of() : new TreeMap<>(declared);
        aliased.forEach((alias, value) -> {
            String property = "gbis.keys." + alias;
            if (!ALIAS.matcher(alias).matches()) {
                throw new IllegalStateException("gbis.keys 의 별칭은 영문 소문자와 숫자 1~16자여야 한다: " + alias);
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(property + " 의 값이 비었다. 쓰지 않을 키는 줄을 지워라");
            }
            if (merged.containsKey(alias)) {
                throw new IllegalStateException(property + " 와 GBIS_SERVICE_KEY 계열 설정이 같은 별칭 "
                    + alias + " 를 함께 정한다. 한쪽만 남겨라");
            }
            merged.put(alias, decodedOnce(property, value));
        });
        if (!merged.containsKey(GbisKey.PRIMARY)) {
            throw new IllegalStateException(
                "gbis.service-key must not be blank. Set the GBIS_SERVICE_KEY or GBIS_KEYS_A environment variable");
        }
        rejectDuplicateKeys(merged);
        Map<String, String> ordered = new LinkedHashMap<>();
        ordered.put(GbisKey.PRIMARY, merged.remove(GbisKey.PRIMARY));
        ordered.putAll(merged);
        return Collections.unmodifiableMap(ordered);
    }

    private static Map<String, Integer> limitsOf(
        Map<String, String> keys,
        Map<String, Integer> limits
    ) {
        Map<String, Integer> declared = limits == null ? Map.of() : new TreeMap<>(limits);
        declared.forEach((alias, limit) -> {
            if (!keys.containsKey(alias)) {
                throw new IllegalStateException("gbis.limits." + alias + " 에 맞는 GBIS 키가 없다. 별칭을 확인하라");
            }
            if (limit == null || limit <= 0) {
                throw new IllegalStateException("gbis.limits." + alias + " 는 1 이상이어야 한다");
            }
        });
        return Collections.unmodifiableMap(declared);
    }

    private static void putIfPresent(
        Map<String, String> keys,
        String alias,
        String serviceKey
    ) {
        if (serviceKey != null) {
            keys.put(alias, serviceKey);
        }
    }

    private static void rejectDuplicateKeys(
        Map<String, String> keys
    ) {
        Map<String, List<String>> aliasesByKey = new LinkedHashMap<>();
        keys.forEach((alias, serviceKey) ->
            aliasesByKey.computeIfAbsent(serviceKey, ignored -> new ArrayList<>()).add(alias));
        aliasesByKey.values().stream()
            .filter(aliases -> aliases.size() > 1)
            .findFirst()
            .ifPresent(aliases -> {
                throw new IllegalStateException("같은 GBIS 인증키를 별칭 " + String.join(",", aliases)
                    + " 에 함께 넣었다. 한도를 두 번 세므로 하나만 남겨라");
            });
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
        return "GbisProperties[baseUrl=%s, keyAliases=%s, serviceKey=***, dailyLimit=%d]"
            .formatted(baseUrl, String.join(",", keys.keySet()), dailyLimit);
    }
}
