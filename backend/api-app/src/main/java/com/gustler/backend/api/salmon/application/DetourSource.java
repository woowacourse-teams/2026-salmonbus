package com.gustler.backend.api.salmon.application;

import java.util.Optional;

public interface DetourSource {
    Optional<Double> detourOf(String routeId, WalkOption option);

    static DetourSource unavailable() {
        return (routeId, option) -> Optional.empty();
    }
}
