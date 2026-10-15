package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class RouteGeometryCatalogPropertiesTest {
    @Test
    void 전체카탈로그수집은_명시적으로_켰을때만_활성화한다() {
        var source = new MapConfigurationPropertySource(Map.of("route-geometry.enabled", "true",
            "route-geometry.include-catalog", "true"));

        var properties = new Binder(source).bind("route-geometry", Bindable.of(RouteGeometryProperties.class)).get();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.includeCatalog()).isTrue();
        assertThat(properties.routeIds()).isEmpty();
    }

    @Test
    void 기존속성과_두인자생성자는_기본수집범위를_유지한다() {
        var source = new MapConfigurationPropertySource(Map.of("route-geometry.enabled", "true",
            "route-geometry.route-ids[0]", "204000057"));

        var properties = new Binder(source).bind("route-geometry", Bindable.of(RouteGeometryProperties.class)).get();

        assertThat(properties.includeCatalog()).isFalse();
        assertThat(properties.routeIds()).containsExactly("204000057");
        assertThat(new RouteGeometryProperties(true, List.of()).includeCatalog()).isFalse();
    }
}
