package com.gustler.backend.forecasting.application.statistics;

import java.time.Instant;

public record StatisticsStep(Status status, String phase, Instant dataUntil) {

    public enum Status { IDLE, WAITING, PROGRESSED, STARTED, COMPLETED }
}
