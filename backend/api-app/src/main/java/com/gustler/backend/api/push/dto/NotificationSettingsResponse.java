package com.gustler.backend.api.push.dto;

import java.util.List;

public record NotificationSettingsResponse(
    List<NotificationSettingResponse> settings
) {
    public NotificationSettingsResponse {
        settings = List.copyOf(settings);
    }
}
