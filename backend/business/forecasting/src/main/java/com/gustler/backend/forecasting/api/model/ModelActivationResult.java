package com.gustler.backend.forecasting.api.model;

import java.time.Instant;

public record ModelActivationResult(long deploymentId, long activeVersion, Instant activatedAt) { }
