package com.gustler.backend.worker.configuration;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 키 별칭 환경 설정을 GBIS 속성으로 연결한다.
 * 인증값 원문을 기동 진단에 출력하지 않는다.
 */
public final class GbisServiceKeyEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PROPERTY_SOURCE = "gbisServiceKeys";

    private static final String PREFIX = "GBIS_SERVICE_KEY_";
    private static final Set<String> BOUND_BY_APPLICATION_YML = Set.of("B", "C", "D");

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!(environment.getPropertySources().get(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)
            instanceof EnumerablePropertySource<?> variables)) {
            return;
        }
        Map<String, Object> keys = new TreeMap<>();
        for (String name : variables.getPropertyNames()) {
            if (!name.startsWith(PREFIX)) {
                continue;
            }
            String alias = name.substring(PREFIX.length());
            Object value = variables.getProperty(name);
            if (shouldIgnore(alias, value)) {
                continue;
            }
            String property = "gbis.named-keys[" + alias.toLowerCase(Locale.ROOT) + "]";
            if (keys.containsKey(property)) {
                throw new IllegalStateException("GBIS_SERVICE_KEY_ 뒤의 별칭이 대소문자만 다른 변수가 둘 있다: "
                    + alias.toLowerCase(Locale.ROOT) + ". 하나만 남겨라");
            }
            keys.put(property, value);
        }
        if (!keys.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, keys));
        }
    }

    private boolean shouldIgnore(String alias, Object value) {
        if (BOUND_BY_APPLICATION_YML.contains(alias) || value == null) {
            return true;
        }
        return value.toString().isBlank();
    }
}
