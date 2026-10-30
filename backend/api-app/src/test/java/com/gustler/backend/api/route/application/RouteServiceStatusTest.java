package com.gustler.backend.api.route.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.route.domain.RouteServiceStatus;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class RouteServiceStatusTest {
    private final Instant now = Instant.parse("2026-10-29T12:00:00Z");

    @Test
    void 관측과_모델_없이_안내를_조회하고_30초_뒤_만료된다() {
        RouteOperations operations = mock(RouteOperations.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("ops", operations);
        when(operations.find("228000184"))
            .thenReturn(
                Optional.of(
                    new RouteOperation(
                        "228000184",
                        "READY",
                        true,
                        false,
                        false,
                        true,
                        "STRIKE",
                        "파업으로 운행 중단",
                        "https://example.org",
                        2,
                        1,
                        now,
                        "",
                        now,
                        null)));
        RouteServiceStatusQuery query =
            new RouteServiceStatusQuery(
                beans.getBeanProvider(RouteOperations.class),
                Clock.fixed(now, ZoneOffset.UTC));
        RouteServiceStatus status = query.get("228000184");
        assertThat(status.forecastAllowed()).isFalse();
        assertThat(status.message()).isEqualTo("파업으로 운행 중단");
        assertThat(status.validUntil()).isEqualTo(now.plusSeconds(30));
    }

    @Test
    void 기능을_끄면_기존_예보_허용을_유지한다() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        RouteServiceStatus status =
            new RouteServiceStatusQuery(
                    beans.getBeanProvider(RouteOperations.class),
                    Clock.fixed(now, ZoneOffset.UTC))
                .get("228000184");
        assertThat(status.managed()).isFalse();
        assertThat(status.forecastAllowed()).isTrue();
    }

    @Test
    void DB_조회_실패를_예보_허용으로_바꾸지_않는다() {
        RouteOperations operations = mock(RouteOperations.class);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("ops", operations);
        when(operations.find(anyString())).thenThrow(new IllegalStateException("offline"));
        RouteServiceStatusQuery query =
            new RouteServiceStatusQuery(
                beans.getBeanProvider(RouteOperations.class),
                Clock.fixed(now, ZoneOffset.UTC));
        assertThatThrownBy(() -> query.get("228000184")).isInstanceOf(IllegalStateException.class);
    }
}
