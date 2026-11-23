package com.gustler.backend.api.board.controller;

import com.gustler.backend.api.board.application.BoardQueryService;
import com.gustler.backend.api.board.application.WatchChangeDetector;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code @RestController} 는 컴포넌트 스캔이 이 설정의 조건과 상관없이 등록하므로 {@link WatchStreamController} 가 같은 조건을
 * 직접 단다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = WatchStreamConfiguration.ENABLED, havingValue = "true")
@EnableConfigurationProperties(WatchStreamSettings.class)
class WatchStreamConfiguration {
    static final String ENABLED = "watch.stream.enabled";

    @Bean(initMethod = "start")
    WatchStreamRegistry watchStreamRegistry(WatchStreamSettings settings, MeterRegistry meters) {
        return new WatchStreamRegistry(settings, meters);
    }

    @Bean(initMethod = "start")
    WatchChangeDetector watchChangeDetector(WatchStreamRegistry registry, BoardQueryService boards,
        WatchStreamSettings settings, MeterRegistry meters) {
        return new WatchChangeDetector(registry, boards, settings.detectInterval(), settings.readTimeout(), meters);
    }
}
