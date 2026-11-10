package com.gustler.backend.api.push.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record UnregisterPushDeviceRequest(
    @Schema(description = "APNs 기기 토큰. 64자리 16진수", requiredMode = Schema.RequiredMode.REQUIRED)
    String token
) {
}
