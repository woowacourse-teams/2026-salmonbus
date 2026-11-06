package com.gustler.backend.api.boarding.domain;

/**
 * 탑승 정류장까지 가는 구간의 이동 수단. 지금은 도보만 있다. 새 수단은 그 수단을 쓰는 {@code AccessFinder}와 함께 더한다.
 */
public enum AccessMode {
    WALK
}
