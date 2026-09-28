package com.gustler.backend.forecasting.application.evaluation;

import java.time.Duration;

public interface QueryTimeLimits {

    void apply(Duration statementTimeout, Duration lockTimeout);
}
