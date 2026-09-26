package com.gustler.backend.observations.infrastructure.gbis;

import com.gustler.backend.observations.domain.CollectedObservations;
import com.gustler.backend.observations.domain.ObservationBatchConclusion;
import com.gustler.backend.observations.domain.ObservationBatchFailureCode;
import com.gustler.backend.observations.domain.ObservationBatchOutcome;
import com.gustler.backend.observations.domain.RemainingSeats;
import com.gustler.backend.observations.domain.VehicleObservation;
import com.gustler.backend.gbis.api.GbisLocationResult;
import com.gustler.backend.gbis.api.GbisResultCode;
import com.gustler.backend.gbis.api.dto.BusLocationResponse.BusLocation;
import java.util.List;
import java.time.OffsetDateTime;
import com.gustler.backend.observations.domain.ObservationResponse;

import com.gustler.backend.gbis.api.GbisLocationResult.DailyQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.GatewayRejected;
import com.gustler.backend.gbis.api.GbisLocationResult.GbisSystemError;
import com.gustler.backend.gbis.api.GbisLocationResult.MissingRequiredParameter;
import com.gustler.backend.gbis.api.GbisLocationResult.NoResponse;
import com.gustler.backend.gbis.api.GbisLocationResult.NoVehicles;
import com.gustler.backend.gbis.api.GbisLocationResult.PerSecondQuotaExceeded;
import com.gustler.backend.gbis.api.GbisLocationResult.Success;
import com.gustler.backend.gbis.api.GbisLocationResult.UnknownGbisResultCode;
import com.gustler.backend.gbis.api.GbisLocationResult.UnreadableResponse;


/** GBIS 응답을 수집 업무의 결과와 관측 값으로 변환한다. */
public final class GbisObservationMapper {
    private GbisObservationMapper() {
    }

    public static ObservationResponse response(GbisLocationResult result, OffsetDateTime receivedAt) {
        ObservationBatchConclusion conclusion = from(result);
        return switch (result) {
            case Success success -> ObservationResponse.received(conclusion, collect(success.buses()), receivedAt);
            case NoVehicles ignored -> ObservationResponse.received(conclusion, collect(List.of()), receivedAt);
            default -> ObservationResponse.failed(conclusion, receivedAt);
        };
    }

    public static ObservationBatchConclusion from(
        GbisLocationResult result
    ) {
        return switch (result) {
            case Success success -> succeededWith(success.buses().size());
            // 호출은 정상인데 그 순간 운행 차량이 없다. 실패가 아니고 운행이 끝났다는 뜻도 아니다.
            // 실측에서 심야에 이 응답이 되풀이된 뒤 다음 수집에서 차량이 다시 나타났다.
            case NoVehicles ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.SUCCESS_EMPTY, null, GbisResultCode.NO_VEHICLES.code());
            case DailyQuotaExceeded ignored ->
                rejectedBy(ObservationBatchFailureCode.DAILY_QUOTA_EXCEEDED, null);
            case PerSecondQuotaExceeded ignored ->
                rejectedBy(ObservationBatchFailureCode.PER_SECOND_QUOTA_EXCEEDED, null);
            // 게이트웨이가 GBIS 앞에서 막은 것이라 GBIS 결과 코드가 없다.
            case GatewayRejected ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, null);
            case GbisSystemError ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, GbisResultCode.SYSTEM_FAILURE.code());
            case MissingRequiredParameter ignored ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, GbisResultCode.PARAMETER_MISSING.code());
            case UnknownGbisResultCode unknown ->
                rejectedBy(ObservationBatchFailureCode.UPSTREAM_ERROR, unknown.resultCode());
            case NoResponse ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.UNKNOWN_AFTER_DISPATCH, null, null);
            // 결과 코드가 0 이었는데 본문이 없던 경우도 여기로 온다. 갈래가 코드를 안 들고 나와 0 이 남지 않는다.
            case UnreadableResponse ignored -> new ObservationBatchConclusion(
                ObservationBatchOutcome.FAILED_UNREADABLE, null, null);
        };
    }

    private static ObservationBatchConclusion succeededWith(
        final int providerRows
    ) {
        return new ObservationBatchConclusion(
            ObservationBatchOutcome.forProviderRows(providerRows), null, GbisResultCode.SUCCESS.code());
    }

    private static ObservationBatchConclusion rejectedBy(
        ObservationBatchFailureCode failureCode,
        Integer upstreamResultCode
    ) {
        return new ObservationBatchConclusion(
            ObservationBatchOutcome.FAILED_UPSTREAM, failureCode, upstreamResultCode);
    }

    public static VehicleObservation observation(BusLocation bus) {
        return new VehicleObservation(bus.vehicleId(), bus.plateNumber(), bus.stopSequence(), bus.stopId(),
            bus.runningStatus(), RemainingSeats.from(bus.remainingSeatCount()), bus.crowdLevel(),
            bus.vehicleType(), bus.routeType(), bus.taglessCode());
    }

    public static CollectedObservations collect(List<BusLocation> buses) {
        return CollectedObservations.fromObservations(buses.stream().map(GbisObservationMapper::observation).toList());
    }
}
