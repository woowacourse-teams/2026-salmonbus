package com.gustler.backend.forecasting.api.model;

public record LoadConfiguredModelCommand(String directory, boolean promoteOnStart) { }
