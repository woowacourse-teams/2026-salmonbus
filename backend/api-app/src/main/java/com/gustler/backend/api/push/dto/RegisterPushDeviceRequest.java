package com.gustler.backend.api.push.dto;

public record RegisterPushDeviceRequest(String token, String environment) {
}
