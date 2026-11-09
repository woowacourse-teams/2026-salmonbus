package com.gustler.backend.forecasting.api;

/** 스케줄이 시작되기 전에 PostgreSQL의 최신 차량 정원을 메모리에 올린다. */
public interface WarmUpVehicleCapacities {
    void warmUp();
}
