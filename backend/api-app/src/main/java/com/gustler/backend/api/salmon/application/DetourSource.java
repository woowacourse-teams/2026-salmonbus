package com.gustler.backend.api.salmon.application;

import java.util.Optional;

/** 후보의 우회량 D를 주는 포트. D 정의가 채택되기 전까지 구현은 unavailable() 하나다. */
public interface DetourSource {
    Optional<Double> detourOf(String routeId, WalkOption option);

    static DetourSource unavailable() {
        return (routeId, option) -> Optional.empty();
    }
}
