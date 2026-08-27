package com.gustler.backend.api.board.application;

public record DepartureSchedule(
    String upFirstDepartureTime,
    String upLastDepartureTime,
    String downFirstDepartureTime,
    String downLastDepartureTime
) {
}
