package com.gustler.backend.collector;

import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import java.util.List;

public sealed interface GbisLocationResult {

    record Success(
        String gbisQueryTime,
        List<BusLocation> buses
    ) implements GbisLocationResult {
    }

    record NoVehicles(
        String gbisQueryTime
    ) implements GbisLocationResult {
    }

    record DailyQuotaExceeded() implements GbisLocationResult {
    }

    record PerSecondQuotaExceeded() implements GbisLocationResult {
    }

    record UnknownGbisResultCode(
        int resultCode,
        String message
    ) implements GbisLocationResult {
    }

    record GatewayRejected(
        String reasonCode,
        String message
    ) implements GbisLocationResult {
    }

    record GbisSystemError(
        String message
    ) implements GbisLocationResult {
    }

    record NoResponse(
        String message
    ) implements GbisLocationResult {
    }

    record UnreadableResponse(
        String message
    ) implements GbisLocationResult {
    }

    record MissingRequiredParameter(
        String message
    ) implements GbisLocationResult {
    }
}
