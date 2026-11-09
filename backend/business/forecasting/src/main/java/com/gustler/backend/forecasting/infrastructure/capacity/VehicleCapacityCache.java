package com.gustler.backend.forecasting.infrastructure.capacity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/** Worker에서 예보와 수요 통계가 함께 사용하는 관측 정원이다. PostgreSQL이 정원의 원본이다. */
@Component
public class VehicleCapacityCache {
    private final JdbcVehicleCapacityStore store;
    private final ConcurrentMap<VehicleKey, JdbcVehicleCapacityStore.Capacity> capacities = new ConcurrentHashMap<>();

    public VehicleCapacityCache(JdbcVehicleCapacityStore store) {
        this.store = store;
    }

    public void warmUp() {
        remember(store.findAll());
    }

    public Map<String, JdbcVehicleCapacityStore.Capacity> find(String sourceId, List<String> vehicleIds) {
        List<String> missing = vehicleIds.stream()
            .distinct()
            .filter(vehicleId -> !capacities.containsKey(new VehicleKey(sourceId, vehicleId)))
            .toList();

        if (!missing.isEmpty()) {
            remember(store.findByVehicleIds(sourceId, missing));
        }

        return vehicleIds.stream()
            .map(vehicleId -> capacities.get(new VehicleKey(sourceId, vehicleId)))
            .filter(Objects::nonNull)
            .collect(
                LinkedHashMap::new,
                (byVehicle, capacity) -> byVehicle.put(capacity.vehicleId(), capacity),
                Map::putAll);
    }

    /** 지연된 조회 응답이나 커밋 콜백이 먼저 반영한 상향값을 덮지 않게 한다. */
    public void remember(List<JdbcVehicleCapacityStore.Capacity> committed) {
        committed.stream()
            .forEach(capacity -> capacities.merge(
                new VehicleKey(capacity.sourceId(), capacity.vehicleId()),
                capacity,
                VehicleCapacityCache::higherCapacity));
    }

    private static JdbcVehicleCapacityStore.Capacity higherCapacity(
        JdbcVehicleCapacityStore.Capacity current,
        JdbcVehicleCapacityStore.Capacity incoming
    ) {
        if (incoming.seats() > current.seats()) {
            return incoming;
        }
        return current;
    }

    private record VehicleKey(String sourceId, String vehicleId) {
    }
}
