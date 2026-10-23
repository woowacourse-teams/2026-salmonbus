package com.gustler.backend.gbis.api;

import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import java.util.List;

/**
 * 위치정보 수신 결과와 실패 사유다.
 * 성공 목록은 상류 DTO의 null·원소·수정 가능성을 그대로 전달한다.
 */
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
        String gbisQueryTime,
        int resultCode,
        String message
    ) implements GbisLocationResult {
    }

    record GatewayRejected(
        String reasonCode,
        String errorCode,
        String message
    ) implements GbisLocationResult {
    }

    record GbisSystemError(
        String gbisQueryTime,
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
        String gbisQueryTime,
        String message
    ) implements GbisLocationResult {
    }
}
