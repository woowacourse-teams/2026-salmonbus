package com.gustler.backend.api.chat.dto;

public record ChatRoomResponse(
    String routeId,
    String displayName,
    int historySize,
    int maxBodyCodePoints
) {
}
