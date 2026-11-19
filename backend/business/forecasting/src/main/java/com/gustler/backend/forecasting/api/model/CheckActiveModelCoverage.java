package com.gustler.backend.forecasting.api.model;

import java.util.List;

@FunctionalInterface
public interface CheckActiveModelCoverage {
    boolean coversAll(List<String> sourceRouteIds);
}
