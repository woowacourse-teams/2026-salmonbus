package com.gustler.backend.forecasting.domain.statistics;

import java.time.LocalDate;
import java.util.Objects;

public record ReduceCursor(int stopOrder, TimeSlot timeSlot, LocalDate arrivalDate) {

    public ReduceCursor {
        Objects.requireNonNull(timeSlot, "시간대가 필요하다");
        Objects.requireNonNull(arrivalDate, "도착 날짜가 필요하다");
    }
}
