package com.gustler.backend.routeoperations.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RouteOperationCacheTest {
    private static final String ID = "228000184";

    @Test
    void 로드_전과_연결_해제_후에는_기존_ON을_반환하지_않는다() {
        RouteOperationCache cache = new RouteOperationCache();
        assertThatThrownBy(cache::list).hasMessage("ROUTE_OPERATIONS_UNAVAILABLE");
        cache.replaceAll(List.of(operation(ID)));
        assertThat(cache.find(ID)).isPresent();
        cache.invalidate();
        assertThatThrownBy(() -> cache.find(ID)).hasMessage("ROUTE_OPERATIONS_UNAVAILABLE");
    }

    @Test
    void 변경된_노선만_교체하고_삭제하며_정렬된_불변_목록을_제공한다() {
        RouteOperationCache cache = new RouteOperationCache();
        cache.replaceAll(List.of(operation(ID), operation("200000314")));
        assertThat(cache.list()).extracting(RouteOperation::routeId).containsExactly("200000314", ID);
        cache.replace(ID, Optional.empty());
        assertThat(cache.list()).extracting(RouteOperation::routeId).containsExactly("200000314");
        cache.replace(ID, Optional.of(operation(ID)));
        assertThat(cache.find(ID)).isPresent();
        assertThatThrownBy(() -> cache.list().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 연결_확인이_15초를_넘으면_상태를_반환하지_않는다() {
        AtomicLong clock = new AtomicLong();
        RouteOperationCache cache = new RouteOperationCache(clock::get);
        cache.replaceAll(List.of(operation(ID)));
        clock.set(Duration.ofSeconds(10).toNanos());
        cache.verified();
        clock.set(Duration.ofSeconds(24).toNanos());
        assertThat(cache.find(ID)).isPresent();
        clock.set(Duration.ofSeconds(26).toNanos());
        assertThatThrownBy(cache::list).hasMessage("ROUTE_OPERATIONS_UNAVAILABLE");
    }

    @Test
    void 일반_조회는_DB를_호출하지_않고_발행_판정은_DB에_위임한다() {
        RouteOperations database = mock(RouteOperations.class);
        RouteOperationCache cache = new RouteOperationCache();
        cache.replaceAll(List.of(operation(ID)));
        CachedRouteOperations operations = new CachedRouteOperations(database, cache);
        assertThat(operations.list()).hasSize(1);
        assertThat(operations.find(ID)).isPresent();
        assertThat(operations.required(ID).serviceEnabled()).isTrue();
        verifyNoInteractions(database);
        Instant observedAt = Instant.now();
        when(database.allowPublication(123, observedAt)).thenReturn(false);
        assertThat(operations.allowPublication(123, observedAt)).isFalse();
        verify(database).allowPublication(123, observedAt);
    }

    private RouteOperation operation(String routeId) {
        Instant now = Instant.now();
        return new RouteOperation(routeId, "READY", true, true, true, true,
            "NORMAL", "", "", 1, 1, now, "", now, now);
    }
}
