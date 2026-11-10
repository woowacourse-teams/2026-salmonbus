package com.gustler.backend.api.push.dto;

import java.util.List;
import java.util.UUID;

public record NotificationSettingResponse(
    UUID id,
    String routeId,
    String stopId,
    String direction,
    String time,
    List<String> weekdays
) {
    public NotificationSettingResponse {
        weekdays = List.copyOf(weekdays);
    }
}
