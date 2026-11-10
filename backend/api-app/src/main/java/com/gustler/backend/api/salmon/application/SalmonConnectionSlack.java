package com.gustler.backend.api.salmon.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 승차 위치에 도착한 뒤 버스가 오기 전에 남겨야 하는 여유. */
@Component
public class SalmonConnectionSlack {
    private final Duration slack;

    public SalmonConnectionSlack(@Value("${salmon.action.min-connection:15s}") Duration slack) {
        if (slack == null || slack.isNegative()) {
            throw new IllegalArgumentException("min-connection must be zero or positive");
        }
        this.slack = slack;
    }

    public Duration slack() {
        return slack;
    }

    public boolean connected(long busArrivalMillis, long personReadyMillis) {
        return busArrivalMillis - personReadyMillis >= slack.toMillis();
    }
}
