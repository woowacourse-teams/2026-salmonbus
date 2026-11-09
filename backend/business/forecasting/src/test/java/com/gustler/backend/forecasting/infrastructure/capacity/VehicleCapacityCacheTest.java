package com.gustler.backend.forecasting.infrastructure.capacity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class VehicleCapacityCacheTest {
    private static final String SOURCE = "GBIS";
    private static final Instant EVIDENCE_AT = Instant.parse("2026-11-06T00:00:00Z");

    @Test
    void 캐시에_없는_차량만_DB에서_읽어_캐시에_채운다() {
        // given
        JdbcVehicleCapacityStore store = mock(JdbcVehicleCapacityStore.class);
        JdbcVehicleCapacityStore.Capacity initial = capacity("initial", 44);
        JdbcVehicleCapacityStore.Capacity missing = capacity("missing", 45);
        given(store.findAll()).willReturn(List.of(initial));
        given(store.findByVehicleIds(SOURCE, List.of("missing"))).willReturn(List.of(missing));
        VehicleCapacityCache cache = new VehicleCapacityCache(store);
        cache.warmUp();

        // when
        cache.find(SOURCE, List.of("initial", "missing"));

        // then
        assertThat(cache.find(SOURCE, List.of("initial", "missing"))).containsEntry("initial", initial)
            .containsEntry("missing", missing);
        verify(store).findAll();
        verify(store).findByVehicleIds(SOURCE, List.of("missing"));
        verifyNoMoreInteractions(store);
    }

    @Test
    void 늦게_도착한_DB_조회값으로_상향된_캐시를_덮지_않는다() {
        // given
        JdbcVehicleCapacityStore store = mock(JdbcVehicleCapacityStore.class);
        VehicleCapacityCache cache = new VehicleCapacityCache(store);
        JdbcVehicleCapacityStore.Capacity raised = capacity("vehicle", 45);
        given(store.findByVehicleIds(SOURCE, List.of("vehicle"))).willAnswer(invocation -> {
            cache.remember(List.of(raised));
            return List.of(capacity("vehicle", 44));
        });

        // when
        JdbcVehicleCapacityStore.Capacity actual = cache.find(SOURCE, List.of("vehicle")).get("vehicle");

        // then
        assertThat(actual).isEqualTo(raised);
    }

    private JdbcVehicleCapacityStore.Capacity capacity(String vehicleId, final int seats) {
        return new JdbcVehicleCapacityStore.Capacity(SOURCE, vehicleId, seats, EVIDENCE_AT, null);
    }
}
