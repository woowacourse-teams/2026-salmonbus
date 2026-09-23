package com.gustler.backend.forecasting.api.model;

public interface LoadConfiguredModel {
    ModelLoadResult load(LoadConfiguredModelCommand command);
}
