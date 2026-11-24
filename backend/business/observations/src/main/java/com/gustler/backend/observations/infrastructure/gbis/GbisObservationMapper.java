package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisResultCode;
import com.gustler.backend.gbis.api.PortalReasonCode;
import com.gustler.backend.gbis.api.dto.BusLocationResponse;
import com.gustler.backend.observations.domain.CollectionBatchStatus;
import com.gustler.backend.observations.domain.CollectionFailureReason;
import com.gustler.backend.observations.domain.CollectionResult;
import com.gustler.backend.observations.domain.ObservationRows;
import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.VehicleObservation;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** GBIS 응답을 한 번 분류해 수집 결과와 관측 값으로 변환한다. */
public final class GbisObservationMapper {

    private GbisObservationMapper() {
    }

    /**
     * 차량 없음은 성공이며 차량이 모두 제외된 응답과 구분한다.
     * 읽지 못한 본문에는 확인된 기관 결과 코드를 기록하지 않는다.
     */
    public static CollectionResult response(GbisLocationResult upstreamResult, OffsetDateTime receivedAt) {
        return switch (upstreamResult) {
            case GbisLocationResult.Success success -> CollectionResult.received(
                    CollectionBatchStatus.forProviderRows(success.buses().size()),
                    GbisResultCode.SUCCESS.code(),
                    collect(success.buses()),
                    receivedAt
            );
            case GbisLocationResult.NoVehicles ignored -> CollectionResult.received(
                    CollectionBatchStatus.SUCCESS_EMPTY,
                    GbisResultCode.NO_VEHICLES.code(),
                    collect(List.of()),
                    receivedAt
            );
            case GbisLocationResult.DailyQuotaExceeded ignored -> rejectedBy(
                    CollectionFailureReason.DAILY_QUOTA_EXCEEDED,
                    null,
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.PerSecondQuotaExceeded ignored -> rejectedBy(
                    CollectionFailureReason.PER_SECOND_QUOTA_EXCEEDED,
                    null,
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.GatewayRejected ignored -> rejectedBy(
                    CollectionFailureReason.UPSTREAM_ERROR,
                    null,
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.GbisSystemError ignored -> rejectedBy(
                    CollectionFailureReason.UPSTREAM_ERROR,
                    GbisResultCode.SYSTEM_FAILURE.code(),
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.MissingRequiredParameter ignored -> rejectedBy(
                    CollectionFailureReason.UPSTREAM_ERROR,
                    GbisResultCode.PARAMETER_MISSING.code(),
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.UnknownGbisResultCode unknownResult -> rejectedBy(
                    CollectionFailureReason.UPSTREAM_ERROR,
                    unknownResult.resultCode(),
                    upstreamResult,
                    receivedAt
            );
            case GbisLocationResult.NoResponse ignored -> CollectionResult.unconfirmed(receivedAt);
            case GbisLocationResult.UnreadableResponse ignored -> CollectionResult.failed(
                    CollectionBatchStatus.FAILED_UNREADABLE,
                    null,
                    null,
                    receivedAt
            );
        };
    }

    static Optional<String> keyRejectionCodeOf(GbisLocationResult upstreamResult) {
        return switch (upstreamResult) {
            case GbisLocationResult.DailyQuotaExceeded ignored ->
                Optional.of(PortalReasonCode.DAILY_QUOTA_EXCEEDED.code());
            case GbisLocationResult.GatewayRejected rejected -> switch (PortalReasonCode.from(rejected.reasonCode())) {
                case UNREGISTERED_KEY, EXPIRED_KEY -> Optional.of(rejected.reasonCode());
                default -> Optional.empty();
            };
            default -> Optional.empty();
        };
    }

    private static CollectionResult rejectedBy(
        CollectionFailureReason failureCode,
        Integer upstreamResultCode,
        GbisLocationResult upstreamResult,
        OffsetDateTime receivedAt
    ) {
        return keyRejectionCodeOf(upstreamResult)
            .map(code -> CollectionResult.failedByRejectedKey(
                    CollectionBatchStatus.FAILED_UPSTREAM,
                    failureCode,
                    upstreamResultCode,
                    receivedAt,
                    code
            ))
            .orElseGet(() -> CollectionResult.failed(
                    CollectionBatchStatus.FAILED_UPSTREAM,
                    failureCode,
                    upstreamResultCode,
                    receivedAt
            ));
    }

    public static VehicleObservation observation(BusLocationResponse.BusLocation bus) {
        return new VehicleObservation(
                bus.vehicleId(),
                bus.plateNumber(),
                bus.stopSequence(),
                bus.stopId(),
                bus.runningStatus(),
                RemainingSeats.from(bus.remainingSeatCount()),
                bus.crowdLevel(),
                bus.vehicleType(),
                bus.routeType(),
                bus.taglessCode()
        );
    }

    public static ObservationRows collect(List<BusLocationResponse.BusLocation> buses) {
        return ObservationRows.fromObservations(buses.stream()
                .map(GbisObservationMapper::observation)
                .toList());
    }
}
