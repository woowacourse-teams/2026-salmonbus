package com.gustler.backend.api.push.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record SaveNotificationSettingRequest(
    String routeId,
    String stopId,
    String direction,
    String time,
    List<String> weekdays
) {
    public SaveNotificationSettingRequest {
        if (weekdays != null) {
            weekdays = Collections.unmodifiableList(new ArrayList<>(weekdays));
        }
    }
}
