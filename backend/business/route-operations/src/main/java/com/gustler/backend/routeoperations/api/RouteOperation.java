package com.gustler.backend.routeoperations.api;

import java.time.Instant;

/**
 * Requested and applied values are separate: a persisted ON request is not publication readiness.
 */
public record RouteOperation(
    String routeId,
    String registrationState,
    boolean collectionEnabled,
    boolean serviceRequested,
    boolean serviceEnabled,
    boolean everPublished,
    String reason,
    String message,
    String sourceUrl,
    long revision,
    long appliedRevision,
    Instant enableAfter,
    String lastError,
    Instant requestedAt,
    Instant appliedAt,
    String geometryState) {
    public RouteOperation(
        String routeId,
        String registrationState,
        final boolean collectionEnabled,
        final boolean serviceRequested,
        final boolean serviceEnabled,
        final boolean everPublished,
        String reason,
        String message,
        String sourceUrl,
        final long revision,
        final long appliedRevision,
        Instant enableAfter,
        String lastError,
        Instant requestedAt,
        Instant appliedAt
    ) {
        this(
            routeId,
            registrationState,
            collectionEnabled,
            serviceRequested,
            serviceEnabled,
            everPublished,
            reason,
            message,
            sourceUrl,
            revision,
            appliedRevision,
            enableAfter,
            lastError,
            requestedAt,
            appliedAt,
            "UNKNOWN");
    }

    public static void requireRouteId(String id) {
        if (id == null || !id.matches("[0-9]{9}")) {
            throw new IllegalArgumentException("INVALID_ROUTE_ID");
        }
    }

    public boolean canCollect() {
        return collectionEnabled && registrationState.equals("READY");
    }

    public boolean canGenerate() {
        return canCollect() && serviceRequested;
    }

    public String state() {
        if (!registrationState.equals("READY")) {
            return registrationState;
        }
        if (!serviceRequested) {
            return "OFF";
        }
        if (serviceEnabled) {
            return "ON";
        }
        return "WARMING_UP";
    }
}
