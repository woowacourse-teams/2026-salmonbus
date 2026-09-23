package com.gustler.backend.forecasting.api.model;

public interface ActivateModel {
    ModelActivationResult activate(ActivateModelCommand command);
}
