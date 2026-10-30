package com.gustler.backend.api.route.domain;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(
    requiredProperties = {
        "routeId",
        "managed",
        "state",
        "forecastAllowed",
        "collectionEnabled",
        "reason",
        "message",
        "sourceUrl",
        "checkedAt",
        "validUntil"
    })
public record RouteServiceStatus(
    String routeId,
    boolean managed,
    String state,
    boolean forecastAllowed,
    boolean collectionEnabled,
    String reason,
    String message,
    String sourceUrl,
    Instant checkedAt,
    Instant validUntil) {
}
