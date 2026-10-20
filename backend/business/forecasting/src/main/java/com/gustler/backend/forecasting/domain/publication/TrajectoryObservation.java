package com.gustler.backend.forecasting.domain.publication;

import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Known;
import com.gustler.backend.forecasting.domain.model.ObservedSeats.Unknown;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.SeatUnknownReason;
/**
 * 궤적 연결에 사용하는 관측 한 건이다.
 *
 * <p>여정 키는 예보 입력이 아니라 관측의 연결 여부를 판정하는 근거이므로 {@link ObservedVehicle} 에
 * 두지 않고 여기서 보관한다. 수집 때는 비어 있으며 품질 판정이 끝나면 forecasting 이 편도 키를 채운다.
 *
 * <p>잔여석과 함께 모르는 사유도 보관한다. {@link ObservedVehicle} 은 예보에 전달할 값만 담아
 * 모르는 사유를 보관할 필드가 없다.
 */
public record TrajectoryObservation(
    long vehicleObservationId,
    ObservedVehicle vehicle,
    String vehicleTripKey,
    ObservedSeats seats
) {
    private static final int NO_SEAT_LEFT = 0;

    public String vehicleId() {
        return vehicle.vehicleId();
    }

    public int passedStopOrder() {
        return vehicle.passedStopOrder();
    }

    /** 만석 여부를 판정한다. 좌석을 모르는 관측은 만석도 여유도 아니다. */
    public boolean isFull() {
        return seats instanceof Known known && known.seats() == NO_SEAT_LEFT;
    }

    public boolean isForecastEligible() {
        return !(seats instanceof Unknown unknown
            && unknown.reason() == SeatUnknownReason.QUALITY_WITHHELD);
    }
}
